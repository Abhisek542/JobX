import { WatchedCompanyResponse } from '../models/watchlist.model';
import { relTime } from './time';

export type StatusDot = 'ok' | 'warn' | 'bad' | 'idle';

export interface CompanyStatusLine {
  dot: StatusDot;
  text: string;
  /** Renders the line in the error colour. */
  bad: boolean;
  /** Only ACTIVE companies can be checked — 409 otherwise (uiux_plan.md §8). */
  canCheck: boolean;
}

/**
 * The per-company status line in the watchlist-health panel. Ported from the
 * mockup's renderWatchlist(), with one deliberate wording correction.
 *
 * CORRECTION vs the mockup: it renders a FAILED board as
 * "Refresh issue · last worked {lastFetchedAt}". Against the real backend that
 * is false — FetchScheduler.recordFailure() stamps `lastFetchedAt` on the
 * *failed* attempt too (so the manual-fetch cooldown also applies to failures).
 * So `lastFetchedAt` is the last ATTEMPT, not the last success, and the app says
 * "last tried". `lastFetchError` is deliberately not exposed by the API, so
 * there is nothing more specific we could honestly show.
 */
export function companyStatusLine(
  company: WatchedCompanyResponse,
  now: number = Date.now(),
): CompanyStatusLine {
  if (company.status === 'UNSUPPORTED') {
    return { dot: 'idle', text: 'Board has no public API', bad: false, canCheck: false };
  }
  if (company.status === 'PAUSED') {
    return { dot: 'idle', text: 'Paused', bad: false, canCheck: false };
  }
  if (company.lastFetchStatus === 'FAILED') {
    return {
      dot: 'bad',
      text: `Refresh issue · last tried ${relTime(company.lastFetchedAt, now)}`,
      bad: true,
      canCheck: true,
    };
  }
  if (company.lastFetchStatus === null) {
    return { dot: 'warn', text: 'Waiting for first check', bad: false, canCheck: true };
  }
  return {
    dot: 'ok',
    text: `Checked ${relTime(company.lastFetchedAt, now)}`,
    bad: false,
    canCheck: true,
  };
}
