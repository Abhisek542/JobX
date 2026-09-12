import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { GroupOrder, SortMode, StatusFilter } from '../../features/dashboard/feed-logic';
import { Icon } from '../ui/icon';

interface Pill {
  key: StatusFilter;
  label: string;
}

/** "Saved" is the SEEN status — the backend has no SAVED (frontend_constraints §1). */
const PILLS: Pill[] = [
  { key: 'ALL', label: 'All matches' },
  { key: 'NEW', label: 'New' },
  { key: 'SEEN', label: 'Saved' },
  { key: 'APPLIED', label: 'Applied' },
  { key: 'DISMISSED', label: 'Dismissed' },
];

@Component({
  selector: 'app-feed-toolbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="toolbar">
      <div class="search">
        <app-icon name="search" />
        <input
          type="text"
          autocomplete="off"
          aria-label="Search matches by title, company or keyword"
          placeholder="Search matches by title, company or keyword"
          [value]="query()"
          (input)="queryChange.emit($any($event.target).value)"
        />
        @if (query()) {
          <button class="clear" type="button" aria-label="Clear search" (click)="queryChange.emit('')">
            <app-icon name="x" size="xs" />
          </button>
        }
      </div>

      <div class="filter-row">
        <div class="pills" role="tablist" aria-label="Filter matches by status">
          @for (pill of pills; track pill.key) {
            <button
              type="button"
              role="tab"
              class="pill"
              [class.active]="status() === pill.key"
              [attr.aria-selected]="status() === pill.key"
              (click)="statusChange.emit(pill.key)"
            >
              {{ pill.label }}
              <span class="n">{{ counts()[pill.key] }}</span>
            </button>
          }
        </div>

        <!--
          Grouping is a view mode, not a status filter, so it sits outside the
          pill tablist and composes with whichever pill is active — including
          Dismissed, where the groups fill with archive rows.
        -->
        <div class="view-toggle" role="group" aria-label="Feed layout">
          <button
            type="button"
            [class.active]="!grouped()"
            [attr.aria-pressed]="!grouped()"
            (click)="groupedChange.emit(false)"
          >
            <app-icon name="list" size="xs" />
            List
          </button>
          <button
            type="button"
            [class.active]="grouped()"
            [attr.aria-pressed]="grouped()"
            (click)="groupedChange.emit(true)"
          >
            <app-icon name="layers" size="xs" />
            By company
          </button>
        </div>

        @if (grouped()) {
          <!--
            In grouped mode the dropdown orders the GROUPS. A-Z lives here and
            only here: alphabetical was useless for interleaving cards, but it
            is the natural way to find one company among a screen of headers.
          -->
          <div class="sort-wrap">
            <select
              class="sort"
              aria-label="Order companies"
              [value]="groupOrder()"
              (change)="groupOrderChange.emit($any($event.target).value)"
            >
              <option value="score">Companies by: Best match</option>
              <option value="newest">Companies by: Newest</option>
              <option value="name">Companies by: A–Z</option>
            </select>
            <app-icon name="chevron-down" size="sm" />
          </div>
        } @else {
          <div class="sort-wrap">
            <select
              class="sort"
              aria-label="Sort matches"
              [value]="sort()"
              (change)="sortChange.emit($any($event.target).value)"
            >
              <option value="score">Sort by: Best match</option>
              <option value="newest">Sort by: Newest</option>
            </select>
            <app-icon name="chevron-down" size="sm" />
          </div>
        }
      </div>
    </div>
  `,
})
export class FeedToolbar {
  readonly query = input('');
  readonly status = input<StatusFilter>('ALL');
  readonly sort = input<SortMode>('score');
  readonly grouped = input(false);
  readonly groupOrder = input<GroupOrder>('score');
  /** Counts describe the whole feed, never the current page (uiux_plan.md §4). */
  readonly counts = input.required<Record<StatusFilter, number>>();

  readonly queryChange = output<string>();
  readonly statusChange = output<StatusFilter>();
  readonly sortChange = output<SortMode>();
  readonly groupedChange = output<boolean>();
  readonly groupOrderChange = output<GroupOrder>();

  protected readonly pills = PILLS;
}
