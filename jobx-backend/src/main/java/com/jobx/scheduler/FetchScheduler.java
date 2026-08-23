package com.jobx.scheduler;

import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.entity.User;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.JobRepository;
import com.jobx.service.MatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Core polling loop — the engine of Jobx Discovery.
 *
 * Every 30 minutes:
 *   1. Load all companies with at least one ACTIVE watcher — since V4 a board
 *      is fetched ONCE per cycle no matter how many users watch it (pre-V4 it
 *      was fetched once per watch row, storing N duplicate copies of every job)
 *   2. Route each to the correct fetcher
 *   3. For each new job (not seen before by external_id):
 *      a. Save the Job — one shared row per posting
 *      b. MatchingService scores it for every ACTIVE watcher of the company
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FetchScheduler {

    /** Max chars of last_fetch_error kept — a summary for operators, never a stack trace. */
    private static final int MAX_ERROR_LENGTH = 500;

    private final CompanyRepository companyRepository;
    private final JobRepository jobRepository;
    private final FetcherRegistry fetcherRegistry;
    private final MatchingService matchingService;
    /** Own proxy, so fetchAllCompanies gets a real transaction per company. */
    private final ObjectProvider<FetchScheduler> self;

    /**
     * Outcome of one company fetch — feeds the manual "Check now" endpoint's
     * user feedback ("Checked just now; 3 new matches" / "No new roles").
     * newMatchesForOwner counts only matches created for the requesting user,
     * not for other users watching the same board.
     *
     * failed distinguishes "the board had nothing new" from "we could not reach
     * the board" — without it both look like newJobs == 0.
     */
    public record FetchResult(int newJobs, int newMatchesForOwner, boolean failed) {
        public static final FetchResult EMPTY = new FetchResult(0, 0, false);

        public static FetchResult success(int newJobs, int newMatchesForOwner) {
            return new FetchResult(newJobs, newMatchesForOwner, false);
        }

        public static FetchResult failure() {
            return new FetchResult(0, 0, true);
        }
    }

    /**
     * Deliberately NOT @Transactional: each company gets its own transaction via
     * the proxy below, so a failure partway through one company can't roll back
     * the companies already processed, and a long cycle doesn't hold a single DB
     * connection open across every outbound ATS call.
     */
    @Scheduled(fixedDelayString = "${jobx.fetch.interval-ms:1800000}") // 30min default
    public void fetchAllCompanies() {
        List<Company> companies = companyRepository.findAllWithActiveWatchers();

        log.info("Fetch cycle starting — {} companies with active watchers", companies.size());

        for (Company company : companies) {
            try {
                // Through the proxy, not this.fetchCompany(...) — self-invocation
                // would skip @Transactional and put us back in one big transaction.
                self.getObject().fetchCompany(company, null);
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
     * One transaction per company, whether called from the cycle or the endpoint.
     *
     * @param requester the user behind a manual "Check now", so the result can
     *                  count THEIR new matches; null from the scheduled cycle.
     */
    @Transactional
    public FetchResult fetchCompany(Company company, User requester) {
        Optional<AtsFetcher> fetcher = fetcherRegistry.getFetcher(company.getAtsPlatform());

        if (fetcher.isEmpty()) {
            log.warn("No fetcher for {} ({})", company.getDisplayName(), company.getAtsPlatform());
            return recordFailure(company, "no fetcher for platform " + company.getAtsPlatform());
        }

        List<Job> fetchedJobs;
        try {
            fetchedJobs = fetcher.get().fetch(company);
        } catch (Exception e) {
            // The board is unreachable or unintelligible. Record it as FAILED and
            // return normally: nothing has been written yet, so the transaction is
            // clean, and the caller/cycle carries on.
            log.error("Fetch failed for {} ({}): {}",
                    company.getDisplayName(), company.getAtsPlatform(), e.getMessage(), e);
            return recordFailure(company, summarize(e));
        }

        int newCount = 0;
        int requesterMatches = 0;

        for (Job job : fetchedJobs) {
            // Dedup: skip if we've seen this external_id for this company before.
            // Post-V4 this is board-wide, not per-watch-row — the second user
            // watching a board no longer re-inserts every posting.
            if (jobRepository.existsByCompanyAndExternalId(company, job.getExternalId())) {
                continue;
            }

            // New job — save it (once, shared by all watchers)
            Job saved = jobRepository.save(job);
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
     */
    private FetchResult recordFailure(Company company, String error) {
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
