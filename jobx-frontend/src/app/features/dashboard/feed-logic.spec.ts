import { describe, expect, it } from 'vitest';
import { MatchResponse, MatchStatus } from '../../core/models/match.model';
import {
  GROUP_PAGE_SIZE,
  applyView,
  band,
  clampPage,
  countByStatus,
  filterByStatus,
  groupByCompany,
  pageNumbers,
  pageRangeLabel,
  pageSlice,
  searchMatches,
  sortMatches,
  totalPages,
} from './feed-logic';

/* Pure logic only — the rules that decide what a user actually sees
   (uiux_plan.md §2). No template tests. */

function match(over: Partial<MatchResponse> = {}): MatchResponse {
  return {
    id: 'm1',
    jobId: 'j1',
    jobTitle: 'Backend Engineer',
    companyId: 'c-razorpay',
    companyName: 'Razorpay',
    applyUrl: 'https://example.test/apply',
    score: 80,
    matchedKeywords: ['Java', 'Spring Boot'],
    status: 'NEW',
    createdAt: '2026-08-15T09:00:00Z',
    expiredAt: null,
    ...over,
  };
}

function feedOf(...statuses: MatchStatus[]): MatchResponse[] {
  return statuses.map((status, i) => match({ id: `m${i}`, status }));
}

describe('band', () => {
  it('labels each score range', () => {
    expect(band(94).label).toBe('Strong match');
    expect(band(85).label).toBe('Strong match');
    expect(band(84).label).toBe('Good match');
    expect(band(70).label).toBe('Good match');
    expect(band(69).label).toBe('Fair match');
    expect(band(55).label).toBe('Fair match');
    expect(band(54).label).toBe('Weak match');
    expect(band(0).label).toBe('Weak match');
  });

  it('only ever names colour tokens, never literals', () => {
    for (const score of [95, 75, 60, 10]) {
      expect(band(score).color).toMatch(/^var\(--[a-z-]+\)$/);
    }
  });
});

describe('filterByStatus', () => {
  it('excludes DISMISSED from ALL', () => {
    const feed = feedOf('NEW', 'SEEN', 'APPLIED', 'DISMISSED');
    expect(filterByStatus(feed, 'ALL')).toHaveLength(3);
    expect(filterByStatus(feed, 'ALL').some((m) => m.status === 'DISMISSED')).toBe(false);
  });

  it('matches exactly for every other pill, DISMISSED included', () => {
    const feed = feedOf('NEW', 'NEW', 'SEEN', 'DISMISSED');
    expect(filterByStatus(feed, 'NEW')).toHaveLength(2);
    expect(filterByStatus(feed, 'SEEN')).toHaveLength(1);
    expect(filterByStatus(feed, 'DISMISSED')).toHaveLength(1);
    expect(filterByStatus(feed, 'APPLIED')).toHaveLength(0);
  });
});

describe('searchMatches', () => {
  const feed = [
    match({ id: 'a', jobTitle: 'Backend Engineer', companyName: 'Razorpay' }),
    match({ id: 'b', jobTitle: 'Data Scientist', companyName: 'PhonePe', matchedKeywords: ['Python'] }),
  ];

  it('returns everything for a blank or whitespace query', () => {
    expect(searchMatches(feed, '')).toHaveLength(2);
    expect(searchMatches(feed, '   ')).toHaveLength(2);
  });

  it('matches title, company and matched keywords, case-insensitively', () => {
    expect(searchMatches(feed, 'backend').map((m) => m.id)).toEqual(['a']);
    expect(searchMatches(feed, 'PHONEPE').map((m) => m.id)).toEqual(['b']);
    expect(searchMatches(feed, 'python').map((m) => m.id)).toEqual(['b']);
  });

  it('matches nothing outside those three fields', () => {
    // applyUrl is deliberately not searched.
    expect(searchMatches(feed, 'example.test')).toHaveLength(0);
  });
});

describe('sortMatches', () => {
  const feed = [
    match({ id: 'a', score: 70, companyName: 'Zolve', createdAt: '2026-08-15T10:00:00Z' }),
    match({ id: 'b', score: 90, companyName: 'Apna', createdAt: '2026-08-14T10:00:00Z' }),
    match({ id: 'c', score: 90, companyName: 'Apna', createdAt: '2026-08-13T10:00:00Z' }),
  ];

  it('sorts by score descending', () => {
    expect(sortMatches(feed, 'score').map((m) => m.score)).toEqual([90, 90, 70]);
  });

  it('sorts by newest first', () => {
    expect(sortMatches(feed, 'newest').map((m) => m.id)).toEqual(['a', 'b', 'c']);
  });

  it('does not mutate the input', () => {
    const input = [...feed];
    sortMatches(input, 'score');
    expect(input.map((m) => m.id)).toEqual(['a', 'b', 'c']);
  });
});

