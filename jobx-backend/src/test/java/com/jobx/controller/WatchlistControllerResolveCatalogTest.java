package com.jobx.controller;

import com.jobx.dto.ResolvedBoardResponse;
import com.jobx.entity.User;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.UnsupportedBoardRequestRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.resolve.CompanyResolver;
import com.jobx.scheduler.FetchScheduler;
import com.jobx.service.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GET /watchlist/resolve/catalog/{companyId} — the typeahead pick's evidence
 * (BUG_REPORT #5). The resolver owns the logic; this pins the 200/404 mapping
 * the dashboard branches on.
 */
class WatchlistControllerResolveCatalogTest {

    private CompanyResolver companyResolver;
    private WatchlistController controller;
    private User user;

    @BeforeEach
    void setUp() {
        companyResolver = mock(CompanyResolver.class);
        controller = new WatchlistController(mock(WatchedCompanyRepository.class),
                mock(CompanyRepository.class), mock(MatchRepository.class),
                mock(MatchingService.class), mock(FetchScheduler.class), mock(FetcherRegistry.class),
                companyResolver, mock(UnsupportedBoardRequestRepository.class), 300_000);

        user = new User();
        user.setId(UUID.randomUUID());
    }

    @Test
    void returnsTheBoardWithItsEvidence() {
        UUID companyId = UUID.randomUUID();
        ResolvedBoardResponse board = new ResolvedBoardResponse("CATALOG", AtsPlatform.GREENHOUSE,
                "razorpay", "Razorpay", "https://job-boards.greenhouse.io/razorpay", 24,
                List.of("Backend Engineer"), false);
        when(companyResolver.previewCatalog(user, companyId)).thenReturn(Optional.of(board));

        assertSame(board, controller.resolveCatalog(user, companyId));
    }

    @Test
    void notFoundWhenTheBoardHasNothingLive() {
        UUID companyId = UUID.randomUUID();
        when(companyResolver.previewCatalog(user, companyId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resolveCatalog(user, companyId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
