package com.jobx.fetcher;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Postings a fetcher has already found to be past the TTL, so it never pays a
 * detail call for them again.
 *
 * This exists for two-call fetchers whose LIST carries no date (JazzHR,
 * Gusto). Workable can skip a stale posting from its list date before the
 * detail call; these platforms only reveal the date on the detail page. And a
 * posting past the TTL is never stored or tombstoned — FetchScheduler simply
 * drops it — so without this every stale posting still listed on a board
 * would cost one detail call per cycle, forever: BUG_REPORT #2 in a new shape.
 *
 * Remembering is safe because age only grows: a posting past the cutoff can
 * never come back inside it. The memo is JVM-local and lost on restart, which
 * costs one detail call per stale posting per restart — not worth a table.
 * Bounded by {@link #MAX_ENTRIES}; on overflow it is cleared rather than
 * grown, which again only costs re-checks.
 */
public final class PastTtlMemo {

    static final int MAX_ENTRIES = 50_000;

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    public boolean contains(String board, String externalId) {
        return keys.contains(key(board, externalId));
    }

    public void remember(String board, String externalId) {
        if (keys.size() >= MAX_ENTRIES) {
            keys.clear();
        }
        keys.add(key(board, externalId));
    }

    int size() {
        return keys.size();
    }

    private static String key(String board, String externalId) {
        return board + '\u0000' + externalId;
    }
}
