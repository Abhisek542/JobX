import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AuthApi } from '../api/auth.api';
import { AuthResponse, LoginRequest, RegisterRequest, Session } from '../models/auth.model';
import { FeedStore } from '../../features/dashboard/feed.store';
import { displayName, initials } from '../util/identity';
import { readStorage, removeStorage, writeStorage } from '../util/storage';
import { FilterProfileStore } from './filter-profile.store';
import { ToastService } from './toast.service';
import { UiStore } from './ui.store';
import { WatchlistStore } from './watchlist.store';

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

  // Per-user state that must not outlive the session. None of these inject
  // AuthStore, and the interceptors only inject it at request time, so there is
  // no construction-time cycle.
  private readonly feed = inject(FeedStore);
  private readonly watchlist = inject(WatchlistStore);
  private readonly profile = inject(FilterProfileStore);
  private readonly toasts = inject(ToastService);
  private readonly ui = inject(UiStore);

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

  /**
   * Ends the whole client session: the token, and every root store holding the
   * user's data. Both sign-out paths come through here (the sidebar button and
   * the 401 handler in errorInterceptor). The stores are root singletons whose
   * load() is a no-op once loaded, so without the resets the next user to sign
   * in on this tab would be shown this user's feed, watchlist and profile.
   *
   * Nothing to revoke server-side — the JWT is stateless.
   */
  clear(): void {
    this.session.set(null);
    removeStorage(SESSION_KEY);
    this.feed.reset();
    this.watchlist.reset();
    this.profile.reset();
    this.toasts.clear();
    this.ui.reset();
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
