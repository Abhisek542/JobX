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
     * ONE cheap list call describing the board: how many roles are live and a
     * few real titles. Two callers depend on it:
     *
     *  - the add-company resolver, which previews candidate boards it worked out
     *    from a careers URL or a slug guess, and shows the winner's real titles
     *    so a human can confirm it is actually their company;
     *  - {@link #validateBoard}, below.
     *
     * MUST stay to a single HTTP request. The resolver runs this across many
     * (platform, token) candidates in parallel to work out which board a company
     * is on, so a second call here multiplies straight into that fan-out. That
     * is also why it must never fetch per-job detail the way {@link #fetch} does
     * on Workable and SmartRecruiters — titles come from the list response only.
     *
     * Throws {@link AtsFetchException} if the board could not be read at all.
     * A board that exists but has nothing open returns jobCount 0, which is a
     * successful outcome here; it is {@code validateBoard} that decides whether
     * zero is acceptable.
     */
    BoardPreview previewBoard(Company company);

    /**
     * Cheap check that the board token actually identifies a real board, run
     * once when a user first adds it. Throws {@link AtsFetchException} if it
     * does not; POST /watchlist turns that into a 400 naming the token.
     *
     * The rule is "a board we cannot see a single live role on is not a board we
     * should accept", and it is deliberately stricter than {@link #fetch}. Two
     * platforms cannot distinguish a typo from an empty board by status code at
     * all — SmartRecruiters answers a nonsense company id with a cheerful
     * {@code 200 {"totalFound":0,"content":[]}}, and Workable answers an
     * abandoned account with {@code 200 {"name":"Razorpay","jobs":[]}}, echoing
     * a plausible company name back at us. Both were verified live. Without this
     * check either would sit on a watchlist looking perfectly healthy and simply
     * never produce a job — the silent-empty-feed shape that has bitten this
     * project three times.
     *
     * Accepted trade-off, unchanged from when SmartRecruiters introduced it: a
     * real board with zero openings this week is rejected at add time. Only add
     * time is strict — once a board is on the watchlist, {@link #fetch} treats
     * an empty list as the ordinary "nothing new" outcome, so a company that
     * closes all its roles keeps being polled rather than being dropped.
     */
    default void validateBoard(Company company) {
        BoardPreview preview = previewBoard(company);
        if (preview.jobCount() == 0) {
            throw new AtsFetchException("No live roles found on the " + supports()
                    + " board '" + company.getBoardToken() + "'");
        }
    }
}
