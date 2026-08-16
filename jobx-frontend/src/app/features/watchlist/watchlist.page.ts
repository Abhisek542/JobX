import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { WatchedCompanyResponse, PLATFORM_LABEL } from '../../core/models/watchlist.model';
import { UiStore } from '../../core/services/ui.store';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { companyStatusLine } from '../../core/util/watchlist-status';
import { ActionBar } from '../../shared/layout/action-bar';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';

/**
 * Watchlist management — the full version of the rail's health panel.
 *
 * Every state from uiux_plan.md §8 is reachable here: never checked, refresh
 * issue, paused, unsupported board. "Check now" is only offered where the
 * backend would accept it (ACTIVE only — anything else is a 409).
 */
@Component({
  selector: 'app-watchlist-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ActionBar, EmptyState, Icon],
  template: `
    <main class="main">
      <app-action-bar
        title="Watchlist"
        subtitle="Jobx reads each company's own ATS board directly — no scraping, no aggregators."
      />

      @if (store.error(); as error) {
        <app-empty-state icon="alert" tone="bad" heading="Couldn't load your watchlist" [body]="error.detail">
          <button class="btn btn-primary" type="button" (click)="store.load({ force: true })">
            Try again
          </button>
        </app-empty-state>
      } @else if (store.isEmpty()) {
        <app-empty-state
          icon="star"
          heading="Add your first company"
          body="Nothing is watched yet. Add a company and Jobx polls its careers board every 30 minutes, scoring each new posting against your keywords."
        >
          <button class="btn btn-primary" type="button" (click)="ui.openAddCompany()">
            Add company
          </button>
        </app-empty-state>
      } @else {
        <div class="stat-strip">
          <div class="stat good">
            <div class="v">{{ health().healthy }}</div>
            <div class="l">checking fine</div>
          </div>
          <div class="stat" [class.bad]="health().failing > 0">
            <div class="v">{{ health().failing }}</div>
            <div class="l">refresh issue</div>
          </div>
          <div class="stat" [class.warn]="health().pending > 0">
            <div class="v">{{ health().pending }}</div>
            <div class="l">awaiting first check</div>
          </div>
          <div class="stat">
            <div class="v">{{ health().paused + health().unsupported }}</div>
            <div class="l">paused or unsupported</div>
          </div>
        </div>

        <section class="page-section">
          <h2>Watched boards</h2>
          <p class="lede">
            Counts above are computed here from each board's real last-check status — the API
            returns no health value of its own.
          </p>

          <div class="wl-list">
            @for (company of companies(); track company.id) {
              <div class="wl-row">
                <div class="wl-id">
                  <div class="nm">
                    <span class="status-dot" [class]="'status-dot ' + line(company).dot"></span>
                    {{ company.companyName }}
                    @if (company.status === 'PAUSED') {
                      <span class="tag tag-seen">Paused</span>
                    }
                    @if (company.status === 'UNSUPPORTED') {
                      <span class="tag tag-dismissed">Unsupported</span>
                    }
                  </div>
                  <div class="sub">
                    <span>{{ platform(company) }}</span>
                    <code>{{ company.boardToken }}</code>
                    <span class="dot"></span>
                    <span [class.bad]="line(company).bad">{{ line(company).text }}</span>
                  </div>
                </div>

                @if (confirmingId() === company.id) {
                  <div class="wl-confirm">
                    <span>Remove {{ company.companyName }} and its matches?</span>
                    <button class="btn btn-ghost btn-sm" type="button" (click)="confirmingId.set(null)">
                      Cancel
                    </button>
                    <button class="btn btn-danger btn-sm" type="button" (click)="remove(company)">
                      Remove
                    </button>
                  </div>
                } @else {
                  <div class="wl-actions">
                    @if (line(company).canCheck) {
                      <button
                        class="btn btn-ghost btn-sm"
                        type="button"
                        [disabled]="store.isChecking(company.id)"
                        (click)="store.checkNow(company)"
                      >
                        <app-icon name="refresh" size="xs" />
                        {{ store.isChecking(company.id) ? 'Checking…' : 'Check now' }}
                      </button>
                    }
                    @if (company.status === 'ACTIVE') {
                      <button
                        class="btn btn-ghost btn-sm"
                        type="button"
                        (click)="store.updateStatus(company.id, 'PAUSED')"
                      >
                        <app-icon name="pause" size="xs" />
                        Pause
                      </button>
                    }
                    @if (company.status === 'PAUSED') {
                      <button
                        class="btn btn-ghost btn-sm"
                        type="button"
                        (click)="store.updateStatus(company.id, 'ACTIVE')"
                      >
                        <app-icon name="play" size="xs" />
                        Resume
                      </button>
                    }
                    <button
                      class="btn btn-ghost btn-sm"
                      type="button"
                      aria-label="Remove company"
                      (click)="confirmingId.set(company.id)"
                    >
                      <app-icon name="trash" size="xs" />
                    </button>
                  </div>
                }
              </div>
            }
          </div>
        </section>

        <div class="note">
          <app-icon name="info" size="sm" />
          <div>
            A board marked <b>Refresh issue</b> means the last attempt failed — Jobx keeps retrying
            on the normal cycle. Boards on Workday, Rippling or BambooHR have no public API, so
            Jobx marks them <b>unsupported</b> instead of pretending to watch them.
          </div>
        </div>
      }
    </main>
  `,
})
export class WatchlistPage {
  protected readonly store = inject(WatchlistStore);
  protected readonly ui = inject(UiStore);

  protected readonly confirmingId = signal<string | null>(null);
  protected readonly health = this.store.health;
  protected readonly companies = computed(() => this.store.ordered());

  protected line(company: WatchedCompanyResponse) {
    return companyStatusLine(company);
  }

  protected platform(company: WatchedCompanyResponse): string {
    return PLATFORM_LABEL[company.atsPlatform];
  }

  protected remove(company: WatchedCompanyResponse): void {
    this.confirmingId.set(null);
    this.store.remove(company);
  }
}
