import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { PageItem } from '../../features/dashboard/feed-logic';
import { Icon } from '../ui/icon';

/**
 * Numbered pagination, 10/page (change #3, uiux_plan.md §4).
 *
 * Client-side over the already-loaded array — `GET /matches` takes no page
 * parameters, so nothing here may imply the server paginates. The wording
 * ("Showing 1–10 of 43") describes the loaded feed, not a server window.
 */
@Component({
  selector: 'app-pagination',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <nav class="pagination" aria-label="Match feed pages">
      <span class="range">Showing {{ range().from }}–{{ range().to }} of {{ range().total }}</span>

      <div class="pages">
        <button
          type="button"
          class="page-btn"
          [disabled]="page() <= 1"
          (click)="pageChange.emit(page() - 1)"
        >
          <app-icon name="chevron-left" size="xs" />
          Prev
        </button>

        @for (item of items(); track $index) {
          @if (item === 'gap') {
            <span class="page-gap" aria-hidden="true">…</span>
          } @else {
            <button
              type="button"
              class="page-btn"
              [attr.aria-current]="item === page() ? 'page' : null"
              [attr.aria-label]="'Page ' + item"
              (click)="pageChange.emit($any(item))"
            >
              {{ item }}
            </button>
          }
        }

        <button
          type="button"
          class="page-btn"
          [disabled]="page() >= totalPages()"
          (click)="pageChange.emit(page() + 1)"
        >
          Next
          <app-icon name="chevron-right" size="xs" />
        </button>
      </div>
    </nav>
  `,
})
export class Pagination {
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly items = input.required<PageItem[]>();
  readonly range = input.required<{ from: number; to: number; total: number }>();

  readonly pageChange = output<number>();

  protected readonly isFirst = computed(() => this.page() <= 1);
}
