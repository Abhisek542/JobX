import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { FeedStore } from '../../features/dashboard/feed.store';
import { authInterceptor } from '../interceptors/auth.interceptor';
import { errorInterceptor } from '../interceptors/error.interceptor';
import { MatchResponse } from '../models/match.model';
import { FilterProfileResponse } from '../models/filter-profile.model';
import { WatchedCompanyResponse } from '../models/watchlist.model';
import { AuthStore } from './auth.store';
import { FilterProfileStore } from './filter-profile.store';
import { ToastService } from './toast.service';
import { UiStore } from './ui.store';
import { WatchlistStore } from './watchlist.store';

const API = 'http://localhost:8080';

const MATCH = {
  id: 'm1',
  companyId: 'c1',
  companyName: 'Acme',
  jobTitle: 'Engineer',
  status: 'NEW',
  score: 80,
} as unknown as MatchResponse;

const COMPANY = {
  id: 'w1',
  companyId: 'c1',
  companyName: 'Acme',
  status: 'ACTIVE',
  lastFetchStatus: 'SUCCESS',
} as unknown as WatchedCompanyResponse;

const PROFILE = { keywords: ['java'], excludeWords: [] } as unknown as FilterProfileResponse;

/**
 * BUG_REPORT.md #1: signing out must leave nothing of the previous user behind
 * in the root stores, or the next user on the same tab sees their data.
 */
describe('sign-out resets every per-user store', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.setItem(
      'jobx-session',
      JSON.stringify({
        token: 'token-a',
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        userId: 'user-a',
        email: 'a@example.com',
      }),
    );
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        // errorInterceptor navigates to /login on a 401; give it somewhere to land.
        provideRouter([{ path: 'login', children: [] }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  function loadEverything() {
    const feed = TestBed.inject(FeedStore);
    const watchlist = TestBed.inject(WatchlistStore);
    const profile = TestBed.inject(FilterProfileStore);
    feed.load();
    watchlist.load();
    profile.load();
    http.expectOne(`${API}/matches`).flush([MATCH]);
    http.expectOne(`${API}/watchlist`).flush([COMPANY]);
    http.expectOne(`${API}/profile/filter`).flush(PROFILE);
    return { feed, watchlist, profile };
  }

  it('clears loaded data, view state, toasts and overlays', () => {
    const auth = TestBed.inject(AuthStore);
    const toasts = TestBed.inject(ToastService);
    const ui = TestBed.inject(UiStore);
    const { feed, watchlist, profile } = loadEverything();

    feed.setQuery('engineer');
    feed.setStatusFilter('NEW');
    feed.setGrouped(true);
    feed.toggleCollapsed('c1');
    feed.setPage(3);
    toasts.show('Dismissed · Engineer', { undo: () => undefined });
    ui.openAddCompany();
    expect(feed.matches()).toHaveLength(1);

    auth.clear();

    expect(auth.isAuthenticated()).toBe(false);
    expect(feed.matches()).toEqual([]);
    expect(feed.loaded()).toBe(false);
    expect(feed.query()).toBe('');
    expect(feed.status()).toBe('ALL');
    expect(feed.grouped()).toBe(false);
    expect(feed.collapsed().size).toBe(0);
    expect(feed.page()).toBe(1);
    expect(watchlist.companies()).toEqual([]);
    expect(watchlist.loaded()).toBe(false);
    expect(profile.profile()).toBeNull();
    expect(profile.loaded()).toBe(false);
    expect(profile.needsOnboarding()).toBe(false);
    expect(toasts.toasts()).toEqual([]);
    expect(ui.addCompanyOpen()).toBe(false);
  });

  it('lets the next session load fresh data instead of early-returning', () => {
    const auth = TestBed.inject(AuthStore);
    const { feed, watchlist, profile } = loadEverything();

    auth.clear();
    feed.load();
    watchlist.load();
    profile.load();

    http.expectOne(`${API}/matches`).flush([]);
    http.expectOne(`${API}/watchlist`).flush([]);
    http.expectOne(`${API}/profile/filter`).flush(PROFILE);
    expect(feed.matches()).toEqual([]);
    expect(watchlist.companies()).toEqual([]);
  });

  it('ignores a response from the previous session that lands after sign-out', () => {
    const auth = TestBed.inject(AuthStore);
    const feed = TestBed.inject(FeedStore);

    feed.load();
    const inFlight = http.expectOne(`${API}/matches`);
    auth.clear();
    inFlight.flush([MATCH]);

    expect(feed.matches()).toEqual([]);
    expect(feed.loaded()).toBe(false);
    expect(feed.loading()).toBe(false);
  });

  it('resets on the 401 path without writing an error into the fresh store', () => {
    const auth = TestBed.inject(AuthStore);
    const feed = TestBed.inject(FeedStore);

    feed.load();
    http
      .expectOne(`${API}/matches`)
      .flush(
        { status: 401, code: 'UNAUTHORIZED', detail: 'Token expired' },
        { status: 401, statusText: 'Unauthorized' },
      );

    expect(auth.isAuthenticated()).toBe(false);
    expect(feed.error()).toBeNull();
    expect(feed.loading()).toBe(false);
    expect(feed.loaded()).toBe(false);
  });

  it('keeps the collapsed-sidebar device preference', () => {
    const auth = TestBed.inject(AuthStore);
    const ui = TestBed.inject(UiStore);
    ui.navCollapsed.set(true);

    auth.clear();

    expect(ui.navCollapsed()).toBe(true);
  });
});
