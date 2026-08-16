import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { AppError } from '../../core/models/api-error.model';
import {
  AtsPlatform,
  PLATFORM_LABEL,
  SUPPORTED_PLATFORMS,
  TOKEN_HINTS,
  WatchedCompanyResponse,
} from '../../core/models/watchlist.model';
import { ToastService } from '../../core/services/toast.service';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { Icon } from '../ui/icon';
import { OverlayShell } from './overlay-shell';

/**
 * "Add a company" (uiux_plan.md §10 phase 6).
 *
 * Only the four platforms with a real public API are offered — UNSUPPORTED is
 * never a choice a user can make, even though the enum accepts it. The token
 * hint changes per platform because a guessed board token is the single most
 * common way to end up with a permanently FAILED watch.
 */
@Component({
  selector: 'app-add-company-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, OverlayShell],
  template: `
    <app-overlay label="Add a company" (closed)="close()">
      <div class="modal-head">
        <div>
          <h3>Add a company</h3>
          <p>Jobx reads the company's own ATS board — no scraping, no aggregators.</p>
        </div>
        <button class="x-btn" type="button" aria-label="Close" (click)="close()">
          <app-icon name="x" />
        </button>
      </div>

      <div class="modal-body">
        @if (formError(); as message) {
          <div class="auth-error">
            <app-icon name="alert" size="sm" />
            <span>{{ message }}</span>
          </div>
        }

        <div class="field" [class.invalid]="fieldError('companyName')">
          <label for="ac-name">Company name</label>
          <input
            id="ac-name"
            type="text"
            placeholder="e.g. Razorpay"
            [value]="name()"
            (input)="name.set($any($event.target).value)"
          />
          @if (fieldError('companyName'); as message) {
            <p class="err">{{ message }}</p>
          }
        </div>

        <div class="field">
          <label for="ac-platform">ATS platform</label>
          <select
            id="ac-platform"
            [value]="platform()"
            (change)="platform.set($any($event.target).value)"
          >
            @for (option of platforms; track option) {
              <option [value]="option">{{ label(option) }}</option>
            }
          </select>
        </div>

        <div class="field" [class.invalid]="fieldError('boardToken')">
          <label for="ac-token">Board token</label>
          <input
            id="ac-token"
            type="text"
            [placeholder]="hint().placeholder"
            [value]="token()"
            (input)="token.set($any($event.target).value)"
          />
          <p class="hint">
            Take it straight from the careers URL — don't guess it.<br />
            <code>{{ hint().url }}<b>{{ hint().token }}</b></code>
          </p>
          @if (fieldError('boardToken'); as message) {
            <p class="err">{{ message }}</p>
          }
        </div>

        <div class="note" style="margin-bottom:6px">
          <app-icon name="info" size="sm" />
          <div>
            Boards on Workday, Rippling or BambooHR have no public API. Jobx marks those
            <b>unsupported</b> rather than pretending to watch them.
          </div>
        </div>
      </div>

      <div class="modal-foot">
        <button class="btn btn-ghost" type="button" (click)="close()">Cancel</button>
        <button class="btn btn-primary" type="button" [disabled]="saving()" (click)="submit()">
          {{ saving() ? 'Adding…' : 'Add & check now' }}
        </button>
      </div>
    </app-overlay>
  `,
})
export class AddCompanyModal {
  private readonly store = inject(WatchlistStore);
  private readonly toasts = inject(ToastService);

  readonly closed = output<void>();
  readonly added = output<WatchedCompanyResponse>();

  protected readonly platforms = SUPPORTED_PLATFORMS;
  protected readonly name = signal('');
  protected readonly token = signal('');
  protected readonly platform = signal<AtsPlatform>('GREENHOUSE');
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  private readonly fieldErrors = signal<Record<string, string>>({});

  protected readonly hint = computed(
    () => TOKEN_HINTS[this.platform()] ?? TOKEN_HINTS['GREENHOUSE'],
  );

  protected label(platform: AtsPlatform): string {
    return PLATFORM_LABEL[platform];
  }

  protected fieldError(field: string): string | undefined {
    return this.fieldErrors()[field];
  }

  protected close(): void {
    this.closed.emit();
  }

  protected async submit(): Promise<void> {
    const companyName = this.name().trim();
    const boardToken = this.token().trim();

    this.fieldErrors.set({});
    this.formError.set(null);

    const localErrors: Record<string, string> = {};
    if (!companyName) localErrors['companyName'] = 'Company name is required';
    if (!boardToken) localErrors['boardToken'] = 'Board token is required';
    if (Object.keys(localErrors).length) {
      this.fieldErrors.set(localErrors);
      return;
    }

    this.saving.set(true);
    try {
      const company = await this.store.add({
        companyName,
        boardToken,
        atsPlatform: this.platform(),
      });
      this.added.emit(company);
      this.closed.emit();
      this.toasts.ok(`${company.companyName} added · running the first check now`);
      // "Add & check now" — the first scheduled cycle can be up to 30 min away.
      this.store.checkNow(company);
    } catch (error) {
      const appError = error as AppError;
      this.saving.set(false);
      this.fieldErrors.set(appError.fieldErrors ?? {});
      this.formError.set(
        appError.status === 409
          ? `You're already watching ${companyName || 'this company'} on ${this.label(
              this.platform(),
            )}.`
          : appError.detail,
      );
    }
  }
}
