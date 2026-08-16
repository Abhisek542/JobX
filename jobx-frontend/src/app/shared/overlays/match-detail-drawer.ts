import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatchResponse, MatchStatus } from '../../core/models/match.model';
import { absoluteTime, relTime } from '../../core/util/time';
import { band } from '../../features/dashboard/feed-logic';
import { CompanyLogo } from '../ui/company-logo';
import { Icon } from '../ui/icon';
import { OverlayShell } from './overlay-shell';

const STATUS_LABEL: Record<MatchStatus, string> = {
  NEW: 'New',
  SEEN: 'Saved',
  APPLIED: 'Applied',
  DISMISSED: 'Dismissed',
};

/**
 * "View details" (uiux_plan.md §7).
 *
 * Built ONLY from MatchResponse fields plus the real applyUrl, and it says so:
 * there is no GET /matches/{id}, and the list DTO carries no description,
 * location or employer posting date. Opening this drawer does NOT mutate status
 * — SEEN means "the user chose to keep this" (uiux_plan.md §3).
 */
@Component({
  selector: 'app-match-detail-drawer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CompanyLogo, Icon, OverlayShell],
  template: `
    <app-overlay variant="drawer" [label]="match().jobTitle" (closed)="closed.emit()">
      <div class="drawer-head">
        <app-company-logo [companyName]="match().companyName" [size]="46" />
        <div style="flex:1;min-width:0">
          <h3>{{ match().jobTitle }}</h3>
          <div class="company">{{ match().companyName }}</div>
        </div>
        <button class="x-btn" type="button" aria-label="Close" (click)="closed.emit()">
          <app-icon name="x" />
        </button>
      </div>

      <div class="drawer-body">
        <div class="dsec">
          <h4>Match score</h4>
          <div class="score-line">
            <span class="n">{{ match().score }}%</span>
            <span class="b" [style.color]="scoreBand().color">{{ scoreBand().label }}</span>
          </div>
          <div class="score-bar">
            <i [style.width.%]="match().score" [style.background]="scoreBand().color"></i>
          </div>
          <div class="fine">
            Up to 70 points from keyword overlap, up to 30 from how close the experience range sits
            to yours.
          </div>
        </div>

        <div class="dsec">
          <h4>Matched keywords</h4>
          @if (match().matchedKeywords.length) {
            <div class="skills">
              @for (keyword of match().matchedKeywords; track keyword) {
                <span class="skill">{{ keyword }}</span>
              }
            </div>
          } @else {
            <div class="fine">No keywords recorded on this match.</div>
          }
        </div>

        <div class="dsec">
          <h4>Details</h4>
          <div class="kv"><span>Status</span><b>{{ statusLabel() }}</b></div>
          <div class="kv">
            <span>Found by Jobx</span><b [title]="absolute()">{{ found() }}</b>
          </div>
          <div class="kv"><span>Match ID</span><b class="mono">{{ match().id }}</b></div>
        </div>

        <div class="note">
          <app-icon name="info" size="sm" />
          <div>
            Full description, location and the employer's own posting date aren't shown here —
            <code>GET /matches</code> doesn't return them and there is no detail endpoint yet. The
            apply link opens the real posting.
          </div>
        </div>
      </div>

      <div class="drawer-foot">
        <button
          class="btn btn-ghost"
          type="button"
          style="flex:1"
          [disabled]="isApplied()"
          (click)="statusChange.emit('APPLIED')"
        >
          {{ isApplied() ? 'Applied ✓' : 'Mark applied' }}
        </button>
        <a
          class="btn btn-primary"
          style="flex:1;text-decoration:none"
          [href]="match().applyUrl"
          target="_blank"
          rel="noopener"
          (click)="openPosting.emit()"
        >
          Open posting
          <app-icon name="external-link" size="xs" />
        </a>
      </div>
    </app-overlay>
  `,
})
export class MatchDetailDrawer {
  readonly match = input.required<MatchResponse>();

  readonly closed = output<void>();
  readonly statusChange = output<MatchStatus>();
  readonly openPosting = output<void>();

  protected readonly scoreBand = computed(() => band(this.match().score));
  protected readonly statusLabel = computed(() => STATUS_LABEL[this.match().status]);
  protected readonly found = computed(() => relTime(this.match().createdAt));
  protected readonly absolute = computed(() => absoluteTime(this.match().createdAt));
  protected readonly isApplied = computed(() => this.match().status === 'APPLIED');
}
