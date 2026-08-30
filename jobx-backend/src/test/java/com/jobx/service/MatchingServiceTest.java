package com.jobx.service;

import com.jobx.entity.*;
import com.jobx.repository.FilterProfileRepository;
import com.jobx.repository.JobRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.scorer.MatchScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression suite for the V4 fan-out and backfill — the behaviours whose
 * absence let the per-watch-row defect ship:
 *  - one match per (user, posting), never one per stored copy
 *  - PAUSED watchers receive nothing (ACTIVE-only watcher query)
 *  - a new watcher of an already-populated board gets a backfilled feed
 *
 * Uses the real MatchScorer (its own 29-test suite pins the scoring rules);
 * repositories are mocked.
 */
class MatchingServiceTest {

    private WatchedCompanyRepository watchedCompanyRepository;
    private FilterProfileRepository filterProfileRepository;
    private MatchRepository matchRepository;
    private JobRepository jobRepository;
    private MatchingService service;

    private Company company;
    private Job job;
    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        watchedCompanyRepository = mock(WatchedCompanyRepository.class);
        filterProfileRepository = mock(FilterProfileRepository.class);
        matchRepository = mock(MatchRepository.class);
        jobRepository = mock(JobRepository.class);
        service = new MatchingService(watchedCompanyRepository, filterProfileRepository,
                matchRepository, jobRepository, new MatchScorer());

        company = new Company();
        company.setId(UUID.randomUUID());
        company.setDisplayName("Razorpay");

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setTitle("Senior Java Engineer");
        job.setDescription("Spring Boot backend role");

