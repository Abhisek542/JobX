package com.jobx.controller;

import com.jobx.dto.ResolveRequest;
import com.jobx.dto.ResolveResponse;
import com.jobx.dto.UnsupportedBoardReportRequest;
import com.jobx.entity.UnsupportedBoardRequest;
import com.jobx.entity.User;
import com.jobx.enums.AtsPlatform;
import com.jobx.enums.ResolveMode;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.UnsupportedBoardRequestRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.resolve.CompanyResolver;
import com.jobx.scheduler.FetchScheduler;
import com.jobx.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The add-company mode reaches the resolver unchanged, and a dead end records
 * which mode it happened in, so demand splits into "a name with no board" and
 * "a real careers page on an ATS Jobx can't read".
 */
class WatchlistControllerResolveModeTest {

    private CompanyResolver companyResolver;
    private UnsupportedBoardRequestRepository unsupportedRepository;
    private WatchlistController controller;
    private User user;

    @BeforeEach
    void setUp() {
        companyResolver = mock(CompanyResolver.class);
        unsupportedRepository = mock(UnsupportedBoardRequestRepository.class);
        controller = new WatchlistController(mock(WatchedCompanyRepository.class),
                mock(CompanyRepository.class), mock(MatchRepository.class),
                mock(WatchlistService.class), mock(FetchScheduler.class), mock(FetcherRegistry.class),
                companyResolver, unsupportedRepository, 300_000);

        user = new User();
        user.setId(UUID.randomUUID());
    }

    @Test
    void passesTheModeToTheResolver() {
        ResolveResponse empty = new ResolveResponse(List.of(), null);
        when(companyResolver.resolve(user, ResolveMode.URL, "razorpay.com/careers")).thenReturn(empty);

        assertSame(empty, controller.resolve(user,
                new ResolveRequest(ResolveMode.URL, "razorpay.com/careers")));
        verify(companyResolver).resolve(user, ResolveMode.URL, "razorpay.com/careers");
    }

    @Test
    void recordsTheModeOfAnUnsupportedRequest() {
        controller.reportUnsupported(user,
                new UnsupportedBoardReportRequest("acme.com/careers", AtsPlatform.WORKDAY, ResolveMode.URL));

        ArgumentCaptor<UnsupportedBoardRequest> saved = ArgumentCaptor.forClass(UnsupportedBoardRequest.class);
        verify(unsupportedRepository).save(saved.capture());
        assertEquals(ResolveMode.URL, saved.getValue().getMode());
        assertEquals(AtsPlatform.WORKDAY, saved.getValue().getPlatformHint());
        assertEquals("acme.com/careers", saved.getValue().getQuery());
    }

    /** An older client that omits the mode still records the demand. */
    @Test
    void aMissingModeIsStillRecorded() {
        controller.reportUnsupported(user, new UnsupportedBoardReportRequest("Zoho", null, null));

        ArgumentCaptor<UnsupportedBoardRequest> saved = ArgumentCaptor.forClass(UnsupportedBoardRequest.class);
        verify(unsupportedRepository).save(saved.capture());
        assertNull(saved.getValue().getMode());
    }
}
