package com.jobx.fetcher;

import com.jobx.entity.Job;
import com.jobx.entity.Company;
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
     *
     * An empty list means the board genuinely has no (new) postings — a normal,
     * successful outcome. Anything that stopped us from finding that out must
     * throw {@link AtsFetchException} instead, so the scheduler can record
     * FAILED health rather than reporting a dead board as a quiet one.
     * FetchScheduler isolates the failure; one board's outage never stops the
     * next company from being processed.
     */
    List<Job> fetch(Company company);

    /**
     * Cheap one-call check that the board token actually identifies a board,
     * run once when a user first adds it. Throws {@link AtsFetchException} if
     * it does not; POST /watchlist turns that into a 400 naming the token.
     *
     * The default is to do nothing, because for Greenhouse, Lever, Ashby and
     * Workable a bad token 404s and {@link #fetch} already reports it. It is
     * overridden only where the API cannot tell a typo from an empty board —
     * SmartRecruiters answers a nonsense company id with a cheerful
     * {@code 200 {"totalFound":0,"content":[]}}, so without this a typo'd
     * watch would look healthy forever and simply never produce a job.
     *
     * Deliberately separate from {@code fetch}: a real board that has zero
     * openings this week must keep fetching normally once it is on the
     * watchlist. The distinction is only drawable at add time.
     */
    default void validateBoard(Company company) {
        // no-op: this platform's API distinguishes a bad token by itself
    }
}
