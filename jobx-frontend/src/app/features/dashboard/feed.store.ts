import { Injectable, computed, inject, signal } from '@angular/core';
import { MatchApi } from '../../core/api/match.api';
import { AppError } from '../../core/models/api-error.model';
import { MatchResponse, MatchStatus } from '../../core/models/match.model';
import { ToastService } from '../../core/services/toast.service';
import {
  PAGE_SIZE,
  SortMode,
  StatusFilter,
  applyView,
  clampPage,
  countByStatus,
  pageNumbers,
  pageRangeLabel,
  pageSlice,
  totalPages,
} from './feed-logic';

/**
 * The heart of the app (uiux_plan.md §6).
 *
 *   matches ──▶ filtered ──▶ searched ──▶ sorted ──▶ paged
 *      └──────▶ statusCounts        └──▶ totalPages
 *
 * One signal holds the feed; every other view of it is `computed`, so no two
 * arrays can drift out of sync.
 */
@Injectable({ providedIn: 'root' })
export class FeedStore {
  private readonly api = inject(MatchApi);
  private readonly toasts = inject(ToastService);

  private readonly matchesSignal = signal<MatchResponse[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly errorSignal = signal<AppError | null>(null);
  private readonly loadedSignal = signal(false);
  /** Ids with an in-flight PATCH, so the card can show it isn't settled yet. */
  private readonly pendingSignal = signal<ReadonlySet<string>>(new Set());

  private readonly statusSignal = signal<StatusFilter>('ALL');
  private readonly querySignal = signal('');
  private readonly sortSignal = signal<SortMode>('score');
  private readonly pageSignal = signal(1);

  readonly matches = this.matchesSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly loaded = this.loadedSignal.asReadonly();
  readonly pending = this.pendingSignal.asReadonly();

  readonly status = this.statusSignal.asReadonly();
  readonly query = this.querySignal.asReadonly();
  readonly sort = this.sortSignal.asReadonly();

  /** filter -> search -> sort. Pagination happens after this, never before. */
  readonly visible = computed(() =>
    applyView(this.matchesSignal(), {
      status: this.statusSignal(),
      query: this.querySignal(),
      sort: this.sortSignal(),
    }),
  );

  readonly statusCounts = computed(() => countByStatus(this.matchesSignal()));
  readonly totalPages = computed(() => totalPages(this.visible().length, PAGE_SIZE));

  /**
   * The page actually rendered: clamped, so a shrinking list can't strand us on
   * an empty page.
   *
   * Clamping waits for the feed to load. Before that `totalPages()` is 1 for the
   * empty array, and clamping eagerly would turn a deep-linked `?page=3` into
   * page 1 on every refresh — the URL would silently lose the page it was asked
   * for (uiux_plan.md §4: "a view survives refresh and is shareable").
   */
  readonly page = computed(() =>
    this.loadedSignal() ? clampPage(this.pageSignal(), this.totalPages()) : this.pageSignal(),
  );
  readonly paged = computed(() => pageSlice(this.visible(), this.page(), PAGE_SIZE));
  readonly pageItems = computed(() => pageNumbers(this.page(), this.totalPages()));
  readonly range = computed(() => pageRangeLabel(this.visible().length, this.page(), PAGE_SIZE));
  readonly showPagination = computed(() => this.visible().length > PAGE_SIZE);

  readonly isSearching = computed(() => this.querySignal().trim() !== '');
  readonly isFiltered = computed(() => this.isSearching() || this.statusSignal() !== 'ALL');

  /* ------------------------------------------------------------- loading -- */

  load(options: { force?: boolean } = {}): void {
    if (this.loadingSignal()) return;
    if (this.loadedSignal() && !options.force) return;

    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    this.api.list().subscribe({
      next: (matches) => {
        this.matchesSignal.set(matches);
        this.loadedSignal.set(true);
        this.loadingSignal.set(false);
      },
      error: (error: AppError) => {
        this.errorSignal.set(error);
        this.loadingSignal.set(false);
      },
    });
  }

  /** After a manual "Check now" reported new matches, the feed is stale. */
  reload(): void {
    this.load({ force: true });
  }

  /* -------------------------------------------------------- view controls -- */
  /* Each of these resets to page 1 — the old page number means nothing once the
     result set changes (uiux_plan.md §4). */

  setStatusFilter(status: StatusFilter): void {
    if (this.statusSignal() === status) return;
    this.statusSignal.set(status);
    this.pageSignal.set(1);
  }

  setQuery(query: string): void {
    if (this.querySignal() === query) return;
    this.querySignal.set(query);
    this.pageSignal.set(1);
  }

  setSort(sort: SortMode): void {
    if (this.sortSignal() === sort) return;
    this.sortSignal.set(sort);
    this.pageSignal.set(1);
  }

  setPage(page: number): void {
    // Sanitize only — the clamp against the real page count happens in `page`
    // once the feed has actually loaded.
    this.pageSignal.set(Math.max(1, Math.trunc(page) || 1));
  }

  clearFilters(): void {
    this.statusSignal.set('ALL');
    this.querySignal.set('');
    this.pageSignal.set(1);
  }

  /* ------------------------------------------------------ status updates -- */

  /**
   * Optimistic: flip the signal now, PATCH, roll back and toast on failure.
   * The toast keeps the mockup's Undo affordance, which issues the reverse
   * PATCH rather than only reverting the local copy.
   */
  setStatus(id: string, next: MatchStatus, options: { undoable?: boolean } = {}): void {
    const current = this.matchesSignal().find((m) => m.id === id);
    if (!current || current.status === next) return;

    const previous = current.status;
    this.patch(id, next);

    const label = STATUS_COPY[next];
    if (options.undoable !== false) {
      this.toasts.show(`${label} · ${current.jobTitle}`, {
        undo: () => this.setStatus(id, previous, { undoable: false }),
      });
    }
  }

  private patch(id: string, next: MatchStatus): void {
    const before = this.matchesSignal();
    this.applyStatus(id, next);
    this.markPending(id, true);

    this.api.updateStatus(id, next).subscribe({
      next: (updated) => {
        // Trust the server's row over our optimistic guess.
        this.matchesSignal.update((list) => list.map((m) => (m.id === id ? updated : m)));
        this.markPending(id, false);
      },
      error: (error: AppError) => {
        this.matchesSignal.set(before);
        this.markPending(id, false);
        this.toasts.error(
          error.status === 404
            ? "That match no longer exists — refresh the feed."
            : `Couldn't save that change — ${error.detail}`,
        );
      },
    });
  }

  private applyStatus(id: string, status: MatchStatus): void {
    this.matchesSignal.update((list) =>
      list.map((m) => (m.id === id ? { ...m, status } : m)),
    );
  }

  private markPending(id: string, pending: boolean): void {
    this.pendingSignal.update((set) => {
      const next = new Set(set);
      if (pending) next.add(id);
      else next.delete(id);
      return next;
    });
  }
}

const STATUS_COPY: Record<MatchStatus, string> = {
  SEEN: 'Saved for later',
  APPLIED: 'Marked as applied',
  DISMISSED: 'Dismissed',
  NEW: 'Restored',
};
