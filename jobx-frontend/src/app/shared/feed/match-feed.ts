import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { MatchStatus } from '../../core/models/match.model';
import { FilterProfileStore } from '../../core/services/filter-profile.store';
import { ToastService } from '../../core/services/toast.service';
import { UiStore } from '../../core/services/ui.store';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { relTime } from '../../core/util/time';
import { FeedStore } from '../../features/dashboard/feed.store';
import { StatusFilter } from '../../features/dashboard/feed-logic';
import { MatchDetailDrawer } from '../overlays/match-detail-drawer';
import { EmptyState } from '../ui/empty-state';
import { FeedToolbar } from './feed-toolbar';
import { MatchCard } from './match-card';
import { Pagination } from './pagination';

const FEED_TITLE: Record<StatusFilter, string> = {
  ALL: 'Top matches',
  NEW: 'New matches',
  SEEN: 'Saved for later',
  APPLIED: 'Applied',
  DISMISSED: 'Dismissed',
};

const EMPTY_TITLE: Record<StatusFilter, string> = {
  ALL: 'No matching roles yet',
  NEW: 'Nothing new since the last check',
  SEEN: "You haven't saved anything yet",
  APPLIED: 'No applications tracked yet',
  DISMISSED: 'Nothing dismissed',
};

/**
 * The whole feed surface: toolbar, cards, pagination, the §8 empty/error
 * matrix, and the detail drawer. Shared by /dashboard and /matches so the two
 * can never drift.
 */
@Component({
  selector: 'app-match-feed',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, FeedToolbar, MatchCard, MatchDetailDrawer, Pagination],
  template: `
    <app-feed-toolbar
      [query]="feed.query()"
      [status]="feed.status()"
      [sort]="feed.sort()"
      [counts]="feed.statusCounts()"
      (queryChange)="feed.setQuery($event)"
      (statusChange)="feed.setStatusFilter($event)"
      (sortChange)="feed.setSort($event)"
    />

    <div class="feed-head">
      <h2>{{ title() }}</h2>
      @if (feed.visible().length) {
        <span class="sub">{{ subtitle() }}</span>
      }
    </div>

    @if (feed.loading() && !feed.loaded()) {
      <div aria-busy="true" aria-label="Loading matches">
        @for (i of skeletons; track i) {
          <div class="skeleton"></div>
        }
      </div>
    } @else if (feed.error(); as error) {
      <app-empty-state
        icon="alert"
        tone="bad"
        heading="Couldn't load your matches"
        [body]="error.detail"
      >
        <button class="btn btn-primary" type="button" (click)="feed.reload()">Try again</button>
      </app-empty-state>
    } @else if (feed.paged().length) {
      <div class="cards">
        @for (match of feed.paged(); track match.id; let i = $index) {
          <app-match-card
            [match]="match"
            [index]="i"
            [pending]="feed.pending().has(match.id)"
            (statusChange)="feed.setStatus(match.id, $event)"
            (openDetails)="openDrawer(match.id)"
          />
        }
      </div>

      @if (feed.showPagination()) {
        <app-pagination
          [page]="feed.page()"
          [totalPages]="feed.totalPages()"
          [items]="feed.pageItems()"
          [range]="feed.range()"
          (pageChange)="feed.setPage($event)"
        />
      }

      <div class="feed-foot">{{ footer() }}</div>
    } @else if (feed.isFiltered()) {
      <!-- The feed has rows; this view just doesn't. -->
      <app-empty-state [heading]="filteredEmptyTitle()" [body]="filteredEmptyBody()">
        <button class="btn btn-ghost" type="button" (click)="feed.clearFilters()">
          Clear filters
        </button>
        <button class="btn btn-primary" type="button" (click)="ui.openPreferences()">
          Edit keywords
        </button>
      </app-empty-state>
    } @else if (watchlist.isEmpty()) {
      <app-empty-state
        icon="star"
        heading="Add your first company"
        body="Jobx only reads boards you're watching. Add a company and Jobx checks its careers page every 30 minutes — you see new roles before they reach the aggregators."
      >
        <button class="btn btn-primary" type="button" (click)="ui.openAddCompany()">
          Add company
        </button>
      </app-empty-state>
    } @else if (profile.needsOnboarding()) {
      <app-empty-state
        icon="sliders"
        heading="Set your keywords"
        body="Nothing can be scored until Jobx knows what you're looking for. Matching is OR-based — one keyword is enough to start, and extra keywords only add reach."
      >
        <button class="btn btn-primary" type="button" (click)="ui.openPreferences()">
          Set your keywords
        </button>
      </app-empty-state>
    } @else {
      <app-empty-state
        [heading]="EMPTY_TITLE[feed.status()]"
        body="Jobx checks every watched board on a 30-minute cycle. New roles land here as soon as they appear — and a role only appears if it clears your exclude words and hits at least one keyword."
      >
        <button class="btn btn-primary" type="button" (click)="ui.openPreferences()">
          Widen your keywords
        </button>
      </app-empty-state>
    }

    @if (selectedMatch(); as match) {
      <app-match-detail-drawer
        [match]="match"
        (closed)="closeDrawer()"
        (statusChange)="feed.setStatus(match.id, $event)"
        (openPosting)="onOpenPosting()"
      />
    }
  `,
})
export class MatchFeed {
  protected readonly feed = inject(FeedStore);
  protected readonly watchlist = inject(WatchlistStore);
  protected readonly profile = inject(FilterProfileStore);
  protected readonly ui = inject(UiStore);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly EMPTY_TITLE = EMPTY_TITLE;
  protected readonly skeletons = [0, 1, 2, 3];

