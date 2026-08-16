import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthStore } from '../../core/services/auth.store';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { FeedStore } from '../../features/dashboard/feed.store';
import { Icon } from '../ui/icon';

/**
 * Left sidebar. Per uiux_plan.md §3 the wordmark is "Jobx" — dark text, blue x —
 * on the white --panel surface, NOT the guide's lowercase navy treatment.
 */
@Component({
  selector: 'app-sidebar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, RouterLink, RouterLinkActive],
  template: `
    <aside class="sidebar" [class.open]="mobileOpen()">
      <div class="brand">
        <span class="brand-mark">j</span><span>Job<span class="x">x</span></span>
      </div>

      <nav class="nav">
        <a
          class="nav-item"
          routerLink="/dashboard"
          routerLinkActive="active"
          (click)="navigated.emit()"
        >
          <app-icon name="home" />
          <span>Dashboard</span>
        </a>
        <a
          class="nav-item"
          routerLink="/matches"
          routerLinkActive="active"
          (click)="navigated.emit()"
        >
          <app-icon name="briefcase" />
          <span>Matches</span>
          @if (newCount() > 0) {
            <span class="count">{{ newCount() }}</span>
          }
        </a>
        <a
          class="nav-item"
          routerLink="/watchlist"
          routerLinkActive="active"
          (click)="navigated.emit()"
        >
          <app-icon name="star" />
          <span>Watchlist</span>
        </a>
        <a
          class="nav-item"
          routerLink="/profile"
          routerLinkActive="active"
          (click)="navigated.emit()"
        >
          <app-icon name="user" />
          <span>Profile</span>
        </a>
      </nav>

      <div class="side-foot">
        <div class="upsell">
          <h4>{{ boardsLabel() }}</h4>
          <p>More boards means more first-look roles before they reach the aggregators.</p>
          <button class="btn btn-outline btn-sm" type="button" (click)="addCompany.emit()">
            <app-icon name="plus" size="xs" />
            Add company
          </button>
        </div>

        <button class="collapse-btn" type="button" (click)="toggleCollapse.emit()">
          <app-icon name="chevron-left" />
          <span>{{ collapsed() ? 'Expand' : 'Collapse' }}</span>
        </button>

        <div class="account">
          <span class="avatar">{{ auth.initials() }}</span>
          <span class="account-id">
            <span class="nm">{{ auth.name() }}</span>
            <span class="em">{{ auth.email() }}</span>
          </span>
          <button
            class="x-btn"
            type="button"
            title="Sign out"
            aria-label="Sign out"
            (click)="signOut.emit()"
          >
            <app-icon name="log-out" size="sm" />
          </button>
        </div>
      </div>
    </aside>
  `,
})
export class Sidebar {
  protected readonly auth = inject(AuthStore);
  private readonly feed = inject(FeedStore);
  private readonly watchlist = inject(WatchlistStore);

  readonly collapsed = input(false);
  readonly mobileOpen = input(false);

  readonly toggleCollapse = output<void>();
  readonly addCompany = output<void>();
  readonly signOut = output<void>();
  readonly navigated = output<void>();

  protected readonly newCount = computed(() => this.feed.statusCounts().NEW);
  protected readonly boardsLabel = computed(() => {
    const count = this.watchlist.companies().length;
    if (count === 0) return 'No boards watched yet';
    return `Watching ${count} board${count > 1 ? 's' : ''}`;
  });
}
