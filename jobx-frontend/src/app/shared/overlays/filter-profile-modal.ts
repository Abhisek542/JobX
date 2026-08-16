import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { AppError } from '../../core/models/api-error.model';
import { FilterProfileStore } from '../../core/services/filter-profile.store';
import { ToastService } from '../../core/services/toast.service';
import { parseList } from '../../core/util/text-lists';
import { Icon } from '../ui/icon';
import { OverlayShell } from './overlay-shell';

/**
 * "Search preferences" — the entire matching engine, stated plainly
 * (uiux_plan.md §10 phase 6).
 *
 * The two server-side rules are enforced here first so the user sees them as
 * form errors instead of a 400: at least one keyword must survive
 * normalization, and expMin <= expMax. Lists are trimmed and deduped
 * case-insensitively client-side too, mirroring the backend's TextLists, so
 * what the form shows is what the server will store.
 */
@Component({
  selector: 'app-filter-profile-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, OverlayShell],
  template: `
    <app-overlay label="Search preferences" (closed)="close()">
      <div class="modal-head">
        <div>
          <h3>Search preferences</h3>
          <p>These three rules are the entire matching engine. Nothing hidden.</p>
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

        <div class="field" [class.invalid]="keywordError()">
          <label for="pf-keywords">Keywords</label>
          <textarea
            id="pf-keywords"
            rows="2"
            [value]="keywords()"
            (input)="keywords.set($any($event.target).value)"
          ></textarea>
          <p class="hint">
            Comma-separated. A role needs <b>at least one</b> — not all. A hit in the title counts
            double.
          </p>
          @if (keywordError(); as message) {
            <p class="err">{{ message }}</p>
          }
        </div>

        <div class="field">
          <label for="pf-exclude">Exclude words</label>
          <textarea
            id="pf-exclude"
            rows="2"
            [value]="excludeWords()"
            (input)="excludeWords.set($any($event.target).value)"
          ></textarea>
          <p class="hint">
            Any hit anywhere in the title or description drops the role completely. Whole words
            only — <code>lead</code> won't strike out <code>leading</code>.
          </p>
        </div>

        <div class="field field-row" [class.invalid]="rangeError()">
          <div>
            <label for="pf-min">Experience min</label>
            <input
              id="pf-min"
              type="number"
              min="0"
              max="40"
              [value]="expMin()"
              (input)="expMin.set($any($event.target).value)"
            />
          </div>
          <div>
            <label for="pf-max">Experience max</label>
            <input
              id="pf-max"
              type="number"
              min="0"
              max="40"
              [value]="expMax()"
              (input)="expMax.set($any($event.target).value)"
            />
          </div>
        </div>
        @if (rangeError(); as message) {
          <p class="err" style="margin:-9px 0 15px">{{ message }}</p>
        }

        <div class="note" style="margin-bottom:6px">
          <app-icon name="info" size="sm" />
          <div>
            Experience is a <b>soft</b> signal worth up to 30 of the 100 points. A role outside your
            range still shows up — it just scores lower.
          </div>
        </div>
      </div>

      <div class="modal-foot">
        <button class="btn btn-ghost" type="button" (click)="close()">Cancel</button>
        <button class="btn btn-primary" type="button" [disabled]="saving()" (click)="submit()">
          {{ saving() ? 'Saving…' : 'Save preferences' }}
        </button>
      </div>
    </app-overlay>
  `,
})
export class FilterProfileModal {
  private readonly store = inject(FilterProfileStore);
  private readonly toasts = inject(ToastService);

  readonly closed = output<void>();
  readonly saved = output<void>();

  protected readonly keywords = signal('');
  protected readonly excludeWords = signal('');
  protected readonly expMin = signal('');
  protected readonly expMax = signal('');
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly keywordError = signal<string | null>(null);
  protected readonly rangeError = signal<string | null>(null);

  constructor() {
    const profile = this.store.profile();
    if (profile) {
      this.keywords.set(profile.keywords.join(', '));
      this.excludeWords.set(profile.excludeWords.join(', '));
      this.expMin.set(profile.expMin == null ? '' : String(profile.expMin));
      this.expMax.set(profile.expMax == null ? '' : String(profile.expMax));
    }
  }

  protected close(): void {
    this.closed.emit();
  }

  protected async submit(): Promise<void> {
    this.formError.set(null);
    this.keywordError.set(null);
    this.rangeError.set(null);

    const keywords = parseList(this.keywords());
    if (!keywords.length) {
      this.keywordError.set('At least one keyword is required — matching is OR over this list.');
      return;
    }

    const expMin = toNumber(this.expMin());
    const expMax = toNumber(this.expMax());
    if (expMin != null && expMin < 0) {
      this.rangeError.set('Experience cannot be negative.');
      return;
    }
    if (expMax != null && expMax < 0) {
      this.rangeError.set('Experience cannot be negative.');
      return;
    }
    if (expMin != null && expMax != null && expMin > expMax) {
      this.rangeError.set('Minimum experience cannot exceed the maximum.');
      return;
    }

    this.saving.set(true);
    try {
      await this.store.save({
        keywords,
        excludeWords: parseList(this.excludeWords()),
        expMin,
        expMax,
      });
      this.saved.emit();
      this.closed.emit();
      this.toasts.ok('Preferences saved · scores update on the next check');
    } catch (error) {
      const appError = error as AppError;
      this.saving.set(false);
      this.formError.set(appError.detail);
    }
  }
}

function toNumber(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? Math.trunc(parsed) : null;
}