  private readonly selectedId = signal<string | null>(null);

  /** Looked up from the live list, so status changes show up in the drawer. */
  protected readonly selectedMatch = computed(() => {
    const id = this.selectedId();
    return id ? (this.feed.matches().find((m) => m.id === id) ?? null) : null;
  });

  protected readonly title = computed(() => FEED_TITLE[this.feed.status()]);

  protected readonly subtitle = computed(() => {
    const count = this.feed.visible().length;
    const suffix = this.feed.isSearching() ? ' matching your search' : '';
    return `${count} role${count === 1 ? '' : 's'}${suffix}`;
  });

  /** Honest footer: last check time comes from real lastFetchedAt values. */
  protected readonly footer = computed(() => {
    const checked = this.watchlist
      .companies()
      .map((c) => c.lastFetchedAt)
      .filter((value): value is string => value !== null)
      .sort()
      .at(-1);
    if (!checked) return 'No board has been checked yet.';
    return `Boards last checked ${relTime(checked)} · Jobx re-checks every 30 minutes`;
  });

  protected readonly filteredEmptyTitle = computed(() =>
    this.feed.isSearching()
      ? `No matches for “${this.feed.query().trim()}”`
      : EMPTY_TITLE[this.feed.status()],
  );

  protected readonly filteredEmptyBody = computed(() =>
    this.feed.isSearching()
      ? 'Search looks at job title, company and matched keywords only — the API returns nothing else to search.'
      : 'Nothing in this view right now. Clear the filters to see the rest of your feed.',
  );

  /** ?page=N — the view survives a refresh and is shareable (uiux_plan.md §4). */
  private readonly pageParam = toSignal(
    this.route.queryParamMap.pipe(map((params) => Number(params.get('page')) || 1)),
    { initialValue: 1 },
  );

  constructor() {
    // URL -> store.
    effect(() => {
      const fromUrl = this.pageParam();
      if (fromUrl !== untracked(() => this.feed.page())) {
        this.feed.setPage(fromUrl);
      }
    });

    // store -> URL. Also covers the clamp: dismissing the last card on page 4
    // moves the store to page 3, and the address bar follows.
    effect(() => {
      const page = this.feed.page();
      if (page !== untracked(() => this.pageParam())) {
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { page: page === 1 ? null : page },
          queryParamsHandling: 'merge',
          replaceUrl: true,
        });
      }
    });
  }

  protected openDrawer(id: string): void {
    this.selectedId.set(id);
  }

  protected closeDrawer(): void {
    this.selectedId.set(null);
  }

  protected onOpenPosting(): void {
    this.toasts.show('Opening the company posting — Jobx never applies for you');
  }
}
