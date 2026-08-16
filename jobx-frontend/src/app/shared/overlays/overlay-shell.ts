import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  inject,
  input,
  output,
} from '@angular/core';

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/**
 * Scrim + focus management for every modal and the detail drawer
 * (uiux_plan.md §10 phase 8): Escape closes, a backdrop click closes, Tab is
 * trapped inside, and focus returns to whatever opened the overlay.
 */
@Component({
  selector: 'app-overlay',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:keydown)': 'onKeydown($event)',
  },
  template: `
    <div
      class="scrim"
      [class.right]="variant() === 'drawer'"
      (mousedown)="onBackdrop($event)"
      role="presentation"
    >
      <div [class]="panelClass()" role="dialog" aria-modal="true" [attr.aria-label]="label()">
        <ng-content />
      </div>
    </div>
  `,
})
export class OverlayShell implements AfterViewInit, OnDestroy {
  readonly variant = input<'modal' | 'drawer'>('modal');
  readonly label = input('');
  readonly closed = output<void>();

  protected readonly panelClass = computed(() =>
    this.variant() === 'drawer' ? 'drawer' : 'modal',
  );

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly restoreFocusTo = document.activeElement as HTMLElement | null;
  private readonly previousOverflow = document.body.style.overflow;

  ngAfterViewInit(): void {
    document.body.style.overflow = 'hidden';
    // Focus the first real field so a form overlay is typeable immediately; the
    // first focusable is usually the close button, which would swallow typing.
    const items = this.focusables();
    const firstField = items.find((el) =>
      ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName),
    );
    (firstField ?? items[0])?.focus();
  }

  ngOnDestroy(): void {
    document.body.style.overflow = this.previousOverflow;
    this.restoreFocusTo?.focus?.();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.closed.emit();
      return;
    }
    if (event.key !== 'Tab') return;

    const items = this.focusables();
    if (!items.length) return;

    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;

    if (event.shiftKey && (active === first || !this.host.nativeElement.contains(active))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  /** Only a press that both starts and ends on the backdrop closes it. */
  protected onBackdrop(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.closed.emit();
    }
  }

  private focusables(): HTMLElement[] {
    return Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (el) => el.offsetParent !== null || el.getClientRects().length > 0,
    );
  }
}
