import { Injectable, computed, inject, signal } from '@angular/core';
import { FilterProfileApi } from '../api/filter-profile.api';
import { AppError } from '../models/api-error.model';
import { FilterProfileRequest, FilterProfileResponse } from '../models/filter-profile.model';

@Injectable({ providedIn: 'root' })
export class FilterProfileStore {
  private readonly api = inject(FilterProfileApi);

  private readonly profileSignal = signal<FilterProfileResponse | null>(null);
  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);
  /** True only for a real failure — a 404 is "no profile yet", not an error. */
  private readonly errorSignal = signal<AppError | null>(null);
  private readonly missingSignal = signal(false);

  readonly profile = this.profileSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly loaded = this.loadedSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();

  /**
   * GET /profile/filter → 404 means the user has never set keywords. That is
   * onboarding, not a failure — no error toast (uiux_plan.md §8).
   */
  readonly needsOnboarding = computed(() => this.loadedSignal() && this.missingSignal());

  readonly keywords = computed(() => this.profileSignal()?.keywords ?? []);
  readonly excludeWords = computed(() => this.profileSignal()?.excludeWords ?? []);

  readonly experienceLabel = computed(() => {
    const profile = this.profileSignal();
    if (!profile || (profile.expMin == null && profile.expMax == null)) return 'Any experience';
    const min = profile.expMin ?? 0;
    const max = profile.expMax == null ? '∞' : profile.expMax;
    return `${min}–${max} years`;
  });

  load(options: { force?: boolean } = {}): void {
    if (this.loadingSignal()) return;
    if (this.loadedSignal() && !options.force) return;

    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    this.api.get().subscribe({
      next: (profile) => {
        this.profileSignal.set(profile);
        this.missingSignal.set(false);
        this.loadedSignal.set(true);
        this.loadingSignal.set(false);
      },
      error: (error: AppError) => {
        if (error.status === 404) {
          this.profileSignal.set(null);
          this.missingSignal.set(true);
        } else {
          this.errorSignal.set(error);
        }
        this.loadedSignal.set(true);
        this.loadingSignal.set(false);
      },
    });
  }

  /** PUT upserts. Rejects with the AppError so the form can show `fieldErrors`. */
  save(request: FilterProfileRequest): Promise<FilterProfileResponse> {
    return new Promise((resolve, reject) => {
      this.api.save(request).subscribe({
        next: (profile) => {
          this.profileSignal.set(profile);
          this.missingSignal.set(false);
          this.loadedSignal.set(true);
          resolve(profile);
        },
        error: (error: AppError) => reject(error),
      });
    });
  }
}
