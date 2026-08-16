/** Mirrors com.jobx.dto.MatchResponse. Read from backend source, not the docs. */
export type MatchStatus = 'NEW' | 'SEEN' | 'APPLIED' | 'DISMISSED';

export interface MatchResponse {
  id: string;
  jobId: string;
  jobTitle: string;
  companyName: string;
  applyUrl: string;
  score: number;
  matchedKeywords: string[];
  status: MatchStatus;
  createdAt: string;
}

/** PATCH /matches/{id} body. */
export interface UpdateMatchStatusRequest {
  status: MatchStatus;
}
