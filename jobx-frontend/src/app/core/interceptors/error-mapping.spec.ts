import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { toAppError } from './error-mapping';

/** One mapper, used everywhere — so it has to survive every shape the backend
 *  and the network can produce (frontend_constraints.md §16). */
describe('toAppError', () => {
  it('maps a real ApiError body, fieldErrors included', () => {
    const error = toAppError(
      new HttpErrorResponse({
        status: 400,
        error: {
          status: 400,
          code: 'validation_failed',
          detail: 'expMin must be <= expMax',
          fieldErrors: { expMin: 'must be >= 0' },
        },
      }),
    );

    expect(error.status).toBe(400);
    expect(error.code).toBe('validation_failed');
    expect(error.detail).toBe('expMin must be <= expMax');
    expect(error.fieldErrors['expMin']).toBe('must be >= 0');
    expect(error.isNetworkError).toBe(false);
  });

  it('defaults fieldErrors to an empty object so forms can index it safely', () => {
    const error = toAppError(
      new HttpErrorResponse({
        status: 409,
        error: { status: 409, code: 'conflict', detail: 'email already registered' },
      }),
    );
    expect(error.fieldErrors).toEqual({});
  });

  it('reports a status-0 response as an unreachable backend, not a 0 error', () => {
    const error = toAppError(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }));
    expect(error.isNetworkError).toBe(true);
    expect(error.code).toBe('network_unreachable');
    expect(error.detail).toContain('localhost:8080');
  });

  it('falls back to honest copy when the body is not an ApiError', () => {
    const html = toAppError(
      new HttpErrorResponse({ status: 502, error: '<html>Bad Gateway</html>' }),
    );
    expect(html.status).toBe(502);
    expect(html.code).toBe('http_502');
    expect(html.detail).toBe('The backend is unavailable right now.');

    const empty = toAppError(new HttpErrorResponse({ status: 401, error: null }));
    expect(empty.detail).toBe('Your session has expired. Sign in again.');
  });

  it('is an Error, so it survives being thrown through rxjs', () => {
    const error = toAppError(
      new HttpErrorResponse({ status: 404, error: { status: 404, code: 'x', detail: 'gone' } }),
    );
    expect(error).toBeInstanceOf(Error);
    expect(error.message).toBe('gone');
  });
});
