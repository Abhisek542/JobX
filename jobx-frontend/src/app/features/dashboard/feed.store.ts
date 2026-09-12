import { Injectable, computed, inject, signal } from '@angular/core';
import { MatchApi } from '../../core/api/match.api';
import { AppError } from '../../core/models/api-error.model';
import { MatchResponse, MatchStatus } from '../../core/models/match.model';
import { ToastService } from '../../core/services/toast.service';
import {
  GROUP_PAGE_SIZE,
  GroupOrder,
  PAGE_SIZE,
  SortMode,
  StatusFilter,
  applyView,
  clampPage,
  countByStatus,
  groupByCompany,
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

  /** View mode, not a filter — grouping composes with any status pill. */
  private readonly groupedSignal = signal(false);
  private readonly groupOrderSignal = signal<GroupOrder>('score');
  /** Collapsed company ids. Session-only, like the status pill. */
  private readonly collapsedSignal = signal<ReadonlySet<string>>(new Set());

  /**
   * Bumped by reset() on sign-out. Every async callback captures it before the
   * request goes out and bails if it changed, so a response from the previous
   * user's session can never write into the next user's store.
   */
  private epoch = 0;

  readonly matches = this.matchesSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly loaded = this.loadedSignal.asReadonly();
  readonly pending = this.pendingSignal.asReadonly();

  readonly status = this.statusSignal.asReadonly();
  readonly query = this.querySignal.asReadonly();
  readonly sort = this.sortSignal.asReadonly();
  readonly grouped = this.groupedSignal.asReadonly();
  readonly groupOrder = this.groupOrderSignal.asReadonly();
  readonly collapsed = this.collapsedSignal.asReadonly();

  /** filter -> search -> sort. Pagination happens after this, never before. */
  readonly visible = computed(() =>
    applyView(this.matchesSignal(), {
      status: this.statusSignal(),
      query: this.querySignal(),
      sort: this.sortSignal(),
    }),
  );

  readonly statusCounts = computed(() => countByStatus(this.matchesSignal()));

  /**
   * Grouping runs on `visible()`, i.e. after filter -> search -> sort, so the
   * two views can never disagree about which roles exist or how they rank.
   */
  readonly groups = computed(() => groupByCompany(this.visible(), this.groupOrderSignal()));

  /**
   * Pagination is mode-aware: the flat view pages 10 ROLES, the grouped view
   * pages 5 COMPANIES. Paging the grouped view by role would split a company
   * across a page boundary — the exact defect grouping exists to fix.
   */
  private readonly pageSize = computed(() => (this.groupedSignal() ? GROUP_PAGE_SIZE : PAGE_SIZE));
  private readonly itemCount = computed(() =>
    this.groupedSignal() ? this.groups().length : this.visible().length,
  );

  readonly totalPages = computed(() => totalPages(this.itemCount(), this.pageSize()));

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
  /** The groups actually rendered. `pageSlice` is generic — same machinery. */
  readonly pagedGroups = computed(() =>
    pageSlice(this.groups(), this.page(), GROUP_PAGE_SIZE),
  );
  readonly pageItems = computed(() => pageNumbers(this.page(), this.totalPages()));
  readonly range = computed(() => pageRangeLabel(this.itemCount(), this.page(), this.pageSize()));
  readonly showPagination = computed(() => this.itemCount() > this.pageSize());

  readonly isSearching = computed(() => this.querySignal().trim() !== '');
  readonly isFiltered = computed(() => this.isSearching() || this.statusSignal() !== 'ALL');

  /* ------------------------------------------------------------- loading -- */

  load(options: { force?: boolean } = {}): void {
    if (this.loadingSignal()) return;
    if (this.loadedSignal() && !options.force) return;

    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    const epoch = this.epoch;
    this.api.list().subscribe({
      next: (matches) => {
        if (epoch !== this.epoch) return;
        this.matchesSignal.set(matches);
        this.loadedSignal.set(true);
        this.loadingSignal.set(false);
      },
      error: (error: AppError) => {
        if (epoch !== this.epoch) return;
        this.errorSignal.set(error);
        this.loadingSignal.set(false);
      },
    });
  }

  /** After a manual "Check now" reported new matches, the feed is stale. */
  reload(): void {
    this.load({ force: true });
  }

  /**
   * Back to the state of a fresh app boot. Called by AuthStore.clear() on every
   * sign-out, including the 401 path. View state goes too: it is session-only by
   * design, and `collapsed` holds the previous user's company ids.
   */
  reset(): void {
    this.epoch++;
    this.matchesSignal.set([]);
    this.loadingSignal.set(false);
    this.errorSignal.set(null);
    this.loadedSignal.set(false);
    this.pendingSignal.set(new Set());
    this.statusSignal.set('ALL');
    this.querySignal.set('');
    this.sortSignal.set('score');
    this.pageSignal.set(1);
    this.groupedSignal.set(false);
    this.groupOrderSignal.set('score');
    this.collapsedSignal.set(new Set());
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

  /**
   * Page 1 on every switch — page 3 of 9 companies means nothing as page 3 of
   * 43 roles, and the two modes count different things.
   */
  setGrouped(grouped: boolean): void {
    if (this.groupedSignal() === grouped) return;
    this.groupedSignal.set(grouped);
    this.pageSignal.set(1);
  }

  setGroupOrder(order: GroupOrder): void {
    if (this.groupOrderSignal() === order) return;
    this.groupOrderSignal.set(order);
    this.pageSignal.set(1);
  }

  toggleCollapsed(companyId: string): void {
    this.collapsedSignal.update((set) => {
      const next = new Set(set);
      if (next.has(companyId)) next.delete(companyId);
      else next.add(companyId);
      return next;
    });
  }

  expandAll(): void {
    this.collapsedSignal.set(new Set());
  }

  /** Collapses every group currently in the feed, not just this page. */
  collapseAll(): void {
    this.collapsedSignal.set(new Set(this.groups().map((g) => g.companyId)));
  }

  setPage(page: number): void {
    // Sanitize only — the clamp against the real page count happens in `page`
    // once the feed has actually loaded.
    this.pageSignal.set(Math.max(1, Math.trunc(page) || 1));
  }

  /** Filters only. Grouping is a view mode and deliberately survives this. */
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

    const epoch = this.epoch;
    this.api.updateStatus(id, next).subscribe({
      next: (updated) => {
        if (epoch !== this.epoch) return;
        // Trust the server's row over our optimistic guess.
        this.matchesSignal.update((list) => list.map((m) => (m.id === id ? updated : m)));
        this.markPending(id, false);
      },
      error: (error: AppError) => {
        if (epoch !== this.epoch) return;
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