describe('applyView', () => {
  it('filters, then searches, then sorts', () => {
    const feed = [
      match({ id: 'a', status: 'NEW', score: 60, jobTitle: 'Backend Engineer' }),
      match({ id: 'b', status: 'NEW', score: 95, jobTitle: 'Backend Architect' }),
      match({ id: 'c', status: 'DISMISSED', score: 99, jobTitle: 'Backend Lead' }),
      match({ id: 'd', status: 'NEW', score: 99, jobTitle: 'Frontend Engineer' }),
    ];

    const result = applyView(feed, { status: 'ALL', query: 'backend', sort: 'score' });
    expect(result.map((m) => m.id)).toEqual(['b', 'a']);
  });
});

describe('countByStatus', () => {
  it('counts the whole feed, not a filtered view', () => {
    const counts = countByStatus(feedOf('NEW', 'NEW', 'SEEN', 'APPLIED', 'DISMISSED'));
    expect(counts).toEqual({ ALL: 4, NEW: 2, SEEN: 1, APPLIED: 1, DISMISSED: 1 });
  });
});

describe('pagination', () => {
  const items = Array.from({ length: 43 }, (_, i) => i + 1);

  it('computes total pages at 10 per page', () => {
    expect(totalPages(43)).toBe(5);
    expect(totalPages(40)).toBe(4);
    expect(totalPages(1)).toBe(1);
    // Never zero — an empty feed is still "page 1 of 1".
    expect(totalPages(0)).toBe(1);
  });

  it('clamps out-of-range pages instead of showing an empty page', () => {
    expect(clampPage(0, 5)).toBe(1);
    expect(clampPage(-3, 5)).toBe(1);
    expect(clampPage(9, 5)).toBe(5);
    expect(clampPage(Number.NaN, 5)).toBe(1);
  });

  it('slices the right window and clamps a page past the end', () => {
    expect(pageSlice(items, 1)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
    expect(pageSlice(items, 5)).toEqual([41, 42, 43]);
    // The "dismiss the last card on page 5" case: page 5 of a now-40-item list.
    expect(pageSlice(items.slice(0, 40), 5)).toEqual([31, 32, 33, 34, 35, 36, 37, 38, 39, 40]);
  });

  it('labels the visible range inclusively', () => {
    expect(pageRangeLabel(43, 1)).toEqual({ from: 1, to: 10, total: 43 });
    expect(pageRangeLabel(43, 5)).toEqual({ from: 41, to: 43, total: 43 });
    expect(pageRangeLabel(0, 1)).toEqual({ from: 0, to: 0, total: 0 });
  });

  it('shows every page when they fit', () => {
    expect(pageNumbers(1, 4)).toEqual([1, 2, 3, 4]);
  });

  it('uses at most five numeric slots, with ellipses and reachable ends', () => {
    const middle = pageNumbers(10, 20);
    expect(middle).toHaveLength(5);
    expect(middle[0]).toBe(1);
    expect(middle[4]).toBe(20);
    expect(middle).toContain('gap');

    const start = pageNumbers(1, 20);
    expect(start.slice(0, 3)).toEqual([1, 2, 3]);
    expect(start[4]).toBe(20);

    const end = pageNumbers(20, 20);
    expect(end[0]).toBe(1);
    expect(end.slice(3)).toEqual([19, 20]);
  });

  it('keeps consecutive runs consecutive and only elides real gaps', () => {
    expect(pageNumbers(3, 6)).toEqual([1, 2, 3, 'gap', 6]);
    expect(pageNumbers(10, 20)).toEqual([1, 'gap', 10, 'gap', 20]);
    expect(pageNumbers(20, 20)).toEqual([1, 'gap', 18, 19, 20]);
  });
});

describe('groupByCompany', () => {
  const rz = (over = {}) => match({ companyId: 'c-rz', companyName: 'Razorpay', ...over });
  const ap = (over = {}) => match({ companyId: 'c-ap', companyName: 'Apna', ...over });

  const feed = [
    rz({ id: 'a', score: 95, status: 'NEW', createdAt: '2026-08-15T10:00:00Z' }),
    ap({ id: 'b', score: 70, status: 'SEEN', createdAt: '2026-08-18T10:00:00Z' }),
    rz({ id: 'c', score: 60, status: 'NEW', createdAt: '2026-08-10T10:00:00Z' }),
  ];

  it('gathers each company once, with its own roles', () => {
    const groups = groupByCompany(feed, 'score');
    expect(groups).toHaveLength(2);
    expect(groups.map((g) => g.companyName)).toEqual(['Razorpay', 'Apna']);
    expect(groups[0].matches.map((m) => m.id)).toEqual(['a', 'c']);
  });

  it('summarises a group from the rows it actually holds', () => {
    const [razorpay] = groupByCompany(feed, 'score');
    expect(razorpay.count).toBe(2);
    expect(razorpay.bestScore).toBe(95);
    expect(razorpay.newCount).toBe(2);
    expect(razorpay.latestAt).toBe('2026-08-15T10:00:00Z');
  });

  it('keys on companyId, so two boards sharing a display name stay apart', () => {
    // The exact case companyId was added to MatchResponse for: companyName is
    // a display string and is not unique.
    const collision = [
      match({ id: 'x', companyId: 'c-1', companyName: 'Porter' }),
      match({ id: 'y', companyId: 'c-2', companyName: 'Porter' }),
    ];
    const groups = groupByCompany(collision, 'name');
    expect(groups).toHaveLength(2);
    expect(groups.map((g) => g.companyId)).toEqual(['c-1', 'c-2']);
  });

  it('preserves the incoming role order inside a group', () => {
    // groupByCompany runs on applyView's output, so within-group order is
    // whatever the flat pipeline produced — it must not re-sort.
    const sorted = applyView(feed, { status: 'ALL', query: '', sort: 'score' });
    const [razorpay] = groupByCompany(sorted, 'score');
    expect(razorpay.matches.map((m) => m.score)).toEqual([95, 60]);
  });

  it('orders groups by best score, then newest, then A-Z', () => {
    expect(groupByCompany(feed, 'score').map((g) => g.companyName)).toEqual([
      'Razorpay',
      'Apna',
    ]);
    expect(groupByCompany(feed, 'newest').map((g) => g.companyName)).toEqual([
      'Apna',
      'Razorpay',
    ]);
    expect(groupByCompany(feed, 'name').map((g) => g.companyName)).toEqual([
      'Apna',
      'Razorpay',
    ]);
  });

  it('still groups a match whose posting has expired', () => {
    // V5 TTL: jobId goes null but companyId is a denormalized FK and survives.
    const expired = [
      match({ id: 'e', companyId: 'c-rz', jobId: null, expiredAt: '2026-08-20T10:00:00Z' }),
    ];
    expect(groupByCompany(expired, 'score')[0].companyId).toBe('c-rz');
  });

  it('returns nothing for an empty feed', () => {
    expect(groupByCompany([], 'score')).toEqual([]);
  });

  it('tracks latestAt by instant, not by string order', () => {
    // Jackson omits trailing zeros on an Instant, so one feed carries both
    // shapes. Lexically ".246773300Z" sorts BEFORE "Z", so a string compare
    // would pick the earlier row as the latest.
    const mixed = [
      match({ id: 'p', companyId: 'c-1', createdAt: '2026-09-12T12:34:16Z' }),
      match({ id: 'q', companyId: 'c-1', createdAt: '2026-09-12T12:34:16.246773300Z' }),
    ];
    expect(groupByCompany(mixed, 'score')[0].latestAt).toBe('2026-09-12T12:34:16.246773300Z');
  });

  it('orders groups by newest across those same mixed formats', () => {
    const feedMixed = [
      match({ id: 'a', companyId: 'c-old', companyName: 'Old', createdAt: '2026-09-12T12:34:16.900Z' }),
      match({ id: 'b', companyId: 'c-new', companyName: 'New', createdAt: '2026-09-12T12:34:17Z' }),
    ];
    expect(groupByCompany(feedMixed, 'newest').map((g) => g.companyName)).toEqual(['New', 'Old']);
  });
});

describe('paginating groups', () => {
  // The grouped view reuses the generic page helpers over CompanyGroup[], which
  // is what keeps one company's roles off two different pages.
  const groups = groupByCompany(
    Array.from({ length: 12 }, (_, i) =>
      match({ id: `m${i}`, companyId: `c${i}`, companyName: `Co ${i}` }),
    ),
    'name',
  );

  it('pages 12 companies into 3 pages of 5', () => {
    expect(groups).toHaveLength(12);
    expect(totalPages(groups.length, GROUP_PAGE_SIZE)).toBe(3);
    expect(pageSlice(groups, 1, GROUP_PAGE_SIZE)).toHaveLength(5);
    expect(pageSlice(groups, 3, GROUP_PAGE_SIZE)).toHaveLength(2);
  });

  it('clamps onto the last page when groups disappear', () => {
    const shrunk = groups.slice(0, 6);
    const pages = totalPages(shrunk.length, GROUP_PAGE_SIZE);
    expect(clampPage(3, pages)).toBe(2);
  });

  it('labels the range in companies', () => {
    expect(pageRangeLabel(groups.length, 3, GROUP_PAGE_SIZE)).toEqual({
      from: 11,
      to: 12,
      total: 12,
    });
  });
});
