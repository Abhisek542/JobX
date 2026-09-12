package com.jobx.fetcher;

import java.time.Instant;
import java.util.Set;

/**
 * What a fetch cycle already knows about a board, handed to the fetcher so it
 * can skip a posting BEFORE paying for it.
 *
 * On Workable and SmartRecruiters "paying" means a per-posting detail call.
 * Pre-fix those fetchers only knew about the jobs table, so once the TTL sweep
 * had moved a board's postings into expired_jobs, every one still listed cost
 * a detail call per cycle, forever — ~4,774 per cycle on Bosch. The scheduler
 * already loaded the tombstone set; it just loaded it too late.
 *
 * Built once per board by FetchScheduler: two set queries replace one
 * existsBy query per posting.
 *
 * @param knownIds     external ids in jobs OR expired_jobs — never fetch these again
 * @param postedCutoff the retention cutoff; a posting dated before it would be
 *                     swept within a day, so it is not worth ingesting at all
 */
public record FetchFilter(Set<String> knownIds, Instant postedCutoff) {

    /** Skips nothing — for fixture tests that exercise mapping only. */
    public static FetchFilter none() {
        return new FetchFilter(Set.of(), Instant.MIN);
    }

    public boolean isKnown(String externalId) {
        // Set.of() rejects contains(null), and a malformed posting can have no id.
        return externalId != null && knownIds.contains(externalId);
    }

    /**
     * True only when the board gave a date and it is past the TTL. No date is
     * never "too old": the sweep's clock falls back to first_seen_at, which for
     * a posting seen for the first time is now.
     */
    public boolean isTooOld(Instant postedAt) {
        return postedAt != null && postedAt.isBefore(postedCutoff);
    }
}
