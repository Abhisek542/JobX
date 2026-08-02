package com.jobx.scheduler;

import com.jobx.entity.*;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.*;
import com.jobx.scorer.MatchScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final WatchedCompanyRepository watchedCompanyRepository;
    private final JobRepository jobRepository;
    private final FilterProfileRepository filterProfileRepository;
    private final MatchRepository matchRepository;
    private final FetcherRegistry fetcherRegistry;
    private final MatchScorer matchScorer;

    /**
     * Outcome of one company fetch — feeds the manual "Check now" endpoint's
     * user feedback ("Checked just now; 3 new matches" / "No new roles").
     * newMatchesForOwner counts only matches created for the watching user
     * who owns this WatchedCompany row, not for other users on the same board.
     */
    public record FetchResult(int newJobs, int newMatchesForOwner) {
        public static final FetchResult EMPTY = new FetchResult(0, 0);
    }

    @Scheduled(fixedDelayString = "${jobx.fetch.interval-ms:1800000}") // 30min default
    @Transactional
    public void fetchAllCompanies() {
        List<WatchedCompany> activeCompanies =
                watchedCompanyRepository.findByStatus(WatchedCompany.CompanyStatus.ACTIVE);

        log.info("Fetch cycle starting — {} active companies", activeCompanies.size());

        for (WatchedCompany company : activeCompanies) {
            fetchCompany(company);
        }

        log.info("Fetch cycle complete");
    }

    /**
     * Fetch one company now — shared by the scheduled cycle above and the
     * manual POST /watchlist/{id}/fetch endpoint (same dedup + scoring flow).
     * @Transactional so the manual path gets its own transaction; scheduled
     * calls run inside fetchAllCompanies' transaction (self-invocation skips
     * the proxy there, which is fine).
     */
    @Transactional
    public FetchResult fetchCompany(WatchedCompany company) {
        Optional<AtsFetcher> fetcher = fetcherRegistry.getFetcher(company.getAtsPlatform());

        if (fetcher.isEmpty()) {
            log.warn("No fetcher for {} ({})", company.getCompanyName(), company.getAtsPlatform());
            return FetchResult.EMPTY;
        }

        List<Job> fetchedJobs = fetcher.get().fetch(company);
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
        watchedCompanyRepository.save(company);

        if (newCount > 0) {
            log.info("New jobs for {}: {}", company.getCompanyName(), newCount);
        }
        return new FetchResult(newCount, ownerMatches);
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
