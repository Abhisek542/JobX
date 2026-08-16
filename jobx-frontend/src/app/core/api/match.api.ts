import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import { MatchResponse, MatchStatus } from '../models/match.model';

@Injectable({ providedIn: 'root' })
export class MatchApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  /**
   * GET /matches — takes no query parameters and returns everything the user
   * has, DISMISSED included, unpaginated. Filtering, search, sorting and paging
   * are therefore client-side (frontend_constraints.md §10, §11).
   */
  list(): Observable<MatchResponse[]> {
    return this.http.get<MatchResponse[]>(`${this.base}/matches`);
  }

  /** PATCH /matches/{id} — 404 is also returned for a match owned by someone else. */
  updateStatus(id: string, status: MatchStatus): Observable<MatchResponse> {
    return this.http.patch<MatchResponse>(`${this.base}/matches/${id}`, { status });
  }
}
