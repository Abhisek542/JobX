package com.jobx.scheduler;

import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.entity.User;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetchFilter;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.ExpiredJobRepository;
import com.jobx.repository.JobRepository;
import com.jobx.service.MatchingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core polling loop — the engine of Jobx Discovery.
 *
 * Every 30 minutes:
 *   1. Load all companies with at least one ACTIVE watcher — since V4 a board
 *      is fetched ONCE per cycle no matter how many users watch it (pre-V4 it
 *      was fetched once per watch row, storing N duplicate copies of every job)
 *   2. Route each to the correct fetcher, telling it which postings are already
 *      known (stored or tombstoned) and how old is too old
 *   3. For each new job (not seen before by external_id, not past the TTL):
 *      a. Save the Job — one shared row per posting
 *      b. MatchingService scores it for every ACTIVE watcher of the company
 */
@Component
@Slf4j
public class FetchScheduler {

    /** Max chars of last_fetch_error kept — a summary for operators, never a stack trace. */
    private static final int MAX_ERROR_LENGTH = 500;

    private final CompanyRepository companyRepository;
    private final JobRepository jobRepository;
    private final ExpiredJobRepository expiredJobRepository;
    private final FetcherRegistry fetcherRegistry;
    private final MatchingService matchingService;
    /** Own proxy, so fetchCompany's writes get real transactions of their own. */
    private final ObjectProvider<FetchScheduler> self;
    /** Same property JobRetentionSweeper reads, so the two can never disagree. */
    private final int ttlDays;
    /**
     * Companies with a fetch running right now — the per-board lock. JVM-local
     * on purpose: a Postgres advisory lock would have to hold a pool connection
     * across the outbound fetch, which is exactly BUG_REPORT #6. The app is
     * single-instance (RateLimitFilter makes the same assumption); running more
     * than one instance would need a distributed lock here.
     */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public FetchScheduler(CompanyRepository companyRepository,
                          JobRepository jobRepository,
                          ExpiredJobRepository expiredJobRepository,
                          FetcherRegistry fetcherRegistry,
                          MatchingService matchingService,
                          ObjectProvider<FetchScheduler> self,
                          @Value("${jobx.retention.job-ttl-days:6}") int ttlDays) {
        this.companyRepository = companyRepository;
        this.jobRepository = jobRepository;
        this.expiredJobRepository = expiredJobRepository;
        this.fetcherRegistry = fetcherRegistry;
        this.matchingService = matchingService;
        this.self = self;
        this.ttlDays = ttlDays;
    }

    /**
     * Outcome of one company fetch — feeds the manual "Check now" endpoint's
     * user feedback ("Checked just now; 3 new matches" / "No new roles").
     * newMatchesForOwner counts only matches created for the requesting user,
     * not for other users watching the same board.
     *
     * failed distinguishes "the board had nothing new" from "we could not reach
     * the board" — without it both look like newJobs == 0.
     *
     * inProgress means this call did nothing because another fetch of the same
     * board (the cycle, or another watcher's "Check now") was already running.
     * That fetch stores and scores for every ACTIVE watcher, so the caller loses
     * nothing by not running a second one.
     */
    public record FetchResult(int newJobs, int newMatchesForOwner, boolean failed, boolean inProgress) {
        public static final FetchResult EMPTY = new FetchResult(0, 0, false, false);

        public static FetchResult success(int newJobs, int newMatchesForOwner) {
            return new FetchResult(newJobs, newMatchesForOwner, false, false);
        }

        public static FetchResult failure() {
            return new FetchResult(0, 0, true, false);
        }

        public static FetchResult alreadyRunning() {
            return new FetchResult(0, 0, false, true);
        }
    }

    /**
     * Deliberately NOT @Transactional: each company's writes commit on their own
     * (see fetchCompany below), so a failure partway through one company can't
     * roll back the companies already processed, and a long cycle doesn't hold a
     * single DB connection open across every outbound ATS call.
     */
    @Scheduled(fixedDelayString = "${jobx.fetch.interval-ms:1800000}") // 30min default
    public void fetchAllCompanies() {
        List<Company> companies = companyRepository.findAllWithActiveWatchers();

        log.info("Fetch cycle starting — {} companies with active watchers", companies.size());

        for (Company company : companies) {
            try {
                // fetchCompany is not transactional itself — its writes are — so
                // the per-company isolation this loop needs comes from that split
                // rather than from a proxy hop. A direct call is correct here.
                fetchCompany(company, null);
            } catch (Exception e) {
                // Belt-and-braces: fetchCompany already converts board failures
                // into FAILED health. This catches anything else (a DB error mid
                // company) so the remaining companies still get their turn —
                // "a fetch error for one company never stops the next".
                log.error("Fetch cycle: {} failed, continuing with the rest",
                        company.getDisplayName(), e);
            }
        }

        log.info("Fetch cycle complete");
    }

    /**
     * Fetch one company now — shared by the scheduled cycle above and the
     * manual POST /watchlist/{id}/fetch endpoint (same dedup + scoring flow).
     *
     * Deliberately NOT @Transactional. A board fetch is outbound HTTP, and on
     * Workable or SmartRecruiters it pays a detail call per posting, so a
     * transaction spanning it held one of the ten pool connections for minutes
     * at a time — BUG_REPORT #6. Instead the dedup sets are two plain reads, the
     * fetch itself holds nothing, and every write lands in one short transaction
     * below. Those go through the proxy: self-invocation would skip
     * @Transactional, the same trap fetchAllCompanies documents.
     *
     * @param requester the user behind a manual "Check now", so the result can
     *                  count THEIR new matches; null from the scheduled cycle.
     */
    public FetchResult fetchCompany(Company company, User requester) {
        // One fetch per board at a time (BUG_REPORT #11). Without this, "Check
        // now" landing mid-cycle — or two watchers clicking together — ran two
        // fetches off the same pre-fetch dedup set; the loser tripped
        // UNIQUE (company_id, external_id) and rolled back its whole board.
        // A try-lock, not a wait: the running fetch already stores and scores
        // for every ACTIVE watcher, so a second one would only repeat it.
        if (!inFlight.add(company.getId())) {
            log.info("{} is already being fetched — skipping", company.getDisplayName());
            return FetchResult.alreadyRunning();
        }
        try {
            return fetchLocked(company, requester);
        } finally {
            inFlight.remove(company.getId());
        }
    }

