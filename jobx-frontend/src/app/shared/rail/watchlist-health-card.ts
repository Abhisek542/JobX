import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { WatchedCompanyResponse } from '../../core/models/watchlist.model';
import { companyStatusLine } from '../../core/util/watchlist-status';
import { ScoreRing } from '../feed/score-ring';
import { Icon } from '../ui/icon';

/**
 * Rail panel 2 of 2 (change #1, uiux_plan.md §0).
 *
 * Health is concrete counts computed client-side from real `lastFetchStatus`
 * values — "3 checking fine · 1 refresh issue · 1 awaiting first check" — never
 * a fake percentage (uiux_plan.md §7). The ring is a healthy/active fraction and
 * shows that fraction as text, not a percent.
 */
@Component({
  selector: 'app-watchlist-health-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, RouterLink, ScoreRing],
  template: `
    <section class="panel">
      <div class="panel-head">
        <h3>Watchlist health</h3>
        <a class="link" routerLink="/watchlist">View all</a>
      </div>

      <div class="panel-body">
        @if (store.isEmpty()) {
          <p class="panel-empty">
            You're not watching any boards yet. Jobx only sees companies you add — each one is a
            feed you read before the aggregators do.
          </p>
          <button class="btn btn-outline btn-sm" type="button" (click)="addCompany.emit()">
            <app-icon name="plus" size="xs" />
            Add your first company
          </button>
        } @else {
          <div class="health-top">
            <app-score-ring
              [value]="health().ratio * 100"
              [size]="64"
              [color]="health().needsAttention ? 'var(--warn)' : 'var(--good)'"
              [text]="health().healthy + '/' + health().active"
            />
            <div class="health-text">
              <div
                class="st"
                [style.color]="health().needsAttention ? 'var(--warn)' : 'var(--good)'"
              >
                {{ health().headline }}
              </div>
              <div class="dt">{{ health().detail }}</div>
            </div>
          </div>

          @for (company of rows(); track company.id) {
            <div class="company-row">
              <span class="status-dot" [class]="'status-dot ' + line(company).dot"></span>
              <span class="cn">
                <b>{{ company.companyName }}</b>
                <i [class.bad]="line(company).bad">{{ line(company).text }}</i>
              </span>
              @if (line(company).canCheck) {
                <button
                  class="check-btn"
                  type="button"
                  [disabled]="store.isChecking(company.id)"
                  (click)="store.checkNow(company)"
                >
                  {{ store.isChecking(company.id) ? 'Checking…' : 'Check now' }}
                </button>
              }
            </div>
          }
        }
      </div>

      @if (!store.isEmpty()) {
        <a class="panel-foot" routerLink="/watchlist">
          Manage watchlist
          <app-icon name="chevron-right" size="xs" />
        </a>
      }
    </section>
  `,
})
export class WatchlistHealthCard {
  protected readonly store = inject(WatchlistStore);
  readonly addCompany = output<void>();

  protected readonly health = this.store.health;
  protected readonly rows = computed(() => this.store.ordered());

  protected line(company: WatchedCompanyResponse) {
    return companyStatusLine(company);
  }
}
