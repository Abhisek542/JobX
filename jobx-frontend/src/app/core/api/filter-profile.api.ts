import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-base';
import { FilterProfileRequest, FilterProfileResponse } from '../models/filter-profile.model';

@Injectable({ providedIn: 'root' })
export class FilterProfileApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  /** 404 here means "no profile yet" → onboarding, NOT an error (uiux_plan.md §8). */
  get(): Observable<FilterProfileResponse> {
    return this.http.get<FilterProfileResponse>(`${this.base}/profile/filter`);
  }

  /** PUT upserts. 400 when no keyword survives normalization, or expMin > expMax. */
  save(request: FilterProfileRequest): Observable<FilterProfileResponse> {
    return this.http.put<FilterProfileResponse>(`${this.base}/profile/filter`, request);
  }

  remove(): Observable<void> {
    return this.http.delete<void>(`${this.base}/profile/filter`);
  }
}
