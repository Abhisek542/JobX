import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AppError } from '../../core/models/api-error.model';
import { AuthStore } from '../../core/services/auth.store';
import { ThemeService } from '../../core/services/theme.service';
import { Icon } from '../../shared/ui/icon';

/** Password rule mirrors RegisterRequest's @Size(min = 8). */
const MIN_PASSWORD = 8;

@Component({
  selector: 'app-register-page',
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
        <h1>Create your account</h1>
        <p class="lede">Watch company boards directly. No aggregators, no auto-apply.</p>

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
            autocomplete="new-password"
            required
            [value]="password()"
            (input)="password.set($any($event.target).value)"
          />
          <p class="hint">At least {{ minPassword }} characters.</p>
          @if (fieldError('password'); as message) {
            <p class="err">{{ message }}</p>
          }
        </div>

        <button class="btn btn-primary" type="submit" [disabled]="busy()">
          {{ busy() ? 'Creating account…' : 'Create account' }}
        </button>

        <p class="auth-foot">Already have an account? <a routerLink="/login">Sign in</a></p>
      </form>
    </div>
  `,
})
export class RegisterPage {
  protected readonly theme = inject(ThemeService);
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);

  protected readonly minPassword = MIN_PASSWORD;
  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fieldErrors = signal<Record<string, string>>({});

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
      next: () => void this.router.navigate(['/dashboard']),
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
