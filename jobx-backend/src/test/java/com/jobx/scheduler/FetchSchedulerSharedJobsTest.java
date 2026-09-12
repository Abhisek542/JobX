package com.jobx.scheduler;

import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.entity.User;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetchFilter;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.ExpiredJobRepository;
import com.jobx.repository.JobRepository;
import com.jobx.service.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The V4 invariant: a posting is stored ONCE per board, no matter how many
 * users watch it. Pre-V4 the scheduler iterated per-user watch rows, so each
 * watcher's row re-inserted its own private copy of every job — the regression
 * these tests exist to prevent.
 */
class FetchSchedulerSharedJobsTest {

    private static final int TTL_DAYS = 6;

    private CompanyRepository companyRepository;
    private JobRepository jobRepository;
    private MatchingService matchingService;
    private AtsFetcher fetcher;
    private ExpiredJobRepository expiredJobRepository;
    private FetchScheduler scheduler;

    private Company company;
    private Job posting;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        jobRepository = mock(JobRepository.class);
        matchingService = mock(MatchingService.class);
        FetcherRegistry fetcherRegistry = mock(FetcherRegistry.class);
        fetcher = mock(AtsFetcher.class);
        expiredJobRepository = mock(ExpiredJobRepository.class);
        when(jobRepository.findExternalIdsByCompany(any())).thenReturn(Set.of());
        when(expiredJobRepository.findExternalIdsByCompany(any())).thenReturn(Set.of());
        ObjectProvider<FetchScheduler> self = mock(ObjectProvider.class);

        scheduler = new FetchScheduler(companyRepository, jobRepository, expiredJobRepository,
                fetcherRegistry, matchingService, self, TTL_DAYS);
        when(self.getObject()).thenReturn(scheduler);

        company = new Company();
        company.setId(UUID.randomUUID());
        company.setDisplayName("Razorpay");
        company.setAtsPlatform(AtsPlatform.GREENHOUSE);
        company.setBoardToken("razorpay");

        posting = new Job();
        posting.setExternalId("4001");
        posting.setTitle("Senior Backend Engineer (Java)");

