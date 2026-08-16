/**
 * Mirrors the backend's com.jobx.util.TextLists.normalize: trim, drop blanks,
 * dedupe case-insensitively, keep first-seen order and original casing.
 *
 * Doing it client-side too means the preferences form shows the user exactly
 * what the server will store, and the "at least one keyword" check fails in the
 * form instead of as a 400 round trip.
 */
export function normalizeList(values: readonly string[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const raw of values) {
    const value = (raw ?? '').trim();
    if (!value) continue;
    const key = value.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(value);
  }
  return out;
}

/** Comma-separated textarea -> normalized list. */
export function parseList(input: string): string[] {
  return normalizeList((input ?? '').split(','));
}
