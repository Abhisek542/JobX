import { Injectable, computed, inject, signal } from '@angular/core';
import { FilterProfileApi } from '../api/filter-profile.api';
import { AppError } from '../models/api-error.model';
import { FilterProfileRequest, FilterProfileResponse } from '../models/filter-profile.model';
import { FeedStore } from '../../features/dashboard/feed.store';

@Injectable({ providedIn: 'root' })
export class FilterProfileStore {
  private readonly api = inject(FilterProfileApi);
  private readonly feed = inject(FeedStore);

  private readonly profileSignal = signal<FilterProfileResponse | null>(null);
  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);
  /** True only for a real failure — a 404 is "no profile yet", not an error. */
  private readonly errorSignal = signal<AppError | null>(null);
  private readonly missingSignal = signal(false);

  /**
   * Bumped by reset() on sign-out. Every async callback captures it before the
   * request goes out and bails if it changed, so a response from the previous
   * user's session can never write into the next user's store.
   */
  private epoch = 0;

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
    const epoch = this.epoch;
    this.api.get().subscribe({
      next: (profile) => {
        if (epoch !== this.epoch) return;
        this.profileSignal.set(profile);
        this.missingSignal.set(false);
        this.loadedSignal.set(true);
        this.loadingSignal.set(false);
      },
      error: (error: AppError) => {
        if (epoch !== this.epoch) return;
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

  /** Back to the state of a fresh app boot. Called by AuthStore.clear() on sign-out. */
  reset(): void {
    this.epoch++;
    this.profileSignal.set(null);
    this.loadingSignal.set(false);
    this.loadedSignal.set(false);
    this.errorSignal.set(null);
    this.missingSignal.set(false);
  }

  /**
   * PUT upserts. Rejects with the AppError so the form can show `fieldErrors`.
   *
   * Every save rescores the whole feed server-side
   * (MatchingService.rescoreForWatcher): matches are created, refreshed and
   * deleted before the response arrives. The feed must be reloaded or the
   * dashboard keeps rendering scores from the previous keywords — including the
   * onboarding case, where the very first save is what turns an empty feed into
   * a populated one.
   */
  save(request: FilterProfileRequest): Promise<FilterProfileResponse> {
    const epoch = this.epoch;
    return new Promise((resolve, reject) => {
      this.api.save(request).subscribe({
        next: (profile) => {
          // Still settle the promise after a sign-out; just leave the store alone.
          if (epoch === this.epoch) {
            this.profileSignal.set(profile);
            this.missingSignal.set(false);
            this.loadedSignal.set(true);
            this.feed.reload();
          }
          resolve(profile);
        },
        error: (error: AppError) => reject(error),
      });
    });
  }
}