        when(fetcherRegistry.getFetcher(AtsPlatform.GREENHOUSE)).thenReturn(Optional.of(fetcher));
        when(fetcher.fetch(eq(company), any())).thenReturn(List.of(posting));
        when(jobRepository.save(posting)).thenReturn(posting);
    }

    @Test
    void aNewPostingIsSavedOnceAndScoredForAllWatchersInOnePass() {
        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, null);

        assertEquals(1, result.newJobs());
        verify(jobRepository, times(1)).save(posting);
        // The fan-out happens exactly once, from the single shared row —
        // MatchingService owns per-watcher scoring
        verify(matchingService, times(1)).scoreForActiveWatchers(company, posting, null);
    }

    @Test
    void anExpiredPostingIsNotResurrectedWhileStillLiveOnTheBoard() {
        // The job row is gone (the TTL sweep deleted it), so the jobs-table
        // dedup says "never seen this". Boards keep stale postings listed for
        // weeks, so without the tombstone check this posting would be
        // re-inserted as brand new, re-notify every watcher, and be swept again
        // the next night — forever.
        when(expiredJobRepository.findExternalIdsByCompany(company)).thenReturn(Set.of("4001"));

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, null);

        assertEquals(0, result.newJobs());
        assertFalse(result.failed());
        verify(jobRepository, never()).save(any(Job.class));
        verifyNoInteractions(matchingService);
    }

    @Test
    void aTombstoneOnlyBlocksItsOwnPosting() {
        // The tombstone set is board-wide, so it must be matched on external id
        // and not allowed to suppress everything else the board returns.
        when(expiredJobRepository.findExternalIdsByCompany(company)).thenReturn(Set.of("9999"));

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, null);

        assertEquals(1, result.newJobs());
        verify(jobRepository).save(posting);
    }

    @Test
    void aKnownPostingIsNeitherReinsertedNorRescored() {
        // Board-wide dedup: pre-V4 this check was scoped to one user's watch
        // row, so a second watcher's first cycle re-inserted the whole board.
        when(jobRepository.findExternalIdsByCompany(company)).thenReturn(Set.of("4001"));

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, null);

        assertEquals(0, result.newJobs());
        assertFalse(result.failed());
        verify(jobRepository, never()).save(any(Job.class));
        verifyNoInteractions(matchingService);
    }

    /**
     * Bug #2: the tombstone check used to run only AFTER the fetcher returned,
     * so Workable/SmartRecruiters had already paid a detail call for every
     * tombstoned posting. The fetcher must be handed both halves up front.
     */
    @Test
    void theFetcherIsToldAboutStoredAndTombstonedPostingsBeforeItFetches() {
        when(jobRepository.findExternalIdsByCompany(company)).thenReturn(Set.of("stored"));
        when(expiredJobRepository.findExternalIdsByCompany(company)).thenReturn(Set.of("tombstoned"));
        // Return nothing: the filter's set is live and the scheduler adds each
        // saved id to it, which would muddy what the fetcher was handed.
        when(fetcher.fetch(eq(company), any())).thenReturn(List.of());

        Instant before = Instant.now();
        scheduler.fetchCompany(company, null);

        ArgumentCaptor<FetchFilter> filter = ArgumentCaptor.forClass(FetchFilter.class);
        verify(fetcher).fetch(eq(company), filter.capture());
        assertTrue(filter.getValue().isKnown("stored"));
        assertTrue(filter.getValue().isKnown("tombstoned"));
        assertFalse(filter.getValue().isKnown("4001"));

        // The cutoff is the retention TTL, the same clock the sweep uses.
        Instant expectedCutoff = before.minus(Duration.ofDays(TTL_DAYS));
        Instant cutoff = filter.getValue().postedCutoff();
        assertFalse(cutoff.isBefore(expectedCutoff));
        assertTrue(cutoff.isBefore(expectedCutoff.plusSeconds(60)));
    }

    @Test
    void dedupQueriesAreFixedPerBoardNotOnePerPosting() {
        Job second = new Job();
        second.setExternalId("4002");
        Job third = new Job();
        third.setExternalId("4003");
        when(fetcher.fetch(eq(company), any())).thenReturn(List.of(posting, second, third));
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(3, scheduler.fetchCompany(company, null).newJobs());

        // Stored ids: once for the fetcher, once more after the fetch.
        // Tombstones: once. None of it scales with the posting count.
        verify(jobRepository, times(2)).findExternalIdsByCompany(company);
        verify(expiredJobRepository, times(1)).findExternalIdsByCompany(company);
    }

    @Test
    void aPostingStoredByAConcurrentRunDuringTheFetchIsNotReinserted() {
        // The first read (handed to the fetcher) predates the fetch; by the time
        // it returns, a concurrent "Check now" has stored 4001. Saving it again
        // would trip UNIQUE (company_id, external_id) and roll back the board.
        when(jobRepository.findExternalIdsByCompany(company))
                .thenReturn(Set.of())
                .thenReturn(Set.of("4001"));

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, null);

        assertEquals(0, result.newJobs());
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void aPostingAlreadyPastTheTtlIsNeverIngested() {
        // A newly added board lists postings months old. Ingesting them would
        // show each as "New" to every watcher, and the sweep would delete them
        // all within a day.
        posting.setPlatformPostedAt(Instant.now().minus(Duration.ofDays(30)));

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, null);

        assertEquals(0, result.newJobs());
        verify(jobRepository, never()).save(any(Job.class));
        verifyNoInteractions(matchingService);
    }

    @Test
    void aFreshPostingIsIngested() {
        posting.setPlatformPostedAt(Instant.now().minus(Duration.ofDays(2)));

        assertEquals(1, scheduler.fetchCompany(company, null).newJobs());
    }

    @Test
    void aPostingWithNoDateIsIngested() {
        // Not every board publishes a date; the sweep's clock then falls back
        // to first_seen_at, which for a brand-new posting is now.
        posting.setPlatformPostedAt(null);

        assertEquals(1, scheduler.fetchCompany(company, null).newJobs());
    }

    @Test
    void cycleFetchesEachBoardOncePerCycleNotOncePerWatcher() {
        // findAllWithActiveWatchers is DISTINCT over companies — the scheduler
        // sees Razorpay once even with N watch rows on it.
        when(companyRepository.findAllWithActiveWatchers()).thenReturn(List.of(company));

        scheduler.fetchAllCompanies();

        verify(fetcher, times(1)).fetch(eq(company), any());
    }

    @Test
    void manualCheckCountsOnlyTheRequestersMatches() {
        User requester = new User();
        requester.setId(UUID.randomUUID());
        when(matchingService.scoreForActiveWatchers(company, posting, requester)).thenReturn(1);

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, requester);

        assertEquals(1, result.newMatchesForOwner());
        verify(matchingService).scoreForActiveWatchers(company, posting, requester);
    }
}
