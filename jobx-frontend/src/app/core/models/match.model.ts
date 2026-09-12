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
}

/** PATCH /matches/{id} body. */
export interface UpdateMatchStatusRequest {
  status: MatchStatus;
}
