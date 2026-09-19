import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AppError } from '../../core/models/api-error.model';
import { AuthStore } from '../../core/services/auth.store';
import { AuthHero } from '../../shared/ui/auth-hero';
import { Icon } from '../../shared/ui/icon';

/**
 * Sign-in, laid out in the landing hero (AuthHero). The form is the same
 * email + password form as before — one "search bar" shaped row in the
 * redesign — with the same validation, banners and busy state.
 */
@Component({
  selector: 'app-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AuthHero, Icon, RouterLink],
  template: `
    <app-auth-hero mode="login">
      <h1 heroTitle>Find a job<br />that <em>moves you</em><br />forward.</h1>
      <p heroLead class="lead">
        Watch the careers boards you care about, get every new role scored against your keywords,
        and apply on the employer's own page — before it reaches the aggregators.
      </p>

      <form class="auth-form" (submit)="submit($event)">
        @if (expired()) {
          <div class="auth-error">
            <app-icon name="alert" size="sm" />
            <span>Your session expired. Sign in again to continue.</span>
          </div>
        }
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
              autocomplete="current-password"
              placeholder="Password"
              required
              [value]="password()"
              (input)="password.set($any($event.target).value)"
            />
          </div>
          <button class="btn btn-primary" type="submit" [disabled]="busy()">
            {{ busy() ? 'Signing in…' : 'Sign in' }}
            @if (!busy()) {
              <app-icon name="arrow-right" size="sm" />
            }
          </button>
        </div>

        @if (fieldError('email'); as message) {
          <p class="sb-err">{{ message }}</p>
        }
        @if (fieldError('password'); as message) {
          <p class="sb-err">{{ message }}</p>
        }
      </form>

      <p heroFoot class="new-here">
        New to Jobx? <a routerLink="/register">Create an account →</a>
      </p>
    </app-auth-hero>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);

  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fieldErrors = signal<Record<string, string>>({});

  /** Set by error.interceptor when a 401 killed the session mid-use. */
  protected readonly expired = signal(
    new URLSearchParams(location.search).get('expired') === '1',
  );

  protected fieldError(field: string): string | undefined {
    return this.fieldErrors()[field];
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (this.busy()) return;

    this.error.set(null);
    this.fieldErrors.set({});
    this.expired.set(false);

    const email = this.email().trim();
    if (!email || !this.password()) {
      this.fieldErrors.set({
        ...(email ? {} : { email: 'Email is required' }),
        ...(this.password() ? {} : { password: 'Password is required' }),
      });
      return;
    }

    this.busy.set(true);
    this.auth.login({ email, password: this.password() }).subscribe({
      next: () => void this.router.navigate(['/dashboard']),
      error: (error: AppError) => {
        this.busy.set(false);
        this.fieldErrors.set(error.fieldErrors);
        // 401 is deliberately generic server-side — it never says which half was
        // wrong — so pass the backend's own wording straight through.
        this.error.set(
          error.status === 429
            ? `Too many attempts · ${error.detail}`
            : error.detail,
        );
      },
    });
  }
}
