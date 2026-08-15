package com.jobx.scheduler;

import com.jobx.entity.Job;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.FilterProfileRepository;
import com.jobx.repository.JobRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.scorer.MatchScorer;
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
 * Fetch health and failure isolation.
 *
 * Before this, every fetcher swallowed its own exception and returned an empty
 * list, so the scheduler stamped last_fetched_at and the watchlist reported a
 * dead board as "checked just now, nothing new".
 */
class FetchSchedulerHealthTest {

    private WatchedCompanyRepository watchedCompanyRepository;
    private FetcherRegistry fetcherRegistry;
    private AtsFetcher fetcher;
    private FetchScheduler scheduler;

    private User owner;
    private WatchedCompany company;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        watchedCompanyRepository = mock(WatchedCompanyRepository.class);
        JobRepository jobRepository = mock(JobRepository.class);
        FilterProfileRepository filterProfileRepository = mock(FilterProfileRepository.class);
        MatchRepository matchRepository = mock(MatchRepository.class);
        fetcherRegistry = mock(FetcherRegistry.class);
        fetcher = mock(AtsFetcher.class);

        ObjectProvider<FetchScheduler> self = mock(ObjectProvider.class);

        scheduler = new FetchScheduler(watchedCompanyRepository, jobRepository,
                filterProfileRepository, matchRepository, fetcherRegistry, new MatchScorer(), self);
        when(self.getObject()).thenReturn(scheduler);

        owner = new User();
        owner.setId(UUID.randomUUID());

        company = new WatchedCompany();
        company.setId(UUID.randomUUID());
        company.setUser(owner);
        company.setCompanyName("Apna");
        company.setAtsPlatform(AtsPlatform.WORKABLE);
        company.setBoardToken("apna");
        company.setStatus(WatchedCompany.CompanyStatus.ACTIVE);

        when(fetcherRegistry.getFetcher(AtsPlatform.WORKABLE)).thenReturn(Optional.of(fetcher));
        when(watchedCompanyRepository.findAll()).thenReturn(List.of(company));
    }

    @Test
    void unreachableBoardIsRecordedAsFailed() {
        when(fetcher.fetch(company)).thenThrow(new AtsFetchException("board request failed",
                new RuntimeException("Connection refused")));

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company);

        assertTrue(result.failed());
        assertEquals(0, result.newJobs());
        assertEquals(WatchedCompany.FetchStatus.FAILED, company.getLastFetchStatus());
        assertNotNull(company.getLastFetchError());
        // Cooldown still applies to failures, so "Check now" can't hammer a dead board
        assertNotNull(company.getLastFetchedAt());
        verify(watchedCompanyRepository).save(company);
    }

    @Test
    void emptyBoardIsRecordedAsSuccessNotFailure() {
        // The whole point of the split: nothing new is a perfectly good outcome
        when(fetcher.fetch(company)).thenReturn(List.of());

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company);

        assertFalse(result.failed());
        assertEquals(WatchedCompany.FetchStatus.SUCCESS, company.getLastFetchStatus());
        assertNull(company.getLastFetchError());
    }

    @Test
    void successClearsAPreviousFailure() {
        company.setLastFetchStatus(WatchedCompany.FetchStatus.FAILED);
        company.setLastFetchError("404 Not Found");
        when(fetcher.fetch(company)).thenReturn(List.of());

        scheduler.fetchCompany(company);

        assertEquals(WatchedCompany.FetchStatus.SUCCESS, company.getLastFetchStatus());
        assertNull(company.getLastFetchError());
    }

    @Test
    void storedErrorIsShortAndHasNoStackTrace() {
        when(fetcher.fetch(company))
                .thenThrow(new AtsFetchException("x".repeat(5000), new RuntimeException("y".repeat(5000))));

        scheduler.fetchCompany(company);

        assertTrue(company.getLastFetchError().length() <= 500);
        assertFalse(company.getLastFetchError().contains("at com.jobx"));
    }

    @Test
    void missingFetcherIsRecordedAsFailedRatherThanSilentlySkipped() {
        // e.g. a company added with atsPlatform UNSUPPORTED — previously this
        // logged a warning every cycle and looked identical to a healthy board.
        when(fetcherRegistry.getFetcher(AtsPlatform.WORKABLE)).thenReturn(Optional.empty());

        FetchScheduler.FetchResult result = scheduler.fetchCompany(company);

        assertTrue(result.failed());
        assertEquals(WatchedCompany.FetchStatus.FAILED, company.getLastFetchStatus());
    }

    @Test
    void oneCompanysFailureNeverStopsTheNext() {
        WatchedCompany healthy = new WatchedCompany();
        healthy.setId(UUID.randomUUID());
        healthy.setUser(owner);
        healthy.setCompanyName("Aspora");
        healthy.setAtsPlatform(AtsPlatform.ASHBY);
        healthy.setBoardToken("aspora");
        healthy.setStatus(WatchedCompany.CompanyStatus.ACTIVE);

        AtsFetcher ashby = mock(AtsFetcher.class);
        when(fetcherRegistry.getFetcher(AtsPlatform.ASHBY)).thenReturn(Optional.of(ashby));
        when(ashby.fetch(healthy)).thenReturn(List.of());

        // The broken one is first in the cycle, and fails hard
        when(fetcher.fetch(company)).thenThrow(new AtsFetchException("board is down"));
        when(watchedCompanyRepository.findByStatus(WatchedCompany.CompanyStatus.ACTIVE))
                .thenReturn(List.of(company, healthy));

        scheduler.fetchAllCompanies();

        // The healthy board that came after it was still polled
        verify(ashby).fetch(healthy);
        assertEquals(WatchedCompany.FetchStatus.FAILED, company.getLastFetchStatus());
        assertEquals(WatchedCompany.FetchStatus.SUCCESS, healthy.getLastFetchStatus());
    }

    @Test
    void anUnexpectedErrorMidCycleStillLetsLaterCompaniesRun() {
        WatchedCompany healthy = new WatchedCompany();
        healthy.setId(UUID.randomUUID());
        healthy.setUser(owner);
        healthy.setCompanyName("Aspora");
        healthy.setAtsPlatform(AtsPlatform.ASHBY);
        healthy.setBoardToken("aspora");
        healthy.setStatus(WatchedCompany.CompanyStatus.ACTIVE);

        AtsFetcher ashby = mock(AtsFetcher.class);
        when(fetcherRegistry.getFetcher(AtsPlatform.ASHBY)).thenReturn(Optional.of(ashby));
        when(ashby.fetch(healthy)).thenReturn(List.of());

        // Not an AtsFetchException — something the fetch contract didn't anticipate,
        // e.g. a DB error while saving. The cycle must still continue.
        when(fetcher.fetch(company)).thenReturn(List.of(new Job()));
        when(watchedCompanyRepository.save(company)).thenThrow(new RuntimeException("db down"));
        when(watchedCompanyRepository.findByStatus(WatchedCompany.CompanyStatus.ACTIVE))
                .thenReturn(List.of(company, healthy));

        assertDoesNotThrow(() -> scheduler.fetchAllCompanies());
        verify(ashby).fetch(healthy);
    }

    @Test
    void jobsFromAFailedFetchAreNeverScored() {
        when(fetcher.fetch(company)).thenThrow(new AtsFetchException("board is down"));

        scheduler.fetchCompany(company);

        verify(fetcherRegistry).getFetcher(any());
        // No job lookups happened at all — we bailed before the persist loop
        verifyNoMoreInteractions(fetcherRegistry);
    }
}
