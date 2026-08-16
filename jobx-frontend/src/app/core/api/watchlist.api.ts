import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import {
  CompanyStatus,
  ManualFetchResponse,
  WatchedCompanyRequest,
  WatchedCompanyResponse,
} from '../models/watchlist.model';

@Injectable({ providedIn: 'root' })
export class WatchlistApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  list(): Observable<WatchedCompanyResponse[]> {
    return this.http.get<WatchedCompanyResponse[]>(`${this.base}/watchlist`);
  }

  /** 201 on success · 409 when this ATS + token is already watched. */
  add(request: WatchedCompanyRequest): Observable<WatchedCompanyResponse> {
    return this.http.post<WatchedCompanyResponse>(`${this.base}/watchlist`, request);
  }

  updateStatus(id: string, status: CompanyStatus): Observable<WatchedCompanyResponse> {
    return this.http.patch<WatchedCompanyResponse>(`${this.base}/watchlist/${id}`, { status });
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/watchlist/${id}`);
  }

  /**
   * Manual "Check now". Four distinct failures the UI must keep distinct:
   * 404 not owned · 409 not ACTIVE · 429 cooldown · 502 board unreachable.
   * The 502 must never collapse into "no new roles" (uiux_plan.md §8).
   */
  fetchNow(id: string): Observable<ManualFetchResponse> {
    return this.http.post<ManualFetchResponse>(`${this.base}/watchlist/${id}/fetch`, {});
  }
}
