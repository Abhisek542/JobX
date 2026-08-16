import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { AuthStore } from '../../core/services/auth.store';
import { FilterProfileStore } from '../../core/services/filter-profile.store';
import { UiStore } from '../../core/services/ui.store';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { absoluteTime } from '../../core/util/time';
import { ActionBar } from '../../shared/layout/action-bar';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';

/**
 * Profile = account identity + the filter profile, stated as the rules they
 * really are.
 *
 * The account block is deliberately thin: `AuthResponse` carries an email and
 * nothing else — no display name, no role (frontend_constraints.md §5, §15) —
 * so the name shown here is openly labelled as derived from the email.
 */
@Component({
  selector: 'app-profile-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ActionBar, EmptyState, Icon],
  template: `
    <main class="main">
      <app-action-bar title="Profile" subtitle="Your account, and the rules Jobx matches with." />

      <section class="page-section">
        <h2>Account</h2>
        <p class="lede">Signed in with a 7-day token. Signing out clears it from this device.</p>
        <div class="kv"><span>Email</span><b>{{ auth.email() }}</b></div>
        <div class="kv">
          <span>Display name</span><b>{{ auth.name() }} · derived from your email</b>
        </div>
        <div class="kv"><span>User ID</span><b class="mono">{{ auth.userId() }}</b></div>
      </section>

      <section class="page-section">
        <h2>Search preferences</h2>
        <p class="lede">
          These three rules are the entire matching engine. Keyword hits in the title count double;
          experience only moves the score.
        </p>

        @if (profile.needsOnboarding()) {
          <app-empty-state
            icon="sliders"
            heading="No keywords set yet"
            body="Jobx can't score anything until you give it at least one keyword. Matching is OR-based, so one is enough to start."
          >
            <button class="btn btn-primary" type="button" (click)="ui.openPreferences()">
              Set your keywords
            </button>
          </app-empty-state>
        } @else {
          <div class="kv" style="border-top:0">
            <span>Keywords · any one can match</span>
            <b>{{ profile.keywords().length }}</b>
          </div>
          <div class="kw-list" style="margin:10px 0 16px">
            @for (keyword of profile.keywords(); track keyword) {
              <span class="skill">{{ keyword }}</span>
            }
          </div>

          <div class="kv" style="border-top:0">
            <span>Exclude words · drop the role entirely</span>
            <b>{{ profile.excludeWords().length }}</b>
          </div>
          <div class="kw-list" style="margin:10px 0 16px">
            @if (profile.excludeWords().length) {
              @for (word of profile.excludeWords(); track word) {
                <span class="skill exclude">{{ word }}</span>
              }
            } @else {
              <span class="pill-static">None</span>
            }
          </div>

          <div class="kv"><span>Experience</span><b>{{ profile.experienceLabel() }}</b></div>
          <div class="kv"><span>Last updated</span><b>{{ updatedAt() }}</b></div>

          <div style="display:flex;gap:10px;margin-top:16px">
            <button class="btn btn-primary" type="button" (click)="ui.openPreferences()">
              Edit preferences
            </button>
          </div>
        }
      </section>

      <section class="page-section">
        <h2>What Jobx is watching</h2>
        <p class="lede">{{ boardsLine() }}</p>
        <div class="kw-list">
          @for (company of watchlist.ordered(); track company.id) {
            <span class="pill-static">{{ company.companyName }}</span>
          }
        </div>
      </section>

      <div class="note">
        <app-icon name="info" size="sm" />
        <div>
          Jobx never applies on your behalf and never invents a skill you don't have. Scores come
          from keyword overlap and experience distance only — nothing hidden, no model in the loop.
        </div>
      </div>
    </main>
  `,
})
export class ProfilePage {
  protected readonly auth = inject(AuthStore);
  protected readonly profile = inject(FilterProfileStore);
  protected readonly watchlist = inject(WatchlistStore);
  protected readonly ui = inject(UiStore);

  protected readonly updatedAt = computed(() => absoluteTime(this.profile.profile()?.updatedAt));

  protected readonly boardsLine = computed(() => {
    const count = this.watchlist.companies().length;
    if (count === 0) return "No boards yet — Jobx only sees companies you've added.";
    return `${count} board${count > 1 ? 's' : ''} on your watchlist · ${
      this.watchlist.health().healthy
    } checking fine.`;
  });
}
