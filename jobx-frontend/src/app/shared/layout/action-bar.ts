import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { AuthStore } from '../../core/services/auth.store';
import { ThemeService } from '../../core/services/theme.service';
import { UiStore } from '../../core/services/ui.store';
import { FeedStore } from '../../features/dashboard/feed.store';
import { Icon } from '../ui/icon';

/**
 * The top bar (docs/newui-dark-redesign-mockup.html). Still no greeting
 * (uiux_plan.md §0 change #2): feed search on the left — only on the feed
 * routes, via `search`, since it filters the feed and nothing else — then the
 * theme toggle, "Add company" and the signed-in account. The optional page
 * title renders as a heading block below the bar (the dashboard has none).
 *
 * The search box is the same control the feed toolbar used to carry, bound to
 * the same FeedStore query, so filtering, page reset and the clear button
 * behave exactly as before.
 */
@Component({
  selector: 'app-action-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="action-bar">
      @if (search()) {
        <div class="top-search">
          <app-icon name="search" />
          <input
            type="text"
            autocomplete="off"
            aria-label="Search matches by title, company or keyword"
            placeholder="Search matches by title, company or keyword"
            [value]="feed.query()"
            (input)="feed.setQuery($any($event.target).value)"
          />
          @if (feed.query()) {
            <button class="clear" type="button" aria-label="Clear search" (click)="feed.setQuery('')">
              <app-icon name="x" size="xs" />
            </button>
          }
        </div>
      }

      <span class="spacer"></span>

      <button
        class="btn btn-ghost btn-icon"
        type="button"
        [attr.aria-label]="theme.isDark() ? 'Switch to light theme' : 'Switch to dark theme'"
        [title]="theme.isDark() ? 'Switch to light theme' : 'Switch to dark theme'"
        (click)="theme.toggle()"
      >
        <app-icon [name]="theme.isDark() ? 'sun' : 'moon'" />
      </button>

      <button class="btn btn-primary" type="button" (click)="ui.openAddCompany()">
        <app-icon name="plus" size="sm" />
        <span class="add-label">Add company</span>
      </button>

      <div class="top-me">
        <span class="avatar" aria-hidden="true">{{ auth.initials() }}</span>
        <span class="who">
          <span class="nm">{{ auth.name() }}</span>
          <span class="em">{{ auth.email() }}</span>
        </span>
      </div>
    </div>

    @if (title()) {
      <div class="page-head">
        <h1 class="page-title">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="sub">{{ subtitle() }}</p>
        }
      </div>
    }
  `,
})
export class ActionBar {
  protected readonly theme = inject(ThemeService);
  protected readonly ui = inject(UiStore);
  protected readonly auth = inject(AuthStore);
  protected readonly feed = inject(FeedStore);

  readonly title = input('');
  readonly subtitle = input('');
  /** Show the feed search box. Only the feed routes (dashboard, matches) set it. */
  readonly search = input(false);
}
