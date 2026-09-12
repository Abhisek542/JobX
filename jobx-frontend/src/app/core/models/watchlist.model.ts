/** Mirrors com.jobx.dto.WatchedCompanyResponse / WatchedCompanyRequest. */
export type AtsPlatform =
  | 'GREENHOUSE'
  | 'LEVER'
  | 'ASHBY'
  | 'WORKABLE'
  | 'SMARTRECRUITERS'
  | 'UNSUPPORTED';
export type CompanyStatus = 'ACTIVE' | 'PAUSED' | 'UNSUPPORTED';
export type FetchStatus = 'SUCCESS' | 'FAILED';

export interface WatchedCompanyResponse {
  /** The watch row — what PATCH/DELETE /watchlist/{id} addresses. */
  id: string;
  /**
   * The shared board behind the watch row. This is NOT `id`, and it is the
   * only thing that joins a watch to a MatchResponse — matching them by
   * display name would be a guess.
   */
  companyId: string;
  companyName: string;
  atsPlatform: AtsPlatform;
  boardToken: string;
  status: CompanyStatus;
  lastFetchedAt: string | null;
  /** null = never checked. Added to the backend 2026-08-15. */
  lastFetchStatus: FetchStatus | null;
  createdAt: string;
}

export interface WatchedCompanyRequest {
  companyName: string;
  atsPlatform: AtsPlatform;
  boardToken: string;
}

export interface UpdateWatchedCompanyStatusRequest {
  status: CompanyStatus;
}

export interface ManualFetchResponse {
  companyId: string;
  companyName: string;
  checkedAt: string;
  newJobs: number;
  newMatches: number;
}

/** The platforms with a real public API. UNSUPPORTED is never offered. */
export const SUPPORTED_PLATFORMS: readonly AtsPlatform[] = [
  'GREENHOUSE',
  'LEVER',
  'ASHBY',
  'WORKABLE',
  'SMARTRECRUITERS',
];

export const PLATFORM_LABEL: Record<AtsPlatform, string> = {
  GREENHOUSE: 'Greenhouse',
  LEVER: 'Lever',
  ASHBY: 'Ashby',
  WORKABLE: 'Workable',
  SMARTRECRUITERS: 'SmartRecruiters',
  UNSUPPORTED: 'Unsupported board',
};

/** Board-token hints, straight from the mockup's TOKEN_HINTS. */
export const TOKEN_HINTS: Record<string, { placeholder: string; url: string; token: string }> = {
  GREENHOUSE: { placeholder: 'razorpay', url: 'boards.greenhouse.io/', token: 'razorpay' },
  LEVER: { placeholder: 'fampay', url: 'jobs.lever.co/', token: 'fampay' },
  ASHBY: { placeholder: 'sprinto', url: 'jobs.ashbyhq.com/', token: 'sprinto' },
  WORKABLE: { placeholder: 'apna', url: 'apply.workable.com/', token: 'apna' },
  // SmartRecruiters company IDs are usually upper-case and unspaced (PHONEPELIMITED).
  SMARTRECRUITERS: {
    placeholder: 'PHONEPELIMITED',
    url: 'jobs.smartrecruiters.com/',
    token: 'PHONEPELIMITED',
  },
};

// --------------------------------------------------------------------------
// Add-company resolution
//
// A user knows their employer's name and, with effort, its careers URL. Nobody
// knows an "ATS platform" or a "board token" — and the token is often not the
// company name at all (Razorpay's is razorpaysoftwareprivatelimited). These
// types back the flow that works the board out for them.
// --------------------------------------------------------------------------

/** How a candidate board was found. Mirrors com.jobx.resolve.CompanyResolver.Source. */
export type ResolveSource = 'CATALOG' | 'URL' | 'SNIFF' | 'PROBE';

/**
 * One board Jobx believes matches what the user typed, with its evidence.
 *
 * `jobCount` and `sampleTitles` are not decoration. Everything except a CATALOG
 * hit is derived — read off a careers page, or guessed from a slug and then
 * checked — so the card shows real roles and the user confirms. A candidate is
 * never offered with `jobCount: 0`: two platforms answer a token that was never
 * theirs with a cheerful empty 200, and Workable even echoes back the right
 * company name, so a live posting is the only trustworthy signal.
 */
export interface ResolvedBoardResponse {
  source: ResolveSource;
  atsPlatform: AtsPlatform;
  boardToken: string;
  companyName: string;
  boardUrl: string;
  jobCount: number;
  sampleTitles: string[];
  alreadyWatched: boolean;
}

export interface ResolveResponse {
  candidates: ResolvedBoardResponse[];
  /**
   * Set when the careers page named an ATS but never named the board — a
   * client-rendered board, or a host that only appears in a CSP header. Lets the
   * dead end say something more useful than "not found".
   */
  platformHint: AtsPlatform | null;
}

export interface ResolveRequest {
  query: string;
}

/** A board Jobx already knows — offered as the user types, with no network call. */
export interface CompanySearchResponse {
  companyId: string;
  companyName: string;
  atsPlatform: AtsPlatform;
  boardToken: string;
  alreadyWatched: boolean;
}

export interface UnsupportedBoardReportRequest {
  query: string;
  platformHint: AtsPlatform | null;
}

/** Explains where a candidate came from, in the user's terms rather than ours. */
export const SOURCE_LABEL: Record<ResolveSource, string> = {
  CATALOG: 'Already tracked by Jobx',
  URL: 'From the link you pasted',
  SNIFF: 'Found on their careers page',
  PROBE: 'Matched to their job board',
};
