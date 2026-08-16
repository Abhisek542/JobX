import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthStore } from '../services/auth.store';
import { toAppError } from './error-mapping';

/**
 * Maps every failed response to an AppError, once. On 401 it clears the session
 * and sends the user to /login — there is no refresh token in v1, so an expired
 * JWT is terminal (uiux_plan.md §6).
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthStore);
  const router = inject(Router);

  return next(req).pipe(
    catchError((raw: unknown) => {
      if (!(raw instanceof HttpErrorResponse)) {
        return throwError(() => raw);
      }

      const error = toAppError(raw);

      // A 401 from /auth/login or /auth/register is "wrong credentials", not an
      // expired session — leave those to the form to display.
      const isAuthCall = req.url.includes('/auth/');
      if (error.status === 401 && !isAuthCall) {
        auth.clear();
        void router.navigate(['/login'], { queryParams: { expired: 1 } });
      }

      return throwError(() => error);
    }),
  );
};
