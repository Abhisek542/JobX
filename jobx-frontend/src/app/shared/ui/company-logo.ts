import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { logoStyle, logoText } from '../../core/util/logo';

/**
 * Initials on a deterministic hue. No API field carries a logo URL and we do
 * not call an external logo service (frontend_constraints.md §12) — so this is
 * the honest stand-in, and it never makes a network request.
 */
@Component({
  selector: 'app-company-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="logo" [style]="style()" [style.width.px]="size()" [style.height.px]="size()"
         [style.font-size.px]="fontSize()" [attr.aria-hidden]="true">
      {{ text() }}
    </div>
  `,
})
export class CompanyLogo {
  readonly companyName = input.required<string>();
  readonly size = input(52);

  readonly text = computed(() => logoText(this.companyName()));
  readonly style = computed(() => logoStyle(this.companyName()));
  readonly fontSize = computed(() => Math.round(this.size() * 0.33));
}
