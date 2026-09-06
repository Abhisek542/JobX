import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AppError } from '../../core/models/api-error.model';
import {
  AtsPlatform,
  CompanySearchResponse,
  PLATFORM_LABEL,
  ResolvedBoardResponse,
  SOURCE_LABEL,
  SUPPORTED_PLATFORMS,
  TOKEN_HINTS,
  WatchedCompanyResponse,
} from '../../core/models/watchlist.model';
import { WatchlistApi } from '../../core/api/watchlist.api';
import { ToastService } from '../../core/services/toast.service';
import { WatchlistStore } from '../../core/services/watchlist.store';
import { CompanyLogo } from '../ui/company-logo';
import { Icon } from '../ui/icon';
import { OverlayShell } from './overlay-shell';

type Step = 'input' | 'resolving' | 'confirm' | 'dead-end';

/**
 * "Add a company".
 *
 * The form used to ask for three things and the user knew one of them. Company
 * name, yes — but "ATS platform" means nothing to a job seeker, and a board
 * token is an opaque, case-sensitive string that is frequently not the company
 * name at all (Razorpay's is `razorpaysoftwareprivatelimited`, Lever's Sprinto
 * board 404s as `sprinto`). So this now asks for the one thing a user actually
 * has — a name, a website, or a careers link — and works the board out.
 *
 * Four steps:
 *   input     one field, with catalog suggestions as they type
 *   resolving the probe path takes seconds; it must not look frozen
 *   confirm   the board, with REAL job titles off it, and a Watch button
 *   dead-end  honest "we can't watch this portal yet", request recorded
 *
 * The confirm step is not polish. Everything except a catalog hit is derived —
 * read off a careers page, or guessed from a slug and then checked against the
 * live API — and a job count alone cannot tell that a "porter" board belongs to
 * a different Porter. Showing real titles and letting a human say yes is what
 * keeps this inside the project's "never guess a board token" rule: the machine
 * proposes, the person confirms.
 *
 * Manual entry survives as "Advanced", collapsed. Nothing was taken away.
 */
