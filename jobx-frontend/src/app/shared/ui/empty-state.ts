import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from './icon';
import { IconName } from './icons';

/**
 * The dashed panel used for every empty/error condition in uiux_plan.md §8.
 * Actions are projected so each caller supplies its own CTA.
 */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="empty" [class.warn]="tone() === 'warn'" [class.bad]="tone() === 'bad'">
      <div class="glyph"><app-icon [name]="icon()" /></div>
      <h3>{{ heading() }}</h3>
      <p>{{ body() }}</p>
      <div class="actions"><ng-content /></div>
    </div>
  `,
})
export class EmptyState {
  readonly icon = input<IconName>('search');
  readonly heading = input.required<string>();
  readonly body = input('');
  readonly tone = input<'brand' | 'warn' | 'bad'>('brand');
}
