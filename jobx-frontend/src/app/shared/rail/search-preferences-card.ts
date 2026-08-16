import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { FilterProfileStore } from '../../core/services/filter-profile.store';
import { Icon } from '../ui/icon';

/**
 * Rail panel 1 of 2 (change #1, uiux_plan.md §0).
 *
 * Shows the three rules that ARE the matching engine, with the same wording the
 * mockup used: keywords are OR, exclude words drop the role entirely,
 * experience only nudges the score. There is no "Roles" row — the API has no
 * such field (frontend_constraints.md §9).
 */
@Component({
  selector: 'app-search-preferences-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <section class="panel">
      <div class="panel-head">
        <h3>Search preferences</h3>
        <button class="link" type="button" (click)="edit.emit()">Edit</button>
      </div>

      <div class="panel-body">
        @if (store.needsOnboarding()) {
          <p class="panel-empty">
            No keywords yet — Jobx can't score anything until you set at least one.
          </p>
          <button class="btn btn-outline btn-sm" type="button" (click)="edit.emit()">
            <app-icon name="plus" size="xs" />
            Set your keywords
          </button>
        } @else {
          <button class="pref" type="button" (click)="edit.emit()">
            <app-icon name="tag" size="sm" />
            <span class="pref-text">
              <span class="lb">Keywords · any one can match</span>
              <span class="vl">{{ keywords() }}</span>
            </span>
            <app-icon name="chevron-right" size="xs" />
          </button>

          <button class="pref" type="button" (click)="edit.emit()">
            <app-icon name="x-circle" size="sm" />
            <span class="pref-text">
              <span class="lb">Exclude words · removes the role entirely</span>
              <span class="vl exclude">{{ excludeWords() }}</span>
            </span>
            <app-icon name="chevron-right" size="xs" />
          </button>

          <button class="pref" type="button" (click)="edit.emit()">
            <app-icon name="clock" size="sm" />
            <span class="pref-text">
              <span class="lb">Experience · nudges the score, never filters</span>
              <span class="vl">{{ store.experienceLabel() }}</span>
            </span>
            <app-icon name="chevron-right" size="xs" />
          </button>
        }
      </div>
    </section>
  `,
})
export class SearchPreferencesCard {
  protected readonly store = inject(FilterProfileStore);
  readonly edit = output<void>();

  protected readonly keywords = computed(() =>
    this.store.keywords().length ? this.store.keywords().join(', ') : 'No keywords set',
  );
  protected readonly excludeWords = computed(() =>
    this.store.excludeWords().length ? this.store.excludeWords().join(', ') : 'None',
  );
}
