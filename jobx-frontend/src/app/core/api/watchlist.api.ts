import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import {
  CompanySearchResponse,
  CompanyStatus,
  ManualFetchResponse,
  ResolveResponse,
  UnsupportedBoardReportRequest,
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

  /**
   * Boards Jobx already knows, for the typeahead. No network call happens on the
   * backend either — it searches the companies table, which since V4 is a
   * registry of boards somebody has already proven real.
   */
  searchCompanies(query: string): Observable<CompanySearchResponse[]> {
    return this.http.get<CompanySearchResponse[]>(`${this.base}/companies/search`, {
      params: { q: query },
    });
  }

  /**
   * Works out the ATS board behind a company name, a website, or a careers link.
   *
   * Read-only: this proposes boards with live job titles attached, and `add()`
   * below is what actually starts watching one. An empty candidate list is a
   * normal 200 — plenty of companies are on a portal with no public API. A 400
   * means the input itself was refused (`invalid_url`), which is the one failure
   * the user can act on.
   */
  resolve(query: string): Observable<ResolveResponse> {
    return this.http.post<ResolveResponse>(`${this.base}/watchlist/resolve`, { query });
  }

  /**
   * Records a company Jobx could not resolve, so the answer to "which ATS do we
   * support next" comes from demand rather than guesswork. Fire-and-forget.
   */
  reportUnsupported(request: UnsupportedBoardReportRequest): Observable<void> {
    return this.http.post<void>(`${this.base}/watchlist/unsupported`, request);
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
