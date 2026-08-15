package com.jobx.scheduler;

import com.jobx.entity.*;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.*;
import com.jobx.scorer.MatchScorer;
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
 *   1. Load all ACTIVE watched companies
 *   2. Route each to the correct fetcher (Greenhouse only in Phase 1)
 *   3. For each new job (not seen before by external_id):
 *      a. Save the Job
 *      b. Run MatchScorer against every user watching this company
 *      c. Save Match rows for non-excluded results
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FetchScheduler {

    /** Max chars of last_fetch_error kept — a summary for operators, never a stack trace. */
    private static final int MAX_ERROR_LENGTH = 500;

    private final WatchedCompanyRepository watchedCompanyRepository;
    private final JobRepository jobRepository;
    private final FilterProfileRepository filterProfileRepository;
    private final MatchRepository matchRepository;
    private final FetcherRegistry fetcherRegistry;
    private final MatchScorer matchScorer;
    /** Own proxy, so fetchAllCompanies gets a real transaction per company. */
    private final ObjectProvider<FetchScheduler> self;

    /**
     * Outcome of one company fetch — feeds the manual "Check now" endpoint's
     * user feedback ("Checked just now; 3 new matches" / "No new roles").
     * newMatchesForOwner counts only matches created for the watching user
     * who owns this WatchedCompany row, not for other users on the same board.
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
        List<WatchedCompany> activeCompanies =
                watchedCompanyRepository.findByStatus(WatchedCompany.CompanyStatus.ACTIVE);

        log.info("Fetch cycle starting — {} active companies", activeCompanies.size());

        for (WatchedCompany company : activeCompanies) {
            try {
                // Through the proxy, not this.fetchCompany(...) — self-invocation
                // would skip @Transactional and put us back in one big transaction.
                self.getObject().fetchCompany(company);
            } catch (Exception e) {
                // Belt-and-braces: fetchCompany already converts board failures
                // into FAILED health. This catches anything else (a DB error mid
                // company) so the remaining companies still get their turn —
                // "a fetch error for one company never stops the next".
                log.error("Fetch cycle: {} failed, continuing with the rest",
                        company.getCompanyName(), e);
            }
        }

        log.info("Fetch cycle complete");
    }

    /**
     * Fetch one company now — shared by the scheduled cycle above and the
     * manual POST /watchlist/{id}/fetch endpoint (same dedup + scoring flow).
     * One transaction per company, whether called from the cycle or the endpoint.
     */
    @Transactional
    public FetchResult fetchCompany(WatchedCompany company) {
        Optional<AtsFetcher> fetcher = fetcherRegistry.getFetcher(company.getAtsPlatform());

        if (fetcher.isEmpty()) {
            log.warn("No fetcher for {} ({})", company.getCompanyName(), company.getAtsPlatform());
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
                    company.getCompanyName(), company.getAtsPlatform(), e.getMessage(), e);
            return recordFailure(company, summarize(e));
        }

        int newCount = 0;
        int ownerMatches = 0;

        for (Job job : fetchedJobs) {
            // Dedup: skip if we've seen this external_id for this company before
            if (jobRepository.existsByCompanyAndExternalId(company, job.getExternalId())) {
                continue;
            }

            // New job — save it
            Job saved = jobRepository.save(job);
            newCount++;

            // Run MatchScorer against all users watching this company
            ownerMatches += scoreForAllWatchers(company, saved);
        }

        // Update last fetched timestamp (also the manual-fetch cooldown anchor)
        company.setLastFetchedAt(Instant.now());
        company.setLastFetchStatus(WatchedCompany.FetchStatus.SUCCESS);
        company.setLastFetchError(null);
        watchedCompanyRepository.save(company);

        if (newCount > 0) {
            log.info("New jobs for {}: {}", company.getCompanyName(), newCount);
        }
        return FetchResult.success(newCount, ownerMatches);
    }

    /**
     * Stamp a failed attempt. lastFetchedAt still moves so the manual-fetch
     * cooldown applies to failures too — a broken board must not become a way to
     * hammer an ATS by holding down "Check now".
     */
    private FetchResult recordFailure(WatchedCompany company, String error) {
        company.setLastFetchedAt(Instant.now());
        company.setLastFetchStatus(WatchedCompany.FetchStatus.FAILED);
        company.setLastFetchError(error);
        watchedCompanyRepository.save(company);
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

    /** Returns how many of the created matches belong to the company row's owner. */
    private int scoreForAllWatchers(WatchedCompany company, Job job) {
        // Find all users watching this company
        // In Phase 1 with a single user this is trivial;
        // multi-tenant shape is already correct for when more users join
        List<User> watchers = watchedCompanyRepository
                .findAll()
                .stream()
                .filter(wc -> wc.getAtsPlatform() == company.getAtsPlatform()
                        && wc.getBoardToken().equals(company.getBoardToken()))
                .map(WatchedCompany::getUser)
                .distinct()
                .toList();

        int ownerMatches = 0;

        for (User user : watchers) {
            Optional<FilterProfile> profile = filterProfileRepository.findByUser(user);
            if (profile.isEmpty()) continue;

            // Skip if this user already has a Match for this job (shouldn't happen, but guard)
            if (matchRepository.existsByUserAndJob_Id(user, job.getId())) continue;

            MatchScorer.ScoredJob result = matchScorer.score(profile.get(), job);

            if (!result.excluded()) {
                Match match = new Match();
                match.setUser(user);
                match.setJob(job);
                match.setScore(result.score());
                match.setMatchedKeywords(result.matchedKeywords());
                match.setStatus(Match.MatchStatus.NEW);
                matchRepository.save(match);

                if (user.getId().equals(company.getUser().getId())) {
                    ownerMatches++;
                }
            }
        }
        return ownerMatches;
    }
}
