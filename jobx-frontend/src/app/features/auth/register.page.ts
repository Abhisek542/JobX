import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AppError } from '../../core/models/api-error.model';
import { AuthStore } from '../../core/services/auth.store';
import { AuthHero } from '../../shared/ui/auth-hero';
import { ThemeService } from '../../core/services/theme.service';
import { safeNext } from '../../core/util/redirect';
import { Icon } from '../../shared/ui/icon';

/** Password rule mirrors RegisterRequest's @Size(min = 8). */
const MIN_PASSWORD = 8;

@Component({
  selector: 'app-register-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AuthHero, Icon, RouterLink],
  template: `
    <app-auth-hero mode="register">
      <h1 heroTitle>Get the first look<br />at <em>every new role</em>.</h1>
      <p heroLead class="lead">
        Create your account, pick the companies you care about, and Jobx reads their own careers
        boards directly. No aggregators, no auto-apply.
      </p>

      <form class="auth-form" (submit)="submit($event)">
        @if (error(); as message) {
          <div class="auth-error">
            <app-icon name="alert" size="sm" />
            <span>{{ message }}</span>
          </div>
        }

        <div class="signin-bar">
          <div class="sb-field" [class.invalid]="fieldError('email')">
            <app-icon name="mail" size="sm" />
            <label class="sr-only" for="email">Email</label>
            <input
              id="email"
              type="email"
              autocomplete="email"
              placeholder="Email address"
              required
              [value]="email()"
              (input)="email.set($any($event.target).value)"
            />
          </div>
          <span class="sb-div"></span>
          <div class="sb-field" [class.invalid]="fieldError('password')">
            <app-icon name="lock" size="sm" />
            <label class="sr-only" for="password">Password</label>
            <input
              id="password"
              type="password"
              autocomplete="new-password"
              placeholder="Password"
              required
              aria-describedby="pw-hint"
              [value]="password()"
              (input)="password.set($any($event.target).value)"
            />
          </div>
          <button class="btn btn-primary" type="submit" [disabled]="busy()">
            {{ busy() ? 'Creating account…' : 'Create account' }}
            @if (!busy()) {
              <app-icon name="arrow-right" size="sm" />
            }
          </button>
        </div>

        <p class="sb-hint" id="pw-hint">Password: at least {{ minPassword }} characters.</p>
        @if (fieldError('email'); as message) {
          <p class="sb-err">{{ message }}</p>
        }
        @if (fieldError('password'); as message) {
          <p class="sb-err">{{ message }}</p>
        }
        <button class="btn btn-primary" type="submit" [disabled]="busy()">
          {{ busy() ? 'Creating account…' : 'Create account' }}
        </button>

        <p class="auth-foot">
          Already have an account?
          <a routerLink="/login" [queryParams]="{ next: nextParam() }">Sign in</a>
        </p>
      </form>

      <p heroFoot class="new-here">
        Already have an account? <a routerLink="/login">Sign in →</a>
      </p>
    </app-auth-hero>
  `,
})
export class RegisterPage {
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly minPassword = MIN_PASSWORD;
  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fieldErrors = signal<Record<string, string>>({});

  /** Raw ?next= carried over from /login; validated by safeNext before use. */
  protected nextParam(): string | null {
    return this.route.snapshot.queryParamMap.get('next');
  }

  protected fieldError(field: string): string | undefined {
    return this.fieldErrors()[field];
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (this.busy()) return;

    this.error.set(null);
    this.fieldErrors.set({});

    const email = this.email().trim();
    const password = this.password();
    const localErrors: Record<string, string> = {};
    if (!email) localErrors['email'] = 'Email is required';
    if (password.length < MIN_PASSWORD) {
      localErrors['password'] = `Password must be at least ${MIN_PASSWORD} characters`;
    }
    if (Object.keys(localErrors).length) {
      this.fieldErrors.set(localErrors);
      return;
    }

    this.busy.set(true);
    this.auth.register({ email, password }).subscribe({
      next: () => void this.router.navigateByUrl(safeNext(this.nextParam())),
      error: (error: AppError) => {
        this.busy.set(false);
        this.fieldErrors.set(error.fieldErrors);
        this.error.set(
          error.status === 409
            ? 'That email is already registered — sign in instead.'
            : error.status === 429
              ? `Too many attempts · ${error.detail}`
              : error.detail,
        );
      },
    });
  }
}
