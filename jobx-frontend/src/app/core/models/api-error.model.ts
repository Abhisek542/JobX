/** Mirrors com.jobx.dto.ApiError — the shape of EVERY non-2xx body, including
 *  the hand-written 401 entry point and the 429 rate limiter. */
export interface ApiError {
  status: number;
  code: string;
  detail: string;
  fieldErrors?: Record<string, string>;
}

/**
 * What the app actually works with. `error.interceptor` maps `ApiError` to this
 * exactly once so no feature has to re-parse an HttpErrorResponse
 * (frontend_constraints.md §16).
 */
export class AppError extends Error {
  constructor(
    /** HTTP status. 0 means the request never reached the backend. */
    readonly status: number,
    /** Stable machine-readable slug, or a synthetic one for transport failures. */
    readonly code: string,
    /** Human-safe message — this is what toasts show. */
    readonly detail: string,
    /** Per-field messages from @Valid, keyed by field name. Drives form errors. */
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(detail);
    this.name = 'AppError';
  }

  /** True when the backend was unreachable (offline, CORS, dev server down). */
  get isNetworkError(): boolean {
    return this.status === 0;
  }
}
