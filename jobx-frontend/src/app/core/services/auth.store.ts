import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AuthApi } from '../api/auth.api';
import { AuthResponse, LoginRequest, RegisterRequest, Session } from '../models/auth.model';
import { displayName, initials } from '../util/identity';
import { readStorage, removeStorage, writeStorage } from '../util/storage';

const SESSION_KEY = 'jobx-session';

/**
 * The whole auth surface: one signal holding the session, persisted to
 * localStorage. There is no refresh token in v1 (accepted trade-off,
 * CLAUDE.md), so an expired token is terminal — we check `expiresAt` on boot
 * rather than letting the first request 401 (uiux_plan.md §6).
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly api = inject(AuthApi);
  private readonly session = signal<Session | null>(restore());

  readonly token = computed(() => this.session()?.token ?? null);
  readonly email = computed(() => this.session()?.email ?? '');
  readonly userId = computed(() => this.session()?.userId ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);

  /** Derived identity — the backend has no display name (frontend_constraints §5). */
  readonly name = computed(() => displayName(this.email()));
  readonly initials = computed(() => initials(this.email()));

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.api.login(request).pipe(tap((response) => this.accept(response)));
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.api.register(request).pipe(tap((response) => this.accept(response)));
  }

  /** Local sign-out. Nothing to revoke server-side — the JWT is stateless. */
  clear(): void {
    this.session.set(null);
    removeStorage(SESSION_KEY);
  }

  private accept(response: AuthResponse): void {
    const session: Session = {
      token: response.token,
      expiresAt: response.expiresAt,
      userId: response.userId,
      email: response.email,
    };
    this.session.set(session);
    writeStorage(SESSION_KEY, JSON.stringify(session));
  }
}

function restore(): Session | null {
  const raw = readStorage(SESSION_KEY);
  if (!raw) return null;

  try {
    const session = JSON.parse(raw) as Session;
    if (!session?.token || !session.expiresAt) return null;

    // Already expired: drop it now so the app boots straight to /login instead
    // of flashing a dashboard whose first request is guaranteed to 401.
    if (new Date(session.expiresAt).getTime() <= Date.now()) {
      removeStorage(SESSION_KEY);
      return null;
    }
    return session;
  } catch {
    removeStorage(SESSION_KEY);
    return null;
  }
}