        userA = user("a@jobx.dev");
        userB = user("b@jobx.dev");
    }

    private User user(String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        return user;
    }

    private FilterProfile profile(User owner, String... keywords) {
        FilterProfile profile = new FilterProfile();
        profile.setUser(owner);
        profile.setKeywords(List.of(keywords));
        return profile;
    }

    @Test
    void oneNewJobYieldsExactlyOneMatchPerWatchingUser() {
        when(watchedCompanyRepository.findActiveUsersByCompany(company))
                .thenReturn(List.of(userA, userB));
        when(filterProfileRepository.findByUser(userA)).thenReturn(Optional.of(profile(userA, "java")));
        when(filterProfileRepository.findByUser(userB)).thenReturn(Optional.of(profile(userB, "spring")));

        service.scoreForActiveWatchers(company, job, null);

        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository, times(2)).save(captor.capture());
        assertEquals(List.of(userA, userB),
                captor.getAllValues().stream().map(Match::getUser).toList());
    }

    @Test
    void watchersComeFromTheActiveOnlyQueryNeverFromFindAll() {
        // What makes PAUSED actually pause: the pre-V4 fan-out did a findAll()
        // and filtered by platform+token in Java, ignoring status entirely.
        when(watchedCompanyRepository.findActiveUsersByCompany(company)).thenReturn(List.of());

        service.scoreForActiveWatchers(company, job, null);

        verify(watchedCompanyRepository).findActiveUsersByCompany(company);
        verify(watchedCompanyRepository, never()).findAll();
        verifyNoInteractions(matchRepository);
    }

    @Test
    void ownerCountingOnlyCountsTheOwner() {
        when(watchedCompanyRepository.findActiveUsersByCompany(company))
                .thenReturn(List.of(userA, userB));
        when(filterProfileRepository.findByUser(userA)).thenReturn(Optional.of(profile(userA, "java")));
        when(filterProfileRepository.findByUser(userB)).thenReturn(Optional.of(profile(userB, "spring")));

        assertEquals(1, service.scoreForActiveWatchers(company, job, userA));
        // Scheduled cycle has no owner — matches are still created, none counted
        assertEquals(0, service.scoreForActiveWatchers(company, job, null));
    }

    @Test
    void scoreAndSaveIsIdempotentPerUserAndJob() {
        // Post-V4 a job id identifies a posting, so this guard finally works —
        // pre-V4 each user's private copy had its own id and it never fired.
        when(matchRepository.existsByUserAndJob_Id(userA, job.getId())).thenReturn(true);

        assertFalse(service.scoreAndSave(profile(userA, "java"), userA, job));
        verify(matchRepository, never()).save(any());
    }

    @Test
    void excludedJobsCreateNoMatch() {
        assertFalse(service.scoreAndSave(profile(userA, "python"), userA, job));
        verify(matchRepository, never()).save(any());
    }

    @Test
    void newWatcherOfPopulatedBoardGetsBackfilledFeed() {
        // The accident that used to hide this: pre-V4 the new watcher's own row
        // re-fetched and re-inserted every posting. With shared jobs the
        // backfill must be explicit or the second watcher's feed stays empty.
        Job otherJob = new Job();
        otherJob.setId(UUID.randomUUID());
        otherJob.setTitle("Sales Manager");
        otherJob.setDescription("Quota-carrying role");

        when(filterProfileRepository.findByUser(userB)).thenReturn(Optional.of(profile(userB, "java")));
        when(jobRepository.findByCompany(company)).thenReturn(List.of(job, otherJob));

        int created = service.backfillForWatcher(userB, company);

        // Only the java role matches userB's profile
        assertEquals(1, created);
        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(captor.capture());
        assertEquals(job, captor.getValue().getJob());
        assertEquals(userB, captor.getValue().getUser());
    }

    @Test
    void backfillWithoutAProfileCreatesNothing() {
        when(filterProfileRepository.findByUser(userB)).thenReturn(Optional.empty());

        assertEquals(0, service.backfillForWatcher(userB, company));
        verifyNoInteractions(jobRepository, matchRepository);
    }

    // ── rescoreForWatcher: the profile-save reconcile ────────────────────────
    // Closes the "PUT /profile/filter doesn't rescore" gap: found live
    // 2026-08-23, a profile saved minutes after a board's first fetch left the
    // feed at zero forever because scoring only ran on NEW jobs and watch-add.

    private WatchedCompany watch(User owner, WatchedCompany.CompanyStatus status) {
        WatchedCompany watch = new WatchedCompany();
        watch.setUser(owner);
        watch.setCompany(company);
        watch.setStatus(status);
        return watch;
    }

    private Match match(User owner, Job matchedJob, Match.MatchStatus status,
                        int score, List<String> matchedKeywords) {
        Match match = new Match();
        match.setUser(owner);
        match.setJob(matchedJob);
        match.setStatus(status);
        match.setScore(score);
        match.setMatchedKeywords(matchedKeywords);
        return match;
    }

    @Test
    void rescoreCreatesMatchesForJobsFetchedBeforeTheProfileExisted() {
        when(watchedCompanyRepository.findByUser(userA))
                .thenReturn(List.of(watch(userA, WatchedCompany.CompanyStatus.ACTIVE)));
        when(matchRepository.findByUserAndCompany(userA, company)).thenReturn(List.of());
        when(jobRepository.findByCompany(company)).thenReturn(List.of(job));

        MatchingService.RescoreResult result =
                service.rescoreForWatcher(userA, profile(userA, "java"));

        assertEquals(new MatchingService.RescoreResult(1, 0, 0), result);
        ArgumentCaptor<Match> captor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(captor.capture());
        assertEquals(job, captor.getValue().getJob());
        assertEquals(Match.MatchStatus.NEW, captor.getValue().getStatus());
        // "java" in title (70) + no experience data (full 30)
        assertEquals(100, captor.getValue().getScore());
    }

    @Test
    void rescoreDeletesNewMatchesTheProfileNowExcludes() {
        Match stale = match(userA, job, Match.MatchStatus.NEW, 100, List.of("java"));
        when(watchedCompanyRepository.findByUser(userA))
                .thenReturn(List.of(watch(userA, WatchedCompany.CompanyStatus.ACTIVE)));
        when(matchRepository.findByUserAndCompany(userA, company)).thenReturn(List.of(stale));
        when(jobRepository.findByCompany(company)).thenReturn(List.of(job));

        MatchingService.RescoreResult result =
                service.rescoreForWatcher(userA, profile(userA, "python"));

        assertEquals(new MatchingService.RescoreResult(0, 0, 1), result);
        verify(matchRepository).delete(stale);
        verify(matchRepository, never()).save(any());
    }

    @Test
    void rescoreKeepsUserTouchedMatchesEvenWhenTheyNoLongerPass() {
        // An APPLIED row is the user's own application record — a profile edit
        // must never erase it, even though the job no longer matches.
        Match applied = match(userA, job, Match.MatchStatus.APPLIED, 100, List.of("java"));
        when(watchedCompanyRepository.findByUser(userA))
                .thenReturn(List.of(watch(userA, WatchedCompany.CompanyStatus.ACTIVE)));
        when(matchRepository.findByUserAndCompany(userA, company)).thenReturn(List.of(applied));
        when(jobRepository.findByCompany(company)).thenReturn(List.of(job));

        MatchingService.RescoreResult result =
                service.rescoreForWatcher(userA, profile(userA, "python"));

        assertEquals(new MatchingService.RescoreResult(0, 0, 0), result);
        verify(matchRepository, never()).delete(any());
        verify(matchRepository, never()).save(any());
        assertEquals(100, applied.getScore());
    }

    @Test
    void rescoreRefreshesScoreAndKeywordsWhenTheProfileChanged() {
        Match existing = match(userA, job, Match.MatchStatus.SEEN, 100, List.of("java"));
        when(watchedCompanyRepository.findByUser(userA))
                .thenReturn(List.of(watch(userA, WatchedCompany.CompanyStatus.ACTIVE)));
        when(matchRepository.findByUserAndCompany(userA, company)).thenReturn(List.of(existing));
        when(jobRepository.findByCompany(company)).thenReturn(List.of(job));

        // Two keywords, only "java" hits the title: 70 * (2/4) = 35, + 30 exp
        MatchingService.RescoreResult result =
                service.rescoreForWatcher(userA, profile(userA, "java", "kubernetes"));

        assertEquals(new MatchingService.RescoreResult(0, 1, 0), result);
        assertEquals(65, existing.getScore());
        assertEquals(List.of("java"), existing.getMatchedKeywords());
        assertEquals(Match.MatchStatus.SEEN, existing.getStatus());
        verify(matchRepository).save(existing);
    }

    @Test
    void rescoreLeavesAnUnchangedMatchUntouched() {
        Match unchanged = match(userA, job, Match.MatchStatus.NEW, 100, List.of("java"));
        when(watchedCompanyRepository.findByUser(userA))
                .thenReturn(List.of(watch(userA, WatchedCompany.CompanyStatus.ACTIVE)));
        when(matchRepository.findByUserAndCompany(userA, company)).thenReturn(List.of(unchanged));
        when(jobRepository.findByCompany(company)).thenReturn(List.of(job));

        MatchingService.RescoreResult result =
                service.rescoreForWatcher(userA, profile(userA, "java"));

        assertEquals(new MatchingService.RescoreResult(0, 0, 0), result);
        verify(matchRepository, never()).save(any());
        verify(matchRepository, never()).delete(any());
    }

    @Test
    void rescoreSkipsPausedWatches() {
        when(watchedCompanyRepository.findByUser(userA))
                .thenReturn(List.of(watch(userA, WatchedCompany.CompanyStatus.PAUSED)));

        MatchingService.RescoreResult result =
                service.rescoreForWatcher(userA, profile(userA, "java"));

        assertEquals(new MatchingService.RescoreResult(0, 0, 0), result);
        verifyNoInteractions(jobRepository, matchRepository);
    }
}
