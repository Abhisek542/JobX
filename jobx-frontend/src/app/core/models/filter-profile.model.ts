/** Mirrors com.jobx.dto.FilterProfileResponse / FilterProfileRequest.
 *  There is no `roles` field — keywords are the whole thing
 *  (frontend_constraints.md §9). */
export interface FilterProfileResponse {
  id: string;
  keywords: string[];
  excludeWords: string[];
  expMin: number | null;
  expMax: number | null;
  updatedAt: string;
}

export interface FilterProfileRequest {
  keywords: string[];
  excludeWords: string[];
  expMin: number | null;
  expMax: number | null;
}
