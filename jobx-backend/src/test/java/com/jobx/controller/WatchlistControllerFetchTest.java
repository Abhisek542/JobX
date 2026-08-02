package com.jobx.controller;

import com.jobx.dto.ManualFetchResponse;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.scheduler.FetchScheduler;
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
 */
class WatchlistControllerFetchTest {

    private static final long COOLDOWN_MS = 300_000;

    private WatchedCompanyRepository repository;
    private FetchScheduler fetchScheduler;
    private WatchlistController controller;

    private User owner;
    private WatchedCompany company;

    @BeforeEach
    void setUp() {
        repository = mock(WatchedCompanyRepository.class);
        fetchScheduler = mock(FetchScheduler.class);
        controller = new WatchlistController(repository, fetchScheduler, COOLDOWN_MS);

        owner = new User();
        owner.setId(UUID.randomUUID());

        company = new WatchedCompany();
        company.setId(UUID.randomUUID());
        company.setUser(owner);
        company.setCompanyName("CRED");
        company.setStatus(WatchedCompany.CompanyStatus.ACTIVE);
        when(repository.findById(company.getId())).thenReturn(Optional.of(company));
    }

    @Test
    void fetchesAndReturnsCounts() {
        company.setLastFetchedAt(null); // never fetched — no cooldown
        when(fetchScheduler.fetchCompany(company)).thenReturn(new FetchScheduler.FetchResult(4, 2));

        ManualFetchResponse response = controller.fetchNow(company.getId(), owner);

        assertEquals(company.getId(), response.companyId());
        assertEquals("CRED", response.companyName());
        assertEquals(4, response.newJobs());
        assertEquals(2, response.newMatches());
        assertNotNull(response.checkedAt());
    }

    @Test
    void staleLastFetchAllowsFetch() {
        company.setLastFetchedAt(Instant.now().minusMillis(COOLDOWN_MS + 1000));
        when(fetchScheduler.fetchCompany(company)).thenReturn(FetchScheduler.FetchResult.EMPTY);

        ManualFetchResponse response = controller.fetchNow(company.getId(), owner);

        assertEquals(0, response.newJobs());
        verify(fetchScheduler).fetchCompany(company);
    }

    @Test
    void recentFetchIsRejectedWith429() {
        company.setLastFetchedAt(Instant.now().minusSeconds(10));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.fetchNow(company.getId(), owner));

        assertEquals(429, ex.getStatusCode().value());
        verify(fetchScheduler, never()).fetchCompany(any());
    }

    @Test
    void pausedCompanyIsRejectedWith409() {
        company.setStatus(WatchedCompany.CompanyStatus.PAUSED);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.fetchNow(company.getId(), owner));

        assertEquals(409, ex.getStatusCode().value());
        verify(fetchScheduler, never()).fetchCompany(any());
    }

    @Test
    void anotherUsersCompanyIs404NotLeaked() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.fetchNow(company.getId(), stranger));

        // 404 rather than 403 — same non-leak rule as the rest of the controller
        assertEquals(404, ex.getStatusCode().value());
        verify(fetchScheduler, never()).fetchCompany(any());
    }
}
