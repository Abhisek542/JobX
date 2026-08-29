/** Mirrors com.jobx.dto.WatchedCompanyResponse / WatchedCompanyRequest. */
export type AtsPlatform =
  | 'GREENHOUSE'
  | 'LEVER'
  | 'ASHBY'
  | 'WORKABLE'
  | 'SMARTRECRUITERS'
  | 'UNSUPPORTED';
export type CompanyStatus = 'ACTIVE' | 'PAUSED' | 'UNSUPPORTED';
export type FetchStatus = 'SUCCESS' | 'FAILED';

export interface WatchedCompanyResponse {
  id: string;
  companyName: string;
  atsPlatform: AtsPlatform;
  boardToken: string;
  status: CompanyStatus;
  lastFetchedAt: string | null;
  /** null = never checked. Added to the backend 2026-08-15. */
  lastFetchStatus: FetchStatus | null;
  createdAt: string;
}

export interface WatchedCompanyRequest {
  companyName: string;
  atsPlatform: AtsPlatform;
  boardToken: string;
}

export interface UpdateWatchedCompanyStatusRequest {
  status: CompanyStatus;
}

export interface ManualFetchResponse {
  companyId: string;
  companyName: string;
  checkedAt: string;
  newJobs: number;
  newMatches: number;
}

/** The platforms with a real public API. UNSUPPORTED is never offered. */
export const SUPPORTED_PLATFORMS: readonly AtsPlatform[] = [
  'GREENHOUSE',
  'LEVER',
  'ASHBY',
  'WORKABLE',
  'SMARTRECRUITERS',
];

export const PLATFORM_LABEL: Record<AtsPlatform, string> = {
  GREENHOUSE: 'Greenhouse',
  LEVER: 'Lever',
  ASHBY: 'Ashby',
  WORKABLE: 'Workable',
  SMARTRECRUITERS: 'SmartRecruiters',
  UNSUPPORTED: 'Unsupported board',
};

/** Board-token hints, straight from the mockup's TOKEN_HINTS. */
export const TOKEN_HINTS: Record<string, { placeholder: string; url: string; token: string }> = {
  GREENHOUSE: { placeholder: 'razorpay', url: 'boards.greenhouse.io/', token: 'razorpay' },
  LEVER: { placeholder: 'fampay', url: 'jobs.lever.co/', token: 'fampay' },
  ASHBY: { placeholder: 'sprinto', url: 'jobs.ashbyhq.com/', token: 'sprinto' },
  WORKABLE: { placeholder: 'apna', url: 'apply.workable.com/', token: 'apna' },
  // SmartRecruiters company IDs are usually upper-case and unspaced (PHONEPELIMITED).
  SMARTRECRUITERS: {
    placeholder: 'PHONEPELIMITED',
    url: 'jobs.smartrecruiters.com/',
    token: 'PHONEPELIMITED',
  },
};
