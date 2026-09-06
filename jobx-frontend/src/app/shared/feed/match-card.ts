import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatchResponse, MatchStatus } from '../../core/models/match.model';
import { relTime } from '../../core/util/time';
import { band } from '../../features/dashboard/feed-logic';
import { CompanyLogo } from '../ui/company-logo';
import { Icon } from '../ui/icon';
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
 *
 * `compact` renders the archive row used by the Dismissed view: same data, one
 * line, one action. A dismissed role is history you might reverse — it earns a
 * row, not a full card — and saying that through layout beats saying it by
 * fading a full card, which only makes the Restore button hard to read.
 */
@Component({
  selector: 'app-match-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CompanyLogo, Icon, MatchActions, ScoreRing],
  template: `
    @if (compact()) {
      <article
        class="card is-compact"
        [class.is-dismissed]="isDismissed()"
        [class.is-pending]="pending()"
        [style.animation-delay.ms]="delayMs()"
      >
        <app-company-logo [companyName]="match().companyName" [size]="30" />

        <div class="row-main">
          <button type="button" class="job-title" (click)="openDetails.emit()">
            {{ match().jobTitle }}
          </button>
          <div class="meta row-meta">
            <span>{{ match().companyName }}</span>
            <span class="dot"></span>
            <span>{{ scorePct() }}% match</span>
            @if (expired()) {
              <!-- The row has no space for the closing date the full card
                   shows; the fact that the posting is gone is the part that
                   changes what the user can do about it. -->
              <span class="dot"></span>
              <span class="expired">No longer listed</span>
            }
          </div>
        </div>

        <button
          type="button"
          class="act restore"
          [disabled]="pending()"
          (click)="statusChange.emit('NEW')"
        >
          <app-icon name="undo" size="sm" />
          Restore
        </button>
      </article>
    } @else {
      <article
        class="card"
        [class.is-new]="match().status === 'NEW'"
        [class.is-dismissed]="isDismissed()"
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
              @if (expired(); as gone) {
                <!-- Jobx stopped tracking this posting after six days. It is kept
                     only because you saved or applied to it, and the apply link
                     very likely 404s now — say so rather than letting a dead
                     listing sit in the feed looking live (uiux_plan.md §7). -->
                <span class="dot"></span>
                <span class="expired">No longer listed · closed {{ gone }}</span>
              }
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
            <app-score-ring [value]="match().score" [color]="ringColor()" />
            <div class="match-label">
              <div class="lvl" [style.color]="ringColor()">{{ scoreBand().label }}</div>
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
    }
  `,
})
export class MatchCard {
  readonly match = input.required<MatchResponse>();
  readonly pending = input(false);
  /** Index within the page, for the mockup's staggered rise animation. */
  readonly index = input(0);
  /** Render the one-line archive row instead of the full card. */
  readonly compact = input(false);

  readonly statusChange = output<MatchStatus>();
  readonly openDetails = output<void>();

  protected readonly isDismissed = computed(() => this.match().status === 'DISMISSED');
  protected readonly scoreBand = computed(() => band(this.match().score));
  /**
   * A dismissed card keeps every pixel legible but drops the band colour: a
   * green "Strong match" ring on a role you've rejected is a claim the card is
   * no longer making. The label text still reads honestly, just in a neutral
   * tone.
   */
  protected readonly ringColor = computed(() =>
    this.isDismissed() ? 'var(--faint)' : this.scoreBand().color,
  );
  protected readonly scorePct = computed(() => Math.round(this.match().score));
  protected readonly tag = computed(() => STATUS_TAG[this.match().status]);
  protected readonly found = computed(() => relTime(this.match().createdAt));
  /** Set once the backend's six-day TTL has swept the posting away. */
  protected readonly expired = computed(() => {
    const expiredAt = this.match().expiredAt;
    return expiredAt ? relTime(expiredAt) : null;
  });
  protected readonly shownKeywords = computed(() => this.match().matchedKeywords.slice(0, 3));
  protected readonly extraKeywords = computed(
    () => this.match().matchedKeywords.length - this.shownKeywords().length,
  );
  protected readonly delayMs = computed(() => Math.min(this.index(), 8) * 28);
}
