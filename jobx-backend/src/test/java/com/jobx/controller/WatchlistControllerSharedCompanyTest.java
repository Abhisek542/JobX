package com.jobx.controller;

import com.jobx.dto.WatchedCompanyRequest;
import com.jobx.dto.WatchedCompanyResponse;
import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.UnsupportedBoardRequestRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.scheduler.FetchScheduler;
import com.jobx.resolve.CompanyResolver;
import com.jobx.service.MatchingService;
import com.jobx.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V4 add/remove semantics — the two flows whose behaviour the shared-companies
 * rework changed most:
 *  - add: get-or-create the shared Company (first adder's name is canonical),
 *    then BACKFILL the new watcher against jobs the board already has
 *  - remove: delete only this user's matches; the shared company and its jobs
 *    are untouched (pre-V4 the cascade wiped other users' matches — the
 *    cross-tenant deletion this whole rework exists to prevent)
 */
class WatchlistControllerSharedCompanyTest {

    private WatchedCompanyRepository watchedCompanyRepository;
    private CompanyRepository companyRepository;
    private MatchRepository matchRepository;
    private MatchingService matchingService;
    private FetcherRegistry fetcherRegistry;
    private WatchlistController controller;

    private User user;
    private Company existingCompany;

    @BeforeEach
    void setUp() {
        watchedCompanyRepository = mock(WatchedCompanyRepository.class);
        companyRepository = mock(CompanyRepository.class);
        matchRepository = mock(MatchRepository.class);
        matchingService = mock(MatchingService.class);
        fetcherRegistry = mock(FetcherRegistry.class);
        when(fetcherRegistry.getFetcher(any())).thenReturn(Optional.empty());
        // A real WatchlistService, not a mock: the BUG_REPORT #6 fix moved the two
        // write transactions out of the controller, but they are still exactly the
        // behaviour these tests are about.
        WatchlistService watchlistService = new WatchlistService(companyRepository,
                watchedCompanyRepository, matchingService);
        controller = new WatchlistController(watchedCompanyRepository, companyRepository,
                matchRepository, watchlistService, mock(FetchScheduler.class), fetcherRegistry,
                mock(CompanyResolver.class), mock(UnsupportedBoardRequestRepository.class), 300_000);

        user = new User();
        user.setId(UUID.randomUUID());

        existingCompany = new Company();
        existingCompany.setId(UUID.randomUUID());
        existingCompany.setDisplayName("Razorpay");
        existingCompany.setAtsPlatform(AtsPlatform.GREENHOUSE);
        existingCompany.setBoardToken("razorpay");

        when(watchedCompanyRepository.saveAndFlush(any(WatchedCompany.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void addingAnAlreadyWatchedBoardJoinsItAndDiscardsTheTypedName() {
        when(companyRepository.findByAtsPlatformAndBoardToken(AtsPlatform.GREENHOUSE, "razorpay"))
                .thenReturn(Optional.of(existingCompany));

        WatchedCompanyResponse response = controller.add(user,
                new WatchedCompanyRequest("Razorpay India", AtsPlatform.GREENHOUSE, "razorpay"));

        // Canonical name wins — the second user's label is discarded, so no
        // per-user text ever leaks into another user's feed
        assertEquals("Razorpay", response.companyName());
        verify(companyRepository, never()).saveAndFlush(any(Company.class));
    }

    @Test
    void firstWatcherCreatesTheCompanyWithTheirName() {
        when(companyRepository.findByAtsPlatformAndBoardToken(AtsPlatform.GREENHOUSE, "razorpay"))
                .thenReturn(Optional.empty());
        when(companyRepository.saveAndFlush(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.add(user, new WatchedCompanyRequest("Razorpay", AtsPlatform.GREENHOUSE, "razorpay"));

        ArgumentCaptor<Company> captor = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).saveAndFlush(captor.capture());
        assertEquals("Razorpay", captor.getValue().getDisplayName());
        assertEquals("razorpay", captor.getValue().getBoardToken());
    }

    @Test
    void addingAWatchBackfillsTheFeedFromExistingJobs() {
        // Pre-V4 a new watcher's feed filled itself by accident (their own row
        // re-fetched and re-inserted the whole board). With shared jobs the
        // backfill must be explicit — without it a second watcher sees nothing
        // until the board posts a NEW role.
        when(companyRepository.findByAtsPlatformAndBoardToken(AtsPlatform.GREENHOUSE, "razorpay"))
                .thenReturn(Optional.of(existingCompany));

        controller.add(user, new WatchedCompanyRequest("Razorpay", AtsPlatform.GREENHOUSE, "razorpay"));

        verify(matchingService).backfillForWatcher(user, existingCompany);
    }

    /**
     * BUG_REPORT #6. The board-validation ATS round trip must happen before any
     * row is written, because that is what lets it run outside a transaction —
     * the add used to be one @Transactional method and held a pool connection
     * for the whole call. Ordering is the only part of that a Mockito test can
     * see (nothing here loads a Spring context), and it is the part that breaks
     * first if the writes creep back in front of the validation.
     */
    @Test
    void theBoardIsValidatedBeforeAnythingIsWritten() {
        AtsFetcher fetcher = mock(AtsFetcher.class);
        when(fetcherRegistry.getFetcher(AtsPlatform.SMARTRECRUITERS))
                .thenReturn(Optional.of(fetcher));
        when(companyRepository.findByAtsPlatformAndBoardToken(
                AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED")).thenReturn(Optional.empty());
        when(companyRepository.saveAndFlush(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.add(user, new WatchedCompanyRequest(
                "PhonePe", AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED"));

        InOrder inOrder = inOrder(fetcher, companyRepository, watchedCompanyRepository);
        inOrder.verify(fetcher).validateBoard(any(Company.class));
        inOrder.verify(companyRepository).saveAndFlush(any(Company.class));
        inOrder.verify(watchedCompanyRepository).saveAndFlush(any(WatchedCompany.class));
    }

    /**
     * Two users adding the same brand-new board in the same instant. The loser
     * trips UNIQUE (ats_platform, board_token) — but since #6 split the insert
     * into its own transaction, that transaction is over by the time we see the
     * violation, so the loser can simply join the winner's row instead of
     * failing the request outright.
     */
    @Test
    void losingTheCompanyCreateRaceJoinsTheWinnersRow() {
        when(companyRepository.findByAtsPlatformAndBoardToken(AtsPlatform.GREENHOUSE, "razorpay"))
                .thenReturn(Optional.empty())                    // nobody has it yet
                .thenReturn(Optional.of(existingCompany));       // the winner got there first
        when(companyRepository.saveAndFlush(any(Company.class)))
                .thenThrow(new DataIntegrityViolationException("uq_companies_platform_token"));

        WatchedCompanyResponse response = controller.add(user,
                new WatchedCompanyRequest("Razorpay India", AtsPlatform.GREENHOUSE, "razorpay"));

        assertEquals("Razorpay", response.companyName());
        verify(matchingService).backfillForWatcher(user, existingCompany);
    }

    @Test
    void removingAWatchDeletesOnlyThisUsersMatchesAndNeverTheSharedJobs() {
        WatchedCompany watch = new WatchedCompany();
        watch.setId(UUID.randomUUID());
        watch.setUser(user);
        watch.setCompany(existingCompany);
        when(watchedCompanyRepository.findById(watch.getId())).thenReturn(Optional.of(watch));

        controller.remove(watch.getId(), user);

        verify(matchRepository).deleteByUserAndCompany(user, existingCompany);
        verify(watchedCompanyRepository).delete(watch);
        // The shared board outlives any one watcher — other users' matches
        // point at its jobs
        verify(companyRepository, never()).delete(any(Company.class));
        verifyNoMoreInteractions(companyRepository);
    }
}
