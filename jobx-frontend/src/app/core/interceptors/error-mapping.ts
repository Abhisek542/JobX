import { HttpErrorResponse } from '@angular/common/http';
import { ApiError, AppError } from '../models/api-error.model';

/**
 * The single ApiError -> AppError mapper (frontend_constraints.md §16).
 * Pure so it can be unit-tested without HTTP; used only by error.interceptor,
 * so the mapping happens exactly once per request.
 */
export function toAppError(response: HttpErrorResponse): AppError {
  // status 0: request never got a response — offline, DNS, CORS, backend down.
  if (response.status === 0) {
    return new AppError(
      0,
      'network_unreachable',
      "Can't reach the Jobx backend. Check that it's running on localhost:8080.",
    );
  }

  const body = response.error as Partial<ApiError> | string | null;

  if (body && typeof body === 'object' && typeof body.detail === 'string') {
    return new AppError(
      typeof body.status === 'number' ? body.status : response.status,
      body.code ?? fallbackCode(response.status),
      body.detail,
      body.fieldErrors ?? {},
    );
  }

  // Not an ApiError body. Anything the backend emits should be one, so this is
  // either a proxy/gateway page or a bug — say something honest, not the raw HTML.
  return new AppError(response.status, fallbackCode(response.status), fallbackDetail(response));
}

function fallbackCode(status: number): string {
  return `http_${status}`;
}

function fallbackDetail(response: HttpErrorResponse): string {
  switch (response.status) {
    case 401:
      return 'Your session has expired. Sign in again.';
    case 403:
      return "You don't have access to that.";
    case 404:
      return 'Not found.';
    case 429:
      return 'Too many requests — wait a moment and try again.';
    case 502:
    case 503:
    case 504:
      return 'The backend is unavailable right now.';
    default:
      return response.statusText || `Request failed (${response.status}).`;
  }
}
