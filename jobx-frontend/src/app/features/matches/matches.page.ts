import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatchFeed } from '../../shared/feed/match-feed';
import { ActionBar } from '../../shared/layout/action-bar';

/**
 * The same feed, full width, with a page heading and no rail. Shares FeedStore
 * with /dashboard, so filters, sort and page survive moving between them.
 */
@Component({
  selector: 'app-matches-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ActionBar, MatchFeed],
  template: `
    <main class="main">
      <app-action-bar
        title="Matches"
        subtitle="Every role Jobx has scored for you, newest checks included."
      />
      <app-match-feed />
    </main>
  `,
})
export class MatchesPage {}
