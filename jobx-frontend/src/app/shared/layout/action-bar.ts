import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { ThemeService } from '../../core/services/theme.service';
import { UiStore } from '../../core/services/ui.store';
import { Icon } from '../ui/icon';

/**
 * Change #2 (uiux_plan.md §0): the mockup's "Good evening, {name}" greeting,
 * subhead and demo chip are gone. What's left is a slim right-aligned bar —
 * theme toggle and "Add company" — plus an optional page title for the routes
 * that need one (the dashboard deliberately has none).
 */
@Component({
  selector: 'app-action-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="action-bar">
      @if (title()) {
        <h1 class="page-title">
          {{ title() }}
          @if (subtitle()) {
            <span class="sub">{{ subtitle() }}</span>
          }
        </h1>
      }

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
        Add company
      </button>
    </div>
  `,
})
export class ActionBar {
  protected readonly theme = inject(ThemeService);
  protected readonly ui = inject(UiStore);

  readonly title = input('');
  readonly subtitle = input('');
}
