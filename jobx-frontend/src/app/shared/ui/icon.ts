import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ICONS, IconName, IconShape } from './icons';

/**
 * One inline SVG renderer for the whole app: <app-icon name="search" />.
 * Size and stroke come from the .icon / .icon-sm / .icon-xs classes so callers
 * control weight the same way the mockup did.
 */
@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [class]="svgClass()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      @for (shape of shapes(); track $index) {
        @switch (shape.k) {
          @case ('path') {
            <path [attr.d]="asPath(shape).d" />
          }
          @case ('circle') {
            <circle
              [attr.cx]="asCircle(shape).cx"
              [attr.cy]="asCircle(shape).cy"
              [attr.r]="asCircle(shape).r"
            />
          }
          @case ('rect') {
            <rect
              [attr.x]="asRect(shape).x"
              [attr.y]="asRect(shape).y"
              [attr.width]="asRect(shape).w"
              [attr.height]="asRect(shape).h"
              [attr.rx]="asRect(shape).rx ?? 0"
            />
          }
          @case ('polyline') {
            <polyline [attr.points]="asPoly(shape).points" />
          }
          @case ('polygon') {
            <polygon [attr.points]="asPoly(shape).points" />
          }
        }
      }
    </svg>
  `,
})
export class Icon {
  readonly name = input.required<IconName>();
  /** 'md' | 'sm' | 'xs' — maps to the global .icon* classes. */
  readonly size = input<'md' | 'sm' | 'xs'>('md');

  readonly shapes = computed<readonly IconShape[]>(() => ICONS[this.name()]);
  readonly svgClass = computed(() =>
    this.size() === 'sm' ? 'icon-sm' : this.size() === 'xs' ? 'icon-xs' : 'icon',
  );

  /* Narrowing helpers — @switch on a discriminated union doesn't narrow the
     bound expression inside the template, so each case asks for its own view. */
  asPath(shape: IconShape) {
    return shape as Extract<IconShape, { k: 'path' }>;
  }
  asCircle(shape: IconShape) {
    return shape as Extract<IconShape, { k: 'circle' }>;
  }
  asRect(shape: IconShape) {
    return shape as Extract<IconShape, { k: 'rect' }>;
  }
  asPoly(shape: IconShape) {
    return shape as Extract<IconShape, { k: 'polyline' | 'polygon' }>;
  }
}
