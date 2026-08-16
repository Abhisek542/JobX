import { Injectable, computed, inject, signal } from '@angular/core';
import { WatchlistApi } from '../api/watchlist.api';
import { AppError } from '../models/api-error.model';
import {
  CompanyStatus,
  ManualFetchResponse,
  WatchedCompanyRequest,
  WatchedCompanyResponse,
} from '../models/watchlist.model';
import { FeedStore } from '../../features/dashboard/feed.store';
import { ToastService } from './toast.service';

export interface WatchlistHealth {
  active: number;
  healthy: number;
  failing: number;
  pending: number;
  paused: number;
  unsupported: number;
  /** healthy / active, for the ring only. Never rendered as a percentage. */
  ratio: number;
  headline: string;
  /** "3 checking fine · 1 refresh issue · 1 awaiting first check" */
  detail: string;
  needsAttention: boolean;
}

@Injectable({ providedIn: 'root' })
export class WatchlistStore {
  private readonly api = inject(WatchlistApi);
  private readonly toasts = inject(ToastService);
  private readonly feed = inject(FeedStore);

  private readonly companiesSignal = signal<WatchedCompanyResponse[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);
  private readonly errorSignal = signal<AppError | null>(null);
  private readonly checkingSignal = signal<ReadonlySet<string>>(new Set());

  readonly companies = this.companiesSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly loaded = this.loadedSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly checking = this.checkingSignal.asReadonly();

  readonly isEmpty = computed(() => this.loadedSignal() && this.companiesSignal().length === 0);

  /**
   * Health is computed here, client-side, from real `lastFetchStatus` values —
   * the backend has no health endpoint. Concrete counts only, never a fake
   * percentage (uiux_plan.md §7, frontend_constraints.md §7).
   */
  readonly health = computed<WatchlistHealth>(() => {
    const all = this.companiesSignal();
    const active = all.filter((c) => c.status === 'ACTIVE');
    const healthy = active.filter((c) => c.lastFetchStatus === 'SUCCESS');
    const failing = active.filter((c) => c.lastFetchStatus === 'FAILED');
    const pending = active.filter((c) => c.lastFetchStatus === null);
    const paused = all.filter((c) => c.status === 'PAUSED');
    const unsupported = all.filter((c) => c.status === 'UNSUPPORTED');

    const bits: string[] = [`${healthy.length} checking fine`];
    if (failing.length) bits.push(`${failing.length} refresh issue`);
    if (pending.length) bits.push(`${pending.length} awaiting first check`);
    if (paused.length) bits.push(`${paused.length} paused`);
    if (unsupported.length) {
      bits.push(`${unsupported.length} unsupported board${unsupported.length > 1 ? 's' : ''}`);
    }

    return {
      active: active.length,
      healthy: healthy.length,
      failing: failing.length,
      pending: pending.length,
      paused: paused.length,
      unsupported: unsupported.length,
      ratio: active.length ? healthy.length / active.length : 0,
      headline: failing.length ? 'Needs attention' : 'Healthy',
      detail: bits.join(' · '),
      needsAttention: failing.length > 0,
    };
  });

  /** Newest check first, so the rail's top rows are the informative ones. */
  readonly ordered = computed(() =>
    [...this.companiesSignal()].sort((a, b) => a.companyName.localeCompare(b.companyName)),
  );

  load(options: { force?: boolean } = {}): void {
    if (this.loadingSignal()) return;
    if (this.loadedSignal() && !options.force) return;

    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    this.api.list().subscribe({
      next: (companies) => {
        this.companiesSignal.set(companies);
        this.loadedSignal.set(true);
        this.loadingSignal.set(false);
      },
      error: (error: AppError) => {
        this.errorSignal.set(error);
        this.loadingSignal.set(false);
      },
    });
  }

  /** Resolves to the created company, or rejects with the AppError (409 etc.). */
  add(request: WatchedCompanyRequest): Promise<WatchedCompanyResponse> {
    return new Promise((resolve, reject) => {
      this.api.add(request).subscribe({
        next: (company) => {
          this.companiesSignal.update((list) => [...list, company]);
          resolve(company);
        },
        error: (error: AppError) => reject(error),
      });
    });
  }