@Component({
  selector: 'app-add-company-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CompanyLogo, Icon, OverlayShell],
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

        @switch (step()) {
          @case ('input') {
            <div class="field" [class.invalid]="fieldError('query')">
              <label for="ac-query">Company name, website or careers link</label>
              <input
                id="ac-query"
                type="text"
                autocomplete="off"
                placeholder="Razorpay · razorpay.com · jobs.lever.co/fampay"
                [value]="query()"
                (input)="onQueryInput($any($event.target).value)"
                (keydown.enter)="findBoard()"
              />
              <p class="hint">
                Any of the three works. You don't need to know which system they use.
              </p>
              @if (fieldError('query'); as message) {
                <p class="err">{{ message }}</p>
              }
            </div>

            @if (suggestions().length) {
              <ul class="ac-suggestions" role="listbox" aria-label="Companies Jobx already tracks">
                @for (option of suggestions(); track option.companyId) {
                  <li>
                    <button
                      type="button"
                      role="option"
                      [attr.aria-selected]="false"
                      (click)="pickSuggestion(option)"
                    >
                      <app-company-logo [companyName]="option.companyName" [size]="30" />
                      <span class="ac-sug-name">{{ option.companyName }}</span>
                      <span class="ac-sug-meta">
                        {{ label(option.atsPlatform) }}
                        @if (option.alreadyWatched) {
                          · already watching
                        }
                      </span>
                    </button>
                  </li>
                }
              </ul>
            }

            <details class="ac-advanced" [open]="advancedOpen()">
              <summary (click)="toggleAdvanced($event)">
                Advanced — enter the board details myself
              </summary>

              <div class="field">
                <label for="ac-name">Company name</label>
                <input
                  id="ac-name"
                  type="text"
                  placeholder="e.g. Razorpay"
                  [value]="name()"
                  (input)="name.set($any($event.target).value)"
                />
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
                  <code
                    >{{ hint().url }}<b>{{ hint().token }}</b></code
                  >
                </p>
                @if (fieldError('boardToken'); as message) {
                  <p class="err">{{ message }}</p>
                }
              </div>
            </details>
          }

          @case ('resolving') {
            <div class="ac-working">
              <app-icon name="search" />
              <div>
                <b>Looking for {{ query() }}'s job board…</b>
                <p>Checking their careers page and the job-board providers we support.</p>
              </div>
            </div>
          }

          @case ('confirm') {
            <p class="ac-lead">
              {{
                candidates().length > 1
                  ? 'We found more than one board — pick the right one:'
                  : 'Is this the right company?'
              }}
            </p>

            @for (board of candidates(); track board.atsPlatform + board.boardToken) {
              <button
                type="button"
                class="ac-card"
                [class.selected]="board === selected()"
                (click)="selected.set(board)"
              >
                <app-company-logo [companyName]="board.companyName" [size]="40" />
                <div class="ac-card-main">
                  <b>{{ board.companyName }}</b>
                  <span class="ac-card-meta">
                    {{ label(board.atsPlatform) }} · {{ board.jobCount }}
                    {{ board.jobCount === 1 ? 'open role' : 'open roles' }}
                  </span>
                  @if (board.sampleTitles.length) {
                    <span class="ac-card-titles">{{ board.sampleTitles.join(' · ') }}</span>
                  }
                  <span class="ac-card-source">
                    <app-icon name="check" size="sm" />{{ sourceLabel(board) }}
                  </span>
                  @if (board.alreadyWatched) {
                    <span class="ac-card-watched">Already on your watchlist</span>
                  }
                </div>
              </button>
            }

            @if (selected(); as board) {
              <p class="hint ac-verify">
                Not sure?
                <a [href]="board.boardUrl" target="_blank" rel="noopener noreferrer">
                  Open their board<app-icon name="external-link" size="sm"
                /></a>
                and check it's the company you mean.
              </p>
            }
          }

          @case ('dead-end') {
            <div class="note ac-deadend">
              <app-icon name="info" size="sm" />
              <div>
                <b>We can't watch this one yet.</b>
                @if (platformHint(); as hinted) {
                  <p>
                    {{ query() }} looks like it uses {{ label(hinted) }}, but their board is loaded
                    by JavaScript so we couldn't read its ID from the page. Pasting the direct link
                    to their job board usually fixes it.
                  </p>
                } @else {
                  <p>
                    We couldn't find a job board for “{{ query() }}”. Portals like Workday, Rippling
                    and BambooHR have no public API, so Jobx marks those <b>unsupported</b> rather
                    than pretending to watch them.
                  </p>
                }
                <p>We've noted the request — it helps us decide what to support next.</p>
              </div>
            </div>

            <button
              type="button"
              class="btn btn-ghost ac-manual"
              (click)="openAdvancedFromDeadEnd()"
            >
              Enter the board details myself
            </button>
          }
        }
      </div>

      <div class="modal-foot">
        @switch (step()) {
          @case ('input') {
            <button class="btn btn-ghost" type="button" (click)="close()">Cancel</button>
            @if (advancedOpen() && token().trim()) {
              <button
                class="btn btn-primary"
                type="button"
                [disabled]="saving()"
                (click)="watchManual()"
              >
                {{ saving() ? 'Adding…' : 'Add & check now' }}
              </button>
            } @else {
              <button
                class="btn btn-primary"
                type="button"
                [disabled]="!query().trim()"
                (click)="findBoard()"
              >
                Find their board
              </button>
            }
          }
          @case ('resolving') {
            <button class="btn btn-ghost" type="button" (click)="close()">Cancel</button>
          }
          @case ('confirm') {
            <button class="btn btn-ghost" type="button" (click)="back()">Back</button>
            <button
              class="btn btn-primary"
              type="button"
              [disabled]="saving() || !selected() || selected()!.alreadyWatched"
              (click)="watchSelected()"
            >
              {{ saving() ? 'Adding…' : 'Watch this board' }}
            </button>
          }
          @case ('dead-end') {
            <button class="btn btn-ghost" type="button" (click)="back()">Back</button>
            <button class="btn btn-primary" type="button" (click)="close()">Done</button>
          }
        }
      </div>
    </app-overlay>
  `,
})
export class AddCompanyModal {
  private readonly store = inject(WatchlistStore);
  private readonly api = inject(WatchlistApi);
  private readonly toasts = inject(ToastService);

  readonly closed = output<void>();
  readonly added = output<WatchedCompanyResponse>();

  protected readonly platforms = SUPPORTED_PLATFORMS;

  protected readonly step = signal<Step>('input');
  protected readonly query = signal('');
  protected readonly suggestions = signal<CompanySearchResponse[]>([]);
  protected readonly candidates = signal<ResolvedBoardResponse[]>([]);
  protected readonly selected = signal<ResolvedBoardResponse | null>(null);
  protected readonly platformHint = signal<AtsPlatform | null>(null);
  protected readonly advancedOpen = signal(false);

  // Advanced (manual) fields — unchanged from the original form.
  protected readonly name = signal('');
  protected readonly token = signal('');
  protected readonly platform = signal<AtsPlatform>('GREENHOUSE');

  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  private readonly fieldErrors = signal<Record<string, string>>({});

  protected readonly hint = computed(
    () => TOKEN_HINTS[this.platform()] ?? TOKEN_HINTS['GREENHOUSE'],
  );

  /**
   * Typeahead over boards Jobx already knows. switchMap so a slow response for
   * an earlier keystroke can never overwrite a newer one's suggestions.
   */
  private readonly typed = new Subject<string>();

  constructor() {
    this.typed
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => this.api.searchCompanies(q)),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (results) => this.suggestions.set(results),
        // A failing typeahead is not worth an error banner — the user can still
        // hit "Find their board", which is the path that actually matters.
        error: () => this.suggestions.set([]),
      });
  }

  protected label(platform: AtsPlatform): string {
    return PLATFORM_LABEL[platform];
  }

  protected sourceLabel(board: ResolvedBoardResponse): string {
    return SOURCE_LABEL[board.source];
  }

  protected fieldError(field: string): string | undefined {
    return this.fieldErrors()[field];
  }

  protected close(): void {
    this.closed.emit();
  }

  protected back(): void {
    this.step.set('input');
    this.formError.set(null);
  }

  protected onQueryInput(value: string): void {
    this.query.set(value);
    const trimmed = value.trim();
    if (trimmed.length < 2) {
      this.suggestions.set([]);
      return;
    }
    this.typed.next(trimmed);
  }

  protected toggleAdvanced(event: Event): void {
    event.preventDefault();
    this.advancedOpen.update((open) => !open);
  }

  protected openAdvancedFromDeadEnd(): void {
    this.advancedOpen.set(true);
    this.step.set('input');
  }

  /** A catalog pick is already a confirmed board — skip straight to confirming it. */
  protected pickSuggestion(option: CompanySearchResponse): void {
    const board: ResolvedBoardResponse = {
      source: 'CATALOG',
      atsPlatform: option.atsPlatform,
      boardToken: option.boardToken,
      companyName: option.companyName,
      boardUrl: '',
      jobCount: 0,
      sampleTitles: [],
      alreadyWatched: option.alreadyWatched,
    };
    this.query.set(option.companyName);
    this.suggestions.set([]);
    this.candidates.set([board]);
    this.selected.set(board);
    this.step.set('confirm');
  }

  protected findBoard(): void {
    const query = this.query().trim();
    this.fieldErrors.set({});
    this.formError.set(null);

    if (!query) {
      this.fieldErrors.set({ query: 'Enter a company name, website or careers link' });
      return;
    }

    this.suggestions.set([]);
    this.step.set('resolving');

    this.api.resolve(query).subscribe({
      next: (response) => {
        this.platformHint.set(response.platformHint);
        if (!response.candidates.length) {
          this.reportUnsupported(query, response.platformHint);
          this.step.set('dead-end');
          return;
        }
        this.candidates.set(response.candidates);
        this.selected.set(response.candidates[0]);
        this.step.set('confirm');
      },
      error: (error: AppError) => {
        this.step.set('input');
        // 400 invalid_url is the one failure the user can fix, so it belongs on
        // the field rather than in a banner.
        if (error.status === 400) {
          this.fieldErrors.set({ query: error.detail });
        } else {
          this.formError.set(error.detail);
        }
      },
    });
  }

  /**
   * Fire-and-forget. The user is already being told we can't help; a failure to
   * record that is our problem, not theirs.
   */
  private reportUnsupported(query: string, hint: AtsPlatform | null): void {
    this.api.reportUnsupported({ query, platformHint: hint }).subscribe({ error: () => {} });
  }

  protected watchSelected(): void {
    const board = this.selected();
    if (!board) {
      return;
    }
    void this.watch(board.companyName, board.atsPlatform, board.boardToken);
  }

  protected watchManual(): void {
    const companyName = this.name().trim() || this.query().trim();
    const boardToken = this.token().trim();

    this.fieldErrors.set({});
    this.formError.set(null);

    const localErrors: Record<string, string> = {};
    if (!companyName) localErrors['query'] = 'Company name is required';
    if (!boardToken) localErrors['boardToken'] = 'Board token is required';
    if (Object.keys(localErrors).length) {
      this.fieldErrors.set(localErrors);
      return;
    }

    void this.watch(companyName, this.platform(), boardToken);
  }

  /**
   * The one place a company is actually added, shared by the resolved and manual
   * paths — POST /watchlist is unchanged, so both send the same payload.
   *
   * The success handling below is the 2026-08-29 feed-reload fix and is easy to
   * break: `lastFetchStatus === null` means Jobx has never checked this board,
   * so "the first check" is literally true. A board someone else already watches
   * has been checked before — and the add just backfilled this user's matches
   * from its stored jobs — so promising a first check there would misdescribe
   * where the roles about to appear came from.
   */
  private async watch(companyName: string, atsPlatform: AtsPlatform, boardToken: string) {
    this.saving.set(true);
    try {
      const company = await this.store.add({ companyName, atsPlatform, boardToken });
      this.added.emit(company);
      this.closed.emit();

      const neverChecked = company.lastFetchStatus === null;
      this.toasts.ok(
        neverChecked
          ? `${company.companyName} added · running the first check now`
          : `${company.companyName} added · scoring the roles already on that board`,
      );

      // "Add & check now" — the first scheduled cycle can be up to 30 min away.
      // Stays quiet about "no new roles": for an already-watched board the
      // shared cooldown answers 200-with-zeros, which would contradict the
      // backfilled matches landing in the feed at the same moment.
      this.store.checkNow(company, { quietWhenNothingNew: !neverChecked });
    } catch (error) {
      const appError = error as AppError;
      this.saving.set(false);
      this.step.set('input');
      this.fieldErrors.set(appError.fieldErrors ?? {});
      this.formError.set(
        appError.status === 409
          ? `You're already watching ${companyName} on ${this.label(atsPlatform)}.`
          : appError.detail,
      );
    }
  }
}
