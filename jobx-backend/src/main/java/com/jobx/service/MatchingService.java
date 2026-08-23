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
import java.util.Optional;

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
     * fan-out above. (That they also aren't rescored when a profile is later
     * created/edited is the pre-existing "PUT /profile/filter doesn't rescore"
     * gap, unchanged by V4.)
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

        Match match = new Match();
        match.setUser(user);
        match.setJob(job);
        match.setScore(result.score());
        match.setMatchedKeywords(result.matchedKeywords());
        match.setStatus(Match.MatchStatus.NEW);
        matchRepository.save(match);
        return true;
    }
}
