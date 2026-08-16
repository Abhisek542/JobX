import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatchResponse, MatchStatus } from '../../core/models/match.model';
import { Icon } from '../ui/icon';

/**
 * The Save / Mark applied / Dismiss / View details row.
 *
 * "Save" maps to SEEN because the backend has no SAVED status
 * (frontend_constraints.md §1). Each button toggles, so a mistaken click is one
 * click to undo — matching the mockup's Restore/Applied states.
 */
@Component({
  selector: 'app-match-actions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="card-actions">
      <button
        type="button"
        class="act seen"
        [class.on]="isSaved()"
        [disabled]="pending()"
        [attr.aria-pressed]="isSaved()"
        (click)="statusChange.emit(isSaved() ? 'NEW' : 'SEEN')"
      >
        <app-icon name="bookmark" size="sm" />
        {{ isSaved() ? 'Saved' : 'Save' }}
      </button>

      <button
        type="button"
        class="act applied"
        [class.on]="isApplied()"
        [disabled]="pending()"
        [attr.aria-pressed]="isApplied()"
        (click)="statusChange.emit(isApplied() ? 'SEEN' : 'APPLIED')"
      >
        <app-icon name="check-circle" size="sm" />
        {{ isApplied() ? 'Applied' : 'Mark applied' }}
      </button>

      <button
        type="button"
        class="act dismiss"
        [disabled]="pending()"
        (click)="statusChange.emit(isDismissed() ? 'NEW' : 'DISMISSED')"
      >
        <app-icon name="x-circle" size="sm" />
        {{ isDismissed() ? 'Restore' : 'Dismiss' }}
      </button>

      <span class="act-spacer"></span>

      <button type="button" class="act details" (click)="openDetails.emit()">
        View details
        <app-icon name="chevron-right" size="xs" />
      </button>
    </div>
  `,
})
export class MatchActions {
  readonly match = input.required<MatchResponse>();
  readonly pending = input(false);

  readonly statusChange = output<MatchStatus>();
  readonly openDetails = output<void>();

  protected readonly isSaved = computed(() => this.match().status === 'SEEN');
  protected readonly isApplied = computed(() => this.match().status === 'APPLIED');
  protected readonly isDismissed = computed(() => this.match().status === 'DISMISSED');
}
