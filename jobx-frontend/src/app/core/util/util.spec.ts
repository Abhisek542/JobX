import { describe, expect, it } from 'vitest';
import { WatchedCompanyResponse } from '../models/watchlist.model';
import { displayName, initials } from './identity';
import { logoText } from './logo';
import { safeNext } from './redirect';
import { normalizeList, parseList } from './text-lists';
import { relTime } from './time';
import { looksLikeUrl } from './url';
import { companyStatusLine, moveBannerCopy } from './watchlist-status';

describe('identity (derived from the email local-part)', () => {
  it('derives a display name', () => {
    expect(displayName('arjun.mehta@example.com')).toBe('Arjun');
    expect(displayName('priya_r@example.com')).toBe('Priya');
    expect(displayName('sam@example.com')).toBe('Sam');
  });

  it('derives up to two initials', () => {
    expect(initials('arjun.mehta@example.com')).toBe('AM');
    expect(initials('sam@example.com')).toBe('S');
  });

  it('never throws on a malformed email', () => {
    expect(displayName('')).toBe('You');
    expect(initials('')).toBe('?');
  });
});

describe('relTime', () => {
  const now = Date.parse('2026-08-15T12:00:00Z');
  const ago = (mins: number) => new Date(now - mins * 60_000).toISOString();

  it('renders the mockup buckets', () => {
    expect(relTime(ago(0), now)).toBe('just now');
    expect(relTime(ago(22), now)).toBe('22m ago');
    expect(relTime(ago(190), now)).toBe('3h ago');
    expect(relTime(ago(60 * 24), now)).toBe('yesterday');
    expect(relTime(ago(60 * 24 * 4), now)).toBe('4d ago');
  });

  it('handles a null lastFetchedAt without inventing a time', () => {
    expect(relTime(null, now)).toBe('never');
    expect(relTime('not-a-date', now)).toBe('unknown');
  });
});

describe('normalizeList (mirrors the backend TextLists)', () => {
  it('trims, drops blanks and dedupes case-insensitively, keeping first casing', () => {
    expect(normalizeList([' Java ', 'java', '', '  ', 'Spring Boot'])).toEqual([
      'Java',
      'Spring Boot',
    ]);
  });

  it('parses a comma-separated field the same way', () => {
    expect(parseList('Java, java , Kafka,,  ')).toEqual(['Java', 'Kafka']);
    expect(parseList('')).toEqual([]);
  });
});

describe('safeNext (post-auth ?next= target)', () => {
  it('keeps a plain in-app path, query string included', () => {
    expect(safeNext('/watchlist')).toBe('/watchlist');
    expect(safeNext('/dashboard?page=3')).toBe('/dashboard?page=3');
  });

  it('falls back to /dashboard for anything off-app or malformed', () => {
    for (const raw of [
      null,
      undefined,
      '',
      'watchlist',
      'https://evil.com',
      '//evil.com',
      '/\\evil.com',
      'javascript:alert(1)',
      '/watch\nlist',
    ]) {
      expect(safeNext(raw)).toBe('/dashboard');
    }
  });

  it('never targets an auth page, which guestGuard would bounce', () => {
    expect(safeNext('/login')).toBe('/dashboard');
    expect(safeNext('/login?next=/x')).toBe('/dashboard');
    expect(safeNext('/register#top')).toBe('/dashboard');
  });
});

describe('logoText', () => {
  it('uses two words when present, otherwise the first two characters', () => {
    expect(logoText('Razorpay')).toBe('RA');
    expect(logoText('Fam Pay')).toBe('FP');
    expect(logoText('')).toBe('?');
  });
});