    private FetchResult fetchLocked(Company company, User requester) {
        Optional<AtsFetcher> fetcher = fetcherRegistry.getFetcher(company.getAtsPlatform());

        if (fetcher.isEmpty()) {
            log.warn("No fetcher for {} ({})", company.getDisplayName(), company.getAtsPlatform());
            return self.getObject().markFailed(company,
                    "no fetcher for platform " + company.getAtsPlatform());
        }

        // Everything this board already has, loaded BEFORE the fetch and handed
        // to the fetcher. Two things live in it:
        //  - stored jobs, the ordinary dedup;
        //  - tombstones (V5): postings we dropped on age. Boards keep stale
        //    postings listed long after they stop being worth applying to, so
        //    without these the retention sweep and this loop would fight —
        //    deleted each night, re-added as "new" each morning, forever.
        // It has to reach the fetcher, not just this loop: Workable and
        // SmartRecruiters pay a detail call per posting, and filtering only
        // after they return cost one call per tombstoned posting every cycle.
        // Two set queries per board rather than one existsBy per posting — a
        // board the size of Bosch is thousands of postings.
        Set<String> known = new HashSet<>(jobRepository.findExternalIdsByCompany(company));
        known.addAll(expiredJobRepository.findExternalIdsByCompany(company));
        FetchFilter filter = new FetchFilter(known, Instant.now().minus(Duration.ofDays(ttlDays)));

        List<Job> fetchedJobs;
        try {
            fetchedJobs = fetcher.get().fetch(company, filter);
        } catch (Exception e) {
            // The board is unreachable or unintelligible. Record it as FAILED and
            // return normally: nothing has been written yet, so there is no
            // half-done work to undo, and the caller/cycle carries on.
            log.error("Fetch failed for {} ({}): {}",
                    company.getDisplayName(), company.getAtsPlatform(), e.getMessage(), e);
            return self.getObject().markFailed(company, summarize(e));
        }

        return self.getObject().persistFetched(company, requester, filter, fetchedJobs);
    }

    /**
     * Everything the fetch produced, in one short transaction: the new jobs,
     * their matches and the health stamp commit together or not at all —
     * exactly as they did when the fetch itself was still inside it.
     */
    @Transactional
    public FetchResult persistFetched(Company company, User requester,
                                      FetchFilter filter, List<Job> fetchedJobs) {
        Set<String> known = filter.knownIds();

        // Re-read stored ids now that the fetch is done. The set was taken
        // before it, and a Workable board's detail calls can take seconds. The
        // per-board lock in fetchCompany keeps a second fetch of this board out
        // of this JVM (BUG_REPORT #11); this re-read is the cheap fallback for
        // anything the lock can't see, so a row stored meanwhile is skipped
        // instead of tripping UNIQUE (company_id, external_id) and rolling back
        // this whole board. One query, not one per posting. Tombstones only
        // change in the daily sweep, so they stay.
        known.addAll(jobRepository.findExternalIdsByCompany(company));

        int newCount = 0;
        int requesterMatches = 0;

        for (Job job : fetchedJobs) {
            // Dedup against stored AND tombstoned postings. Post-V4 this is
            // board-wide, not per-watch-row — the second user watching a board
            // no longer re-inserts every posting. Two-call fetchers already
            // applied this; single-call ones rely on it here.
            if (filter.isKnown(job.getExternalId())) {
                continue;
            }

            // Already past the TTL. Without this a newly added board ingests
            // every stale posting it still lists, scores each as "New", and the
            // sweep deletes them all within a day.
            if (filter.isTooOld(job.getPlatformPostedAt())) {
                continue;
            }

            // New job — save it (once, shared by all watchers)
            Job saved = jobRepository.save(job);
            known.add(job.getExternalId());
            newCount++;

            requesterMatches += matchingService.scoreForActiveWatchers(company, saved, requester);
        }

        // Update last fetched timestamp (also the manual-fetch cooldown anchor,
        // now shared by every watcher of the board)
        company.setLastFetchedAt(Instant.now());
        company.setLastFetchStatus(Company.FetchStatus.SUCCESS);
        company.setLastFetchError(null);
        companyRepository.save(company);

        if (newCount > 0) {
            log.info("New jobs for {}: {}", company.getDisplayName(), newCount);
        }
        return FetchResult.success(newCount, requesterMatches);
    }

    /**
     * Stamp a failed attempt. lastFetchedAt still moves so the manual-fetch
     * cooldown applies to failures too — a broken board must not become a way to
     * hammer an ATS by holding down "Check now".
     *
     * Public and transactional because fetchCompany is neither: it reaches this
     * through the proxy, so the stamp still gets a real transaction of its own.
     */
    @Transactional
    public FetchResult markFailed(Company company, String error) {
        company.setLastFetchedAt(Instant.now());
        company.setLastFetchStatus(Company.FetchStatus.FAILED);
        company.setLastFetchError(error);
        companyRepository.save(company);
        return FetchResult.failure();
    }

    /** Short, sanitized cause for last_fetch_error — type + message, never a stack trace. */
    private String summarize(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null) {
            message = message + " (" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")";
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
