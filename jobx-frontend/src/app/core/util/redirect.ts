const FALLBACK = '/dashboard';

/** Auth pages would bounce straight back off guestGuard, so they are never a target. */
const AUTH_PATHS = new Set(['/login', '/register']);

/**
 * Where to land after sign-in / sign-up, given the raw `?next=` value that
 * authGuard or errorInterceptor put on the URL. Only a plain in-app absolute
 * path survives; anything else — a scheme, a protocol-relative `//host`, a
 * control character, an auth page — falls back to /dashboard. The query string
 * is kept, so `/dashboard?page=3` returns the user to page 3.
 */
export function safeNext(raw: string | null | undefined): string {
  if (!raw || !raw.startsWith('/')) return FALLBACK;
  if (raw.startsWith('//') || raw.startsWith('/\\')) return FALLBACK;
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(raw)) return FALLBACK;

  const path = raw.split(/[?#]/, 1)[0];
  if (AUTH_PATHS.has(path)) return FALLBACK;

  return raw;
}
