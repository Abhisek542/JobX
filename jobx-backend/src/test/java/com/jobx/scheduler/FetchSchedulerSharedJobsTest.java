package com.jobx.scheduler;

import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.entity.User;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.JobRepository;
import com.jobx.service.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The V4 invariant: a posting is stored ONCE per board, no matter how many
 * users watch it. Pre-V4 the scheduler iterated per-user watch rows, so each
 * watcher's row re-inserted its own private copy of every job — the regression
 * these tests exist to prevent.
 */
class FetchSchedulerSharedJobsTest {

    private CompanyRepository companyRepository;
    private JobRepository jobRepository;
    private MatchingService matchingService;
    private AtsFetcher fetcher;
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
        ObjectProvider<FetchScheduler> self = mock(ObjectProvider.class);

        scheduler = new FetchScheduler(companyRepository, jobRepository,
                fetcherRegistry, matchingService, self);
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
        when(fetcher.fetch(company)).thenReturn(List.of(posting));
        when(jobRepository.save(posting)).thenReturn(posting);
    }

    @Test
    void aNewPostingIsSavedOnceAndScoredForAllWatchersInOnePass() {
        when(jobRepository.existsByCompanyAndExternalId(company, "4001")).thenReturn(false);

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, null);

        assertEquals(1, result.newJobs());
        verify(jobRepository, times(1)).save(posting);
        // The fan-out happens exactly once, from the single shared row —
        // MatchingService owns per-watcher scoring
        verify(matchingService, times(1)).scoreForActiveWatchers(company, posting, null);
    }

    @Test
    void aKnownPostingIsNeitherReinsertedNorRescored() {
        // Board-wide dedup: pre-V4 this exists() was scoped to one user's watch
        // row, so a second watcher's first cycle re-inserted the whole board.
        when(jobRepository.existsByCompanyAndExternalId(company, "4001")).thenReturn(true);

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, null);

        assertEquals(0, result.newJobs());
        assertFalse(result.failed());
        verify(jobRepository, never()).save(any(Job.class));
        verifyNoInteractions(matchingService);
    }

    @Test
    void cycleFetchesEachBoardOncePerCycleNotOncePerWatcher() {
        // findAllWithActiveWatchers is DISTINCT over companies — the scheduler
        // sees Razorpay once even with N watch rows on it.
        when(companyRepository.findAllWithActiveWatchers()).thenReturn(List.of(company));
        when(jobRepository.existsByCompanyAndExternalId(company, "4001")).thenReturn(false);

        scheduler.fetchAllCompanies();

        verify(fetcher, times(1)).fetch(company);
    }

    @Test
    void manualCheckCountsOnlyTheRequestersMatches() {
        User requester = new User();
        requester.setId(UUID.randomUUID());
        when(jobRepository.existsByCompanyAndExternalId(company, "4001")).thenReturn(false);
        when(matchingService.scoreForActiveWatchers(company, posting, requester)).thenReturn(1);

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company, requester);

        assertEquals(1, result.newMatchesForOwner());
        verify(matchingService).scoreForActiveWatchers(company, posting, requester);
    }
}
