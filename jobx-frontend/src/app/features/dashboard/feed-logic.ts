import { MatchResponse, MatchStatus } from '../../core/models/match.model';

/* ============================================================================
   Pure feed logic. Everything here is a plain function over plain data so it
   can be unit-tested without Angular (uiux_plan.md §2: tests cover pure logic
   only). feed.store.ts is the signal wrapper around these.

   Fixed order of operations (uiux_plan.md §4): filter -> search -> sort -> paginate.
   ========================================================================== */

export type StatusFilter = 'ALL' | MatchStatus;
export type SortMode = 'score' | 'newest' | 'company';

export const PAGE_SIZE = 10;

export interface FeedView {
  status: StatusFilter;
  query: string;
  sort: SortMode;
}

export interface ScoreBand {
  label: string;
  /** A token reference, never a literal colour. */
  color: string;
  why: string;
}

/** Score bands — one source of truth so ring, label and drawer always agree. */
export function band(score: number): ScoreBand {
  if (score >= 85) {
    return {
      label: 'Strong match',
      color: 'var(--good)',
      why: 'Most of your keywords, right seniority',
    };
  }
  if (score >= 70) {
    return { label: 'Good match', color: 'var(--lime)', why: 'Solid keyword overlap' };
  }
  if (score >= 55) {
    return {
      label: 'Fair match',
      color: 'var(--warn)',
      why: 'Partial overlap or experience gap',
    };
  }
  return { label: 'Weak match', color: 'var(--faint)', why: 'One or two keywords only' };
}

/**
 * Step 1 — status. The ALL pill excludes DISMISSED; every other pill is an
 * exact match. (GET /matches returns DISMISSED rows too, so this is the only
 * thing keeping them out of the default feed.)
 */
export function filterByStatus(matches: readonly MatchResponse[], status: StatusFilter) {
  return status === 'ALL'
    ? matches.filter((m) => m.status !== 'DISMISSED')
    : matches.filter((m) => m.status === status);
}

/**
 * Step 2 — search. Title, company and matched keywords ONLY. The empty state
 * says so out loud, because MatchResponse carries no description or location to
 * search (uiux_plan.md §6, §7).
 */
export function searchMatches(matches: readonly MatchResponse[], query: string) {
  const q = query.trim().toLowerCase();
  if (!q) return [...matches];
  return matches.filter(
    (m) =>
      m.jobTitle.toLowerCase().includes(q) ||
      m.companyName.toLowerCase().includes(q) ||
      m.matchedKeywords.some((k) => k.toLowerCase().includes(q)),
  );
}

/** Step 3 — sort. Ties fall back to score so the order is stable. */
export function sortMatches(matches: readonly MatchResponse[], sort: SortMode) {
  const list = [...matches];
  switch (sort) {
    case 'newest':
      return list.sort(
        (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
      );
    case 'company':
      return list.sort(
        (a, b) => a.companyName.localeCompare(b.companyName) || b.score - a.score,
      );
    case 'score':
    default:
      return list.sort((a, b) => b.score - a.score);
  }
}

/** filter -> search -> sort, in that order. Pagination is applied separately. */
export function applyView(matches: readonly MatchResponse[], view: FeedView): MatchResponse[] {
  return sortMatches(searchMatches(filterByStatus(matches, view.status), view.query), view.sort);
}

/**
 * Pill counts always describe the WHOLE feed, never the current page
 * (uiux_plan.md §4).
 */
export function countByStatus(matches: readonly MatchResponse[]): Record<StatusFilter, number> {
  return {
    ALL: matches.filter((m) => m.status !== 'DISMISSED').length,
    NEW: matches.filter((m) => m.status === 'NEW').length,
    SEEN: matches.filter((m) => m.status === 'SEEN').length,
    APPLIED: matches.filter((m) => m.status === 'APPLIED').length,
    DISMISSED: matches.filter((m) => m.status === 'DISMISSED').length,
  };
}

/* ------------------------------------------------------------ pagination -- */

export function totalPages(itemCount: number, pageSize = PAGE_SIZE): number {
  return Math.max(1, Math.ceil(itemCount / pageSize));
}

/**
 * Clamp into range. This is what stops "dismiss the last card on page 4" from
 * landing the user on an empty page — it lands them on page 3.
 */
export function clampPage(page: number, pages: number): number {
  if (!Number.isFinite(page)) return 1;
  return Math.min(Math.max(Math.trunc(page), 1), Math.max(pages, 1));
}

export function pageSlice<T>(items: readonly T[], page: number, pageSize = PAGE_SIZE): T[] {
  const safe = clampPage(page, totalPages(items.length, pageSize));
  const start = (safe - 1) * pageSize;
  return items.slice(start, start + pageSize);
}

export type PageItem = number | 'gap';

/**
 * At most `max` numeric slots, with ellipses beyond that. First and last page
 * are always reachable so a long feed never traps the user mid-range.
 */
export function pageNumbers(current: number, pages: number, max = 5): PageItem[] {
  if (pages <= 0) return [];
  if (pages <= max) return range(1, pages);

  const safe = clampPage(current, pages);
  const start = Math.max(1, Math.min(safe - Math.floor(max / 2), pages - max + 1));
  const items: PageItem[] = range(start, start + max - 1);

  if (items[0] !== 1) {
    items[0] = 1;
    if (items[1] !== 2) items[1] = 'gap';
  }
  if (items[max - 1] !== pages) {
    items[max - 1] = pages;
    if (items[max - 2] !== pages - 1) items[max - 2] = 'gap';
  }
  return items;
}

/** "Showing 1–10 of 43" — inclusive, 1-based, and empty-safe. */
export function pageRangeLabel(
  itemCount: number,
  page: number,
  pageSize = PAGE_SIZE,
): { from: number; to: number; total: number } {
  if (itemCount === 0) return { from: 0, to: 0, total: 0 };
  const safe = clampPage(page, totalPages(itemCount, pageSize));
  const from = (safe - 1) * pageSize + 1;
  return { from, to: Math.min(from + pageSize - 1, itemCount), total: itemCount };
}

function range(from: number, to: number): number[] {
  return Array.from({ length: to - from + 1 }, (_, i) => from + i);
}
