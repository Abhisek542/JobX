import { AtsPlatform } from './watchlist.model';

/** Mirrors com.jobx.dto.MatchResponse. Read from backend source, not the docs. */
export type MatchStatus = 'NEW' | 'SEEN' | 'APPLIED' | 'DISMISSED';

export interface MatchResponse {
  id: string;
  /**
   * Null once the posting has been expired and swept away by the backend's
   * six-day retention TTL. The match itself survives when the user saved or
   * applied to it — see `expiredAt`.
   */
  jobId: string | null;
  jobTitle: string;
  /**
   * The board this role came from. Grouping and any join to the watchlist key
   * on this id, never on companyName — that is a display string, and two
   * distinct boards can carry the same one. Present even when the posting has
   * expired: it is a denormalized FK on the match, so it outlives jobId.
   */
  companyId: string;
  companyName: string;
  applyUrl: string;
  score: number;
  matchedKeywords: string[];
  status: MatchStatus;
  createdAt: string;
  /**
   * When Jobx dropped the underlying posting; null while it is still live.
   * A card with this set must say so rather than presenting a dead listing as
   * if it were still open — the apply URL very likely 404s now.
   */
  expiredAt: string | null;
  /**
   * Display-only labels copied from the ATS posting (backend V8). Each is null
   * when the board doesn't publish it, and all three are null once the posting
   * has expired. Never scored. Render through `jobLabels()`, which drops nulls.
   */
  department: string | null;
  team: string | null;
  employmentType: string | null;
  /**
   * The board's platform. 'CUSTOM' means the role was read off the company's
   * own careers page by the crawler, so the card shows a "Crawled" badge:
   * crawled details may be incomplete. Survives the posting's expiry.
   */
  atsPlatform: AtsPlatform;
}

/** True for a role read off a careers page by the crawler rather than an ATS API. */
export function isCrawled(match: Pick<MatchResponse, 'atsPlatform'>): boolean {
  return match.atsPlatform === 'CUSTOM';
}

export const CRAWLED_TOOLTIP = "Read from the company's careers page. Details may be incomplete.";

/** PATCH /matches/{id} body. */
export interface UpdateMatchStatusRequest {
  status: MatchStatus;
}
