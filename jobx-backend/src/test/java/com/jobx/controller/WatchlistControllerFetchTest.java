package com.jobx.controller;

import com.jobx.dto.ManualFetchResponse;
import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.UnsupportedBoardRequestRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.scheduler.FetchScheduler;
import com.jobx.resolve.CompanyResolver;
import com.jobx.service.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the manual "Check now" endpoint — ownership, ACTIVE-only,
 * and cooldown rules. The fetch itself is mocked; the shared fetch/dedup/
 * scoring flow is covered by FetchScheduler's live verification.
 *
 * V4 changed the cooldown's meaning: it rides on the SHARED
 * company.lastFetchedAt, so "checked recently" can be another user's doing.
 * A recent SUCCESS returns 200 with zeros (the board genuinely was just
 * checked); 429 is reserved for a recent FAILED attempt.
 */
class WatchlistControllerFetchTest {

    private static final long COOLDOWN_MS = 300_000;

    private WatchedCompanyRepository repository;
    private FetchScheduler fetchScheduler;
    private WatchlistController controller;

    private User owner;
    private Company company;
    private WatchedCompany watch;

    @BeforeEach
    void setUp() {
        repository = mock(WatchedCompanyRepository.class);
        fetchScheduler = mock(FetchScheduler.class);
        FetcherRegistry fetcherRegistry = mock(FetcherRegistry.class);
        when(fetcherRegistry.getFetcher(any())).thenReturn(Optional.empty());
        controller = new WatchlistController(repository, mock(CompanyRepository.class),
                mock(MatchRepository.class), mock(MatchingService.class),
                fetchScheduler, fetcherRegistry, mock(CompanyResolver.class),
                mock(UnsupportedBoardRequestRepository.class), COOLDOWN_MS);

        owner = new User();
        owner.setId(UUID.randomUUID());

        company = new Company();
        company.setId(UUID.randomUUID());
        company.setDisplayName("CRED");
        company.setAtsPlatform(AtsPlatform.GREENHOUSE);
        company.setBoardToken("cred");

        watch = new WatchedCompany();
        watch.setId(UUID.randomUUID());
        watch.setUser(owner);
        watch.setCompany(company);
        watch.setStatus(WatchedCompany.CompanyStatus.ACTIVE);
        when(repository.findById(watch.getId())).thenReturn(Optional.of(watch));
    }

    @Test
    void fetchesAndReturnsCounts() {
        company.setLastFetchedAt(null); // never fetched — no cooldown
        when(fetchScheduler.fetchCompany(company, owner))
                .thenReturn(FetchScheduler.FetchResult.success(4, 2));

        ManualFetchResponse response = controller.fetchNow(watch.getId(), owner);

        assertEquals(watch.getId(), response.companyId());
        assertEquals("CRED", response.companyName());
        assertEquals(4, response.newJobs());
        assertEquals(2, response.newMatches());
        assertNotNull(response.checkedAt());
    }

    @Test
    void staleLastFetchAllowsFetch() {
        company.setLastFetchedAt(Instant.now().minusMillis(COOLDOWN_MS + 1000));
        when(fetchScheduler.fetchCompany(company, owner)).thenReturn(FetchScheduler.FetchResult.EMPTY);

        ManualFetchResponse response = controller.fetchNow(watch.getId(), owner);

        assertEquals(0, response.newJobs());
        verify(fetchScheduler).fetchCompany(company, owner);
    }

    @Test
    void recentSuccessfulCheckReturns200WithZerosNot429() {
        // Another watcher (or the scheduler) checked this board moments ago and
        // it worked. "Checked just now, no new roles" is simply true — user B
        // must not eat a 429 for user A's click.
        company.setLastFetchedAt(Instant.now().minusSeconds(10));
        company.setLastFetchStatus(Company.FetchStatus.SUCCESS);

        ManualFetchResponse response = controller.fetchNow(watch.getId(), owner);

        assertEquals(0, response.newJobs());
        assertEquals(0, response.newMatches());
        assertEquals(company.getLastFetchedAt(), response.checkedAt());
        // The honest answer costs the ATS nothing
        verify(fetchScheduler, never()).fetchCompany(any(), any());
    }

    @Test
    void recentFailedCheckIsRejectedWith429() {
        // 429 survives for failures so a broken board can't be hammered by
        // holding down "Check now".
        company.setLastFetchedAt(Instant.now().minusSeconds(10));
        company.setLastFetchStatus(Company.FetchStatus.FAILED);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.fetchNow(watch.getId(), owner));

        assertEquals(429, ex.getStatusCode().value());
        verify(fetchScheduler, never()).fetchCompany(any(), any());
    }

    @Test
    void pausedCompanyIsRejectedWith409() {
        watch.setStatus(WatchedCompany.CompanyStatus.PAUSED);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.fetchNow(watch.getId(), owner));

        assertEquals(409, ex.getStatusCode().value());
        verify(fetchScheduler, never()).fetchCompany(any(), any());
    }

    @Test
    void failedFetchIs502NotAQuietZero() {
        // A board we couldn't reach must not come back as "no new roles" —
        // the scheduler has already recorded FAILED health for it.
        company.setLastFetchedAt(null);
        when(fetchScheduler.fetchCompany(company, owner)).thenReturn(FetchScheduler.FetchResult.failure());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.fetchNow(watch.getId(), owner));

        assertEquals(502, ex.getStatusCode().value());
    }

    @Test
    void anotherUsersCompanyIs404NotLeaked() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.fetchNow(watch.getId(), stranger));

        // 404 rather than 403 — same non-leak rule as the rest of the controller
        assertEquals(404, ex.getStatusCode().value());
        verify(fetchScheduler, never()).fetchCompany(any(), any());
    }
}
