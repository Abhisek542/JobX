package com.jobx.service;

import com.jobx.entity.*;
import com.jobx.repository.FilterProfileRepository;
import com.jobx.repository.JobRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.scorer.MatchScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Match creation — the one place a Match row is ever born.
 *
 * Extracted from FetchScheduler in the V4 (shared companies) rework because
 * two flows now need identical score-and-save semantics:
 *  - the fetch cycle fanning a NEW job out to every active watcher, and
 *  - POST /watchlist backfilling a NEW WATCHER against jobs that already
 *    exist. Pre-V4 that path worked by accident (the new watcher's own row
 *    re-fetched and re-inserted every job); with jobs stored once per board
 *    it must be explicit, or a second watcher's feed stays empty forever.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MatchingService {

    private final WatchedCompanyRepository watchedCompanyRepository;
    private final FilterProfileRepository filterProfileRepository;
    private final MatchRepository matchRepository;
    private final JobRepository jobRepository;
    private final MatchScorer matchScorer;

    /**
     * Score one new job for every ACTIVE watcher of its company.
     * Returns how many of the created matches belong to {@code owner}
     * (the manual "Check now" caller; pass null from the scheduled cycle).
     */
    public int scoreForActiveWatchers(Company company, Job job, User owner) {
        List<User> watchers = watchedCompanyRepository.findActiveUsersByCompany(company);

        int ownerMatches = 0;
        for (User user : watchers) {
            Optional<FilterProfile> profile = filterProfileRepository.findByUser(user);
            if (profile.isEmpty()) continue;

            if (scoreAndSave(profile.get(), user, job)
                    && owner != null && user.getId().equals(owner.getId())) {
                ownerMatches++;
            }
        }
        return ownerMatches;
    }

    /**
     * Score every stored job of a board for one user — called when a watch is
     * added to a company that already has jobs, so the new watcher gets a feed
     * immediately instead of only from postings that appear later.
     *
     * A user with no FilterProfile yet gets nothing here — same rule as the
     * fan-out above. (Since 2026-08-23 that's no longer a dead end: saving the
     * profile later triggers {@link #rescoreForWatcher}, which reconciles the
     * whole feed against jobs that already exist.)
     *
     * Returns the number of matches created.
     */
    @Transactional
    public int backfillForWatcher(User user, Company company) {
        Optional<FilterProfile> profile = filterProfileRepository.findByUser(user);
        if (profile.isEmpty()) return 0;

        int created = 0;
        for (Job job : jobRepository.findByCompany(company)) {
            if (scoreAndSave(profile.get(), user, job)) created++;
        }
        if (created > 0) {
            log.info("Backfilled {} matches for user {} on {}", created, user.getEmail(), company.getDisplayName());
        }
        return created;
    }

    /**
     * Score one job for one user and persist a Match if it isn't excluded.
     * Idempotent per (user, job): the existsBy guard — which finally works
     * post-V4, because a job id now identifies a posting rather than one
     * watch row's private copy — plus the DB's UNIQUE (user_id, job_id).
     */
    public boolean scoreAndSave(FilterProfile profile, User user, Job job) {
        if (matchRepository.existsByUserAndJob_Id(user, job.getId())) return false;

        MatchScorer.ScoredJob result = matchScorer.score(profile, job);
        if (result.excluded()) return false;

        saveNewMatch(user, job, result);
        return true;
    }

    public record RescoreResult(int created, int updated, int removed) {
        public boolean changedAnything() { return created + updated + removed > 0; }
    }

    /**
     * Reconcile one user's entire feed against a just-saved FilterProfile —
     * closes the "PUT /profile/filter doesn't rescore" gap: a profile created
     * or edited after a board was already fetched used to leave the feed
     * frozen forever, because matching only ran on NEW jobs and on watch-add
     * backfill (found live 2026-08-23: two Razorpay roles that plainly matched
     * sat unscored because the profile arrived three minutes after the fetch).
     *
     * Per job of each ACTIVE watch:
     *  - passes now, no match yet   → create (same as backfill)
     *  - passes now, match exists   → refresh score + matchedKeywords, keep
     *                                 status and createdAt
     *  - fails now, match is NEW    → delete; the user never engaged with it,
     *                                 and keeping it would show a role the
     *                                 profile now excludes
     *  - fails now, user touched it → keep untouched (stale score and all):
     *                                 SEEN/APPLIED/DISMISSED rows are the
     *                                 user's own history, not ours to erase
     *
     * PAUSED watches are skipped entirely — same rule as the fetch fan-out.
     */
    @Transactional
    public RescoreResult rescoreForWatcher(User user, FilterProfile profile) {
        int created = 0, updated = 0, removed = 0;

        for (WatchedCompany watch : watchedCompanyRepository.findByUser(user)) {
            if (watch.getStatus() != WatchedCompany.CompanyStatus.ACTIVE) continue;
            Company company = watch.getCompany();

            Map<UUID, Match> existingByJobId =
                    matchRepository.findByUserAndJob_Company(user, company).stream()
                            .collect(Collectors.toMap(m -> m.getJob().getId(), Function.identity()));

            for (Job job : jobRepository.findByCompany(company)) {
                MatchScorer.ScoredJob result = matchScorer.score(profile, job);
                Match match = existingByJobId.get(job.getId());

                if (result.excluded()) {
                    if (match != null && match.getStatus() == Match.MatchStatus.NEW) {
                        matchRepository.delete(match);
                        removed++;
                    }
                } else if (match == null) {
                    saveNewMatch(user, job, result);
                    created++;
                } else if (!Objects.equals(match.getScore(), result.score())
                        || !Objects.equals(match.getMatchedKeywords(), result.matchedKeywords())) {
                    match.setScore(result.score());
                    match.setMatchedKeywords(result.matchedKeywords());
                    matchRepository.save(match);
                    updated++;
                }
            }
        }

        RescoreResult result = new RescoreResult(created, updated, removed);
        if (result.changedAnything()) {
            log.info("Rescored feed for {} after profile save: {} created, {} updated, {} removed",
                    user.getEmail(), created, updated, removed);
        }
        return result;
    }

    private void saveNewMatch(User user, Job job, MatchScorer.ScoredJob result) {
        Match match = new Match();
        match.setUser(user);
        match.setJob(job);
        match.setScore(result.score());
        match.setMatchedKeywords(result.matchedKeywords());
        match.setStatus(Match.MatchStatus.NEW);
        matchRepository.save(match);
    }
}