  updateStatus(id: string, status: CompanyStatus): void {
    this.api.updateStatus(id, status).subscribe({
      next: (company) => {
        this.replace(company);
        this.toasts.ok(
          status === 'PAUSED'
            ? `${company.companyName} paused — Jobx will stop checking that board`
            : `${company.companyName} is active again`,
        );
      },
      error: (error: AppError) => this.toasts.error(error.detail),
    });
  }

  remove(company: WatchedCompanyResponse): void {
    this.api.remove(company.id).subscribe({
      next: () => {
        this.companiesSignal.update((list) => list.filter((c) => c.id !== company.id));
        // Deleting a watch cascades to its jobs and matches server-side.
        this.feed.reload();
        this.toasts.ok(`${company.companyName} removed from your watchlist`);
      },
      error: (error: AppError) => this.toasts.error(error.detail),
    });
  }

  isChecking(id: string): boolean {
    return this.checkingSignal().has(id);
  }

  /**
   * "Check now" — POST /watchlist/{id}/fetch.
   *
   * Every documented failure gets its own message (uiux_plan.md §8). The 502
   * especially: before the 2026-08-15 backend fix an unreachable board returned
   * a cheerful 200 {newJobs: 0}, so a board that had been 404ing for a week read
   * as "checked just now, nothing new". It must never fall back into that path.
   */
  checkNow(company: WatchedCompanyResponse): void {
    if (this.isChecking(company.id)) return;
    this.markChecking(company.id, true);

    this.api.fetchNow(company.id).subscribe({
      next: (result: ManualFetchResponse) => {
        this.markChecking(company.id, false);
        this.patchLocal(company.id, {
          lastFetchedAt: result.checkedAt,
          lastFetchStatus: 'SUCCESS',
        });

        if (result.newMatches > 0) {
          this.feed.reload();
          this.toasts.ok(
            `Checked just now · ${result.newMatches} new match${
              result.newMatches > 1 ? 'es' : ''
            } at ${company.companyName}`,
          );
        } else if (result.newJobs > 0) {
          // Honest: new postings existed, none cleared the filter profile.
          this.toasts.show(
            `Checked just now · ${result.newJobs} new role${
              result.newJobs > 1 ? 's' : ''
            } at ${company.companyName}, none matched your keywords`,
          );
        } else {
          this.toasts.show(`Checked just now · no new roles at ${company.companyName}`);
        }
      },
      error: (error: AppError) => {
        this.markChecking(company.id, false);
        this.toasts.error(this.fetchErrorCopy(error, company));

        if (error.status === 502) {
          this.patchLocal(company.id, { lastFetchStatus: 'FAILED' });
        }
        if (error.status === 404) {
          this.load({ force: true });
        }
      },
    });
  }

  private fetchErrorCopy(error: AppError, company: WatchedCompanyResponse): string {
    switch (error.status) {
      case 429:
        // The backend's detail already reads "checked recently — try again in
        // 235s", and it is the only place the real remaining time exists. Name
        // the company and pass it through rather than restating it.
        return `${company.companyName}: ${error.detail}`;
      case 409:
        return `${company.companyName} is ${company.status.toLowerCase()} — only active companies can be checked`;
      case 502:
        return `${company.companyName}'s board is unreachable — Jobx will retry automatically`;
      case 404:
        return `${company.companyName} is no longer on your watchlist`;
      default:
        return error.detail;
    }
  }

  private patchLocal(id: string, patch: Partial<WatchedCompanyResponse>): void {
    this.companiesSignal.update((list) =>
      list.map((c) => (c.id === id ? { ...c, ...patch } : c)),
    );
  }

  private replace(company: WatchedCompanyResponse): void {
    this.companiesSignal.update((list) =>
      list.map((c) => (c.id === company.id ? company : c)),
    );
  }

  private markChecking(id: string, checking: boolean): void {
    this.checkingSignal.update((set) => {
      const next = new Set(set);
      if (checking) next.add(id);
      else next.delete(id);
      return next;
    });
  }
}
