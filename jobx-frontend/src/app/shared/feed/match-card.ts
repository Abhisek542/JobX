import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatchResponse, MatchStatus } from '../../core/models/match.model';
import { relTime } from '../../core/util/time';
import { band } from '../../features/dashboard/feed-logic';
import { CompanyLogo } from '../ui/company-logo';
import { MatchActions } from './match-actions';
import { ScoreRing } from './score-ring';

const STATUS_TAG: Record<MatchStatus, { cls: string; text: string } | null> = {
  NEW: { cls: 'tag-new', text: 'New' },
  APPLIED: { cls: 'tag-applied', text: 'Applied' },
  DISMISSED: { cls: 'tag-dismissed', text: 'Dismissed' },
  SEEN: null,
};

/**
 * One feed card. Card anatomy is the mockup's spec.
 *
 * HONESTY (uiux_plan.md §7): no location, no description excerpt, no employer
 * posting date, no verified badge — `MatchResponse` carries none of them. The
 * meta line says "Found {relTime(createdAt)}", which is when *Jobx* first saw
 * the role, and is never labelled as the employer's posting date.
 */
@Component({
  selector: 'app-match-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CompanyLogo, MatchActions, ScoreRing],
  template: `
    <article
      class="card"
      [class.is-new]="match().status === 'NEW'"
      [class.is-dismissed]="match().status === 'DISMISSED'"
      [class.is-applied]="match().status === 'APPLIED'"
      [class.is-pending]="pending()"
      [style.animation-delay.ms]="delayMs()"
    >
      <div class="card-top">
        <app-company-logo [companyName]="match().companyName" />

        <div class="card-info">
          <div class="title-row">
            <button type="button" class="job-title" (click)="openDetails.emit()">
              {{ match().jobTitle }}
            </button>
            @if (tag(); as t) {
              <span class="tag" [class]="'tag ' + t.cls">{{ t.text }}</span>
            }
          </div>

          <div class="meta">
            <span>{{ match().companyName }}</span>
            <span class="dot"></span>
            <span>Found {{ found() }}</span>
          </div>

          <div class="skills">
            @for (keyword of shownKeywords(); track keyword) {
              <span class="skill">{{ keyword }}</span>
            }
            @if (extraKeywords() > 0) {
              <span class="skill more">+{{ extraKeywords() }} more</span>
            }
          </div>
        </div>

        <div class="match">
          <app-score-ring [value]="match().score" [color]="scoreBand().color" />
          <div class="match-label">
            <div class="lvl" [style.color]="scoreBand().color">{{ scoreBand().label }}</div>
            <div class="why">{{ scoreBand().why }}</div>
          </div>
        </div>
      </div>

      <app-match-actions
        [match]="match()"
        [pending]="pending()"
        (statusChange)="statusChange.emit($event)"
        (openDetails)="openDetails.emit()"
      />
    </article>
  `,
})
export class MatchCard {
  readonly match = input.required<MatchResponse>();
  readonly pending = input(false);
  /** Index within the page, for the mockup's staggered rise animation. */
  readonly index = input(0);

  readonly statusChange = output<MatchStatus>();
  readonly openDetails = output<void>();

  protected readonly scoreBand = computed(() => band(this.match().score));
  protected readonly tag = computed(() => STATUS_TAG[this.match().status]);
  protected readonly found = computed(() => relTime(this.match().createdAt));
  protected readonly shownKeywords = computed(() => this.match().matchedKeywords.slice(0, 3));
  protected readonly extraKeywords = computed(
    () => this.match().matchedKeywords.length - this.shownKeywords().length,
  );
  protected readonly delayMs = computed(() => Math.min(this.index(), 8) * 28);
}