describe('companyStatusLine', () => {
  const now = Date.parse('2026-08-15T12:00:00Z');
  const company = (over: Partial<WatchedCompanyResponse>): WatchedCompanyResponse => ({
    id: 'w1',
    companyId: 'c1',
    companyName: 'Razorpay',
    atsPlatform: 'GREENHOUSE',
    boardToken: 'razorpay',
    status: 'ACTIVE',
    lastFetchedAt: new Date(now - 22 * 60_000).toISOString(),
    lastFetchStatus: 'SUCCESS',
    createdAt: '2026-07-01T00:00:00Z',
    movedTo: null,
    ...over,
  });

  it('reports a healthy board with its last check time', () => {
    const line = companyStatusLine(company({}), now);
    expect(line).toMatchObject({ dot: 'ok', bad: false, canCheck: true });
    expect(line.text).toBe('Checked 22m ago');
  });

  it('distinguishes "never checked" from "checked, nothing found"', () => {
    const line = companyStatusLine(
      company({ lastFetchedAt: null, lastFetchStatus: null }),
      now,
    );
    expect(line).toMatchObject({ dot: 'warn', text: 'Waiting for first check', canCheck: true });
  });

  it('says "last tried", not "last worked", for a failed board', () => {
    // The backend stamps lastFetchedAt on failures too, so calling it a last
    // success would be false.
    const line = companyStatusLine(company({ lastFetchStatus: 'FAILED' }), now);
    expect(line.dot).toBe('bad');
    expect(line.bad).toBe(true);
    expect(line.text).toBe('Refresh issue · last tried 22m ago');
    expect(line.canCheck).toBe(true);
  });

  it('offers no check for paused or unsupported boards (the backend 409s)', () => {
    expect(companyStatusLine(company({ status: 'PAUSED' }), now)).toMatchObject({
      dot: 'idle',
      text: 'Paused',
      canCheck: false,
    });
    expect(
      companyStatusLine(company({ status: 'UNSUPPORTED', atsPlatform: 'UNSUPPORTED' }), now),
    ).toMatchObject({ dot: 'idle', text: 'Board has no public API', canCheck: false });
  });
});

describe('looksLikeUrl (mirrors CompanyResolver.looksLikeUrl)', () => {
  it('treats company names as names, even with a dot', () => {
    expect(looksLikeUrl('Razorpay')).toBe(false);
    expect(looksLikeUrl('Acme Inc.')).toBe(false);
    expect(looksLikeUrl('  ')).toBe(false);
    expect(looksLikeUrl('')).toBe(false);
  });

  it('accepts hosts with or without a scheme or path', () => {
    expect(looksLikeUrl('razorpay.com')).toBe(true);
    expect(looksLikeUrl('razorpay.com/careers')).toBe(true);
    expect(looksLikeUrl('https://jobs.lever.co/fampay')).toBe(true);
    expect(looksLikeUrl('careers.acme.co.in:8443/jobs')).toBe(true);
  });

  it('counts an explicit scheme as a URL, even one the server will refuse', () => {
    expect(looksLikeUrl('file:///etc/passwd')).toBe(true);
  });
});

describe('moveBannerCopy', () => {
  const company = {
    id: 'w1',
    companyId: 'c1',
    companyName: 'PhonePe',
    atsPlatform: 'GREENHOUSE',
    boardToken: 'phonepe',
    status: 'ACTIVE',
    lastFetchedAt: null,
    lastFetchStatus: 'FAILED',
    createdAt: '2026-07-01T00:00:00Z',
    movedTo: null,
  } as WatchedCompanyResponse;

  it('asks about a move away from a failing board', () => {
    const copy = moveBannerCopy(company, {
      atsPlatform: 'SMARTRECRUITERS',
      boardToken: 'PHONEPELIMITED',
      boardUrl: 'https://jobs.smartrecruiters.com/PHONEPELIMITED',
      reason: 'FAILING',
    });
    expect(copy).toBe(
      'PhonePe seems to have moved to SmartRecruiters. Switch this watch to their new board?',
    );
  });

  it('offers the direct feed when a working careers page gained an ATS', () => {
    const copy = moveBannerCopy(company, {
      atsPlatform: 'GREENHOUSE',
      boardToken: 'phonepe',
      boardUrl: null,
      reason: 'NATIVE_AVAILABLE',
    });
    expect(copy).toContain("PhonePe's careers page now uses Greenhouse");
  });
});
