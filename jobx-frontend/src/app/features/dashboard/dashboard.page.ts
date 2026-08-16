import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { UiStore } from '../../core/services/ui.store';
import { MatchFeed } from '../../shared/feed/match-feed';
import { ActionBar } from '../../shared/layout/action-bar';
import { SearchPreferencesCard } from '../../shared/rail/search-preferences-card';
import { WatchlistHealthCard } from '../../shared/rail/watchlist-health-card';

/**
 * The focused feed — the default route.
 *
 * <main> and <aside class="rail"> land directly in the shell's grid (the host is
 * transparent, see styles/_layout.scss). Two rail panels only (change #1); no greeting (change #2).
 */
@Component({
  selector: 'app-dashboard-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ActionBar, MatchFeed, SearchPreferencesCard, WatchlistHealthCard],
  template: `
    <main class="main">
      <app-action-bar />
      <app-match-feed />
    </main>

    <aside class="rail">
      <app-search-preferences-card (edit)="ui.openPreferences()" />
      <app-watchlist-health-card (addCompany)="ui.openAddCompany()" />
    </aside>
  `,
})
export class DashboardPage {
  protected readonly ui = inject(UiStore);
}
