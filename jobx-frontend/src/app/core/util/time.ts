/**
 * Relative time, ported from the mockup's relTime().
 *
 * HONESTY (uiux_plan.md §7): the only timestamps the API gives us are
 * Match.createdAt (when Jobx first saw the role) and WatchedCompany.lastFetchedAt.
 * Neither is the employer's posting date — `MatchResponse` has no
 * platformPostedAt — so every caller labels this "Found …" or "Checked …",
 * never "Posted …".
 */
export function relTime(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return 'never';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return 'unknown';

  const mins = Math.round((now - then) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const h = Math.round(mins / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  return d === 1 ? 'yesterday' : `${d}d ago`;
}

/** Absolute timestamp for the drawer's detail rows. */
export function absoluteTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}
