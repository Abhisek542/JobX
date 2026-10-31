/**
 * Whether add-company input in "Careers page link" mode is a link at all.
 *
 * Mirrors the backend's `CompanyResolver.looksLikeUrl` (`SlugCandidates.hostOf`
 * plus an explicit `scheme://`), so the form rejects a bare company name before
 * a round trip and the server would agree with the verdict. The rules:
 *   - an explicit scheme (`https://`, even `file://`) is a URL: the server then
 *     says plainly which schemes it fetches, which beats "not a link";
 *   - otherwise the host part (before the first `/`, without `user@` or `:port`)
 *     must contain a dot, no space, and only `[a-z0-9.-]`.
 * "Acme Inc." is therefore a name, and "razorpay.com/careers" is a link.
 */
export function looksLikeUrl(text: string): boolean {
  const query = (text ?? '').trim();
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(query)) {
    return true;
  }

  let host = query;
  const slash = host.indexOf('/');
  if (slash >= 0) host = host.substring(0, slash);
  const at = host.indexOf('@');
  if (at >= 0) host = host.substring(at + 1);
  const colon = host.indexOf(':');
  if (colon >= 0) host = host.substring(0, colon);
  host = host.trim().toLowerCase();

  if (!host || host.includes(' ') || !host.includes('.')) {
    return false;
  }
  return /^[a-z0-9.-]+$/.test(host);
}
