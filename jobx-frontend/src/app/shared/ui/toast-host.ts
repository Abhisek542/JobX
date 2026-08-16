import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService } from '../../core/services/toast.service';

/** Renders ToastService's queue. Mounted once, in the app root. */
@Component({
  selector: 'app-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" role="status" aria-live="polite">
      @for (toast of toasts.toasts(); track toast.id) {
        <div class="toast" [class.ok]="toast.kind === 'ok'" [class.err]="toast.kind === 'err'">
          <span>{{ toast.message }}</span>
          @if (toast.undo) {
            <button class="undo" type="button" (click)="toasts.runUndo(toast)">Undo</button>
          }
        </div>
      }
    </div>
  `,
})
export class ToastHost {
  protected readonly toasts = inject(ToastService);
}
