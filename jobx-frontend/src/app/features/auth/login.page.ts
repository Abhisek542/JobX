import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AppError } from '../../core/models/api-error.model';
import { AuthStore } from '../../core/services/auth.store';
import { ThemeService } from '../../core/services/theme.service';
import { Icon } from '../../shared/ui/icon';

@Component({
  selector: 'app-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, RouterLink],
  template: `
    <div class="auth-shell">
      <button
        class="btn btn-ghost btn-icon theme-corner"
        type="button"
        [attr.aria-label]="theme.isDark() ? 'Switch to light theme' : 'Switch to dark theme'"
        (click)="theme.toggle()"
      >
        <app-icon [name]="theme.isDark() ? 'sun' : 'moon'" />
      </button>

      <form class="auth-card" (submit)="submit($event)">
        <div class="brand"><span>Job<span class="x">x</span></span></div>
        <h1>Welcome back</h1>
        <p class="lede">New roles from the boards you watch, scored against your keywords.</p>

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

        <div class="field" [class.invalid]="fieldError('email')">
          <label for="email">Email</label>
          <input
            id="email"
            type="email"
            autocomplete="email"
            required
            [value]="email()"
            (input)="email.set($any($event.target).value)"
          />
          @if (fieldError('email'); as message) {
            <p class="err">{{ message }}</p>
          }
        </div>

        <div class="field" [class.invalid]="fieldError('password')">
          <label for="password">Password</label>
          <input
            id="password"
            type="password"
            autocomplete="current-password"
            required
            [value]="password()"
            (input)="password.set($any($event.target).value)"
          />
          @if (fieldError('password'); as message) {
            <p class="err">{{ message }}</p>
          }
        </div>

        <button class="btn btn-primary" type="submit" [disabled]="busy()">
          {{ busy() ? 'Signing in…' : 'Sign in' }}
        </button>

        <p class="auth-foot">
          No account yet? <a routerLink="/register">Create one</a>
        </p>
      </form>
    </div>
  `,
})
export class LoginPage {
  protected readonly theme = inject(ThemeService);
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
