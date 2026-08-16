import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { AuthStore } from '../../core/services/auth.store';
import { FilterProfileStore } from '../../core/services/filter-profile.store';
import { UiStore } from '../../core/services/ui.store';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { FeedStore } from '../../features/dashboard/feed.store';
import { AddCompanyModal } from '../overlays/add-company-modal';
import { FilterProfileModal } from '../overlays/filter-profile-modal';
import { Icon } from '../ui/icon';
import { ActionBar } from './action-bar';
import { Sidebar } from './sidebar';

/**
 * The authenticated shell: sidebar · routed content · (optional) rail.
 *
 * The routed page's host is transparent (styles/_layout.scss), so its <main> and
 * <aside class="rail"> become direct children of this grid — that is what keeps the three-column
 * layout working across routes. The rail column is `auto`-sized, so a route
 * that renders no rail simply has no third column.
 *
 * The two global overlays live here rather than in a page, because the sidebar,
 * the action bar and the rail panels all raise them.
 */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AddCompanyModal, FilterProfileModal, Icon, RouterOutlet, Sidebar],
  template: `
    <div class="app" [class.nav-collapsed]="ui.navCollapsed()">
      <app-sidebar
        [collapsed]="ui.navCollapsed()"
        [mobileOpen]="ui.mobileNavOpen()"
        (toggleCollapse)="ui.toggleNavCollapsed()"
        (addCompany)="ui.openAddCompany()"
        (signOut)="signOut()"
        (navigated)="ui.closeMobileNav()"
      />

      <div class="nav-scrim" [class.open]="ui.mobileNavOpen()" (click)="ui.closeMobileNav()"></div>

      <div class="mobile-bar">
        <button
          class="btn btn-ghost btn-icon"
          type="button"
          aria-label="Open navigation"
          (click)="ui.toggleMobileNav()"
        >
          <app-icon name="menu" />
        </button>
        <div class="brand"><span>Job<span class="x">x</span></span></div>
      </div>

      <router-outlet />
    </div>

    @if (ui.addCompanyOpen()) {
      <app-add-company-modal (closed)="ui.closeAddCompany()" />
    }
    @if (ui.preferencesOpen()) {
      <app-filter-profile-modal (closed)="ui.closePreferences()" (saved)="onPreferencesSaved()" />
    }
  `,
})
export class AppShell {
  protected readonly ui = inject(UiStore);
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly feed = inject(FeedStore);
  private readonly watchlist = inject(WatchlistStore);
  private readonly profile = inject(FilterProfileStore);

  constructor() {
    // One load per session; individual actions force-refresh when they change data.
    this.feed.load();
    this.watchlist.load();
    this.profile.load();
  }

  protected signOut(): void {
    this.auth.clear();
    void this.router.navigate(['/login']);
  }

  protected onPreferencesSaved(): void {
    // Scores are recomputed server-side on the next fetch cycle, so the existing
    // feed is unchanged — but the rail's preference panel is not.
    this.profile.load({ force: true });
  }
}
