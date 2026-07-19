package com.jobx.fetcher;

import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;

import java.util.List;

/**
 * Translation layer between raw ATS API responses and normalized Job entities.
 *
 * Each implementation absorbs all ATS-specific weirdness:
 *  - different field names (title vs text vs title)
 *  - different timestamp formats (ISO 8601 vs epoch ms)
 *  - different description formats (HTML vs plain text)
 *  - platform-specific quirks (Greenhouse prospect posts with null internal_job_id)
 *
 * Nothing downstream (MatchScorer, Postgres schema, API layer) ever
 * sees raw ATS JSON — only the normalized List<Job> returned here.
 */
public interface AtsFetcher {

    /** Which ATS platform this fetcher handles. Used by FetcherRegistry for routing. */
    AtsPlatform supports();

    /**
     * Fetch all active job postings for the given watched company.
     * Returns normalized Job objects ready to persist.
     * Never throws — catches all errors, logs them, returns empty list.
     */
    List<Job> fetch(WatchedCompany company);
}
