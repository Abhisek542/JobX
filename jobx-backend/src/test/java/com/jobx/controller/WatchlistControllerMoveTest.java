package com.jobx.controller;

import com.jobx.dto.MoveWatchRequest;
import com.jobx.dto.WatchedCompanyRequest;
import com.jobx.dto.WatchedCompanyResponse;
import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.UnsupportedBoardRequestRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.resolve.CompanyResolver;
import com.jobx.scheduler.FetchScheduler;
import com.jobx.service.MatchingService;
import com.jobx.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PR D: POST /watchlist/{id}/move switches a user's watch to the proposed board,
 * and POST /watchlist remembers the careers page a board was found on.
 */
class WatchlistControllerMoveTest {

    private WatchedCompanyRepository watchedCompanyRepository;
    private CompanyRepository companyRepository;
    private MatchRepository matchRepository;
    private MatchingService matchingService;
    private AtsFetcher smartRecruiters;
    private WatchlistController controller;

    private User user;
    private Company phonepe;
    private WatchedCompany watch;

    @BeforeEach
    void setUp() {
        watchedCompanyRepository = mock(WatchedCompanyRepository.class);
        companyRepository = mock(CompanyRepository.class);
        matchRepository = mock(MatchRepository.class);
        matchingService = mock(MatchingService.class);
        FetcherRegistry fetcherRegistry = mock(FetcherRegistry.class);
        smartRecruiters = mock(AtsFetcher.class);
        when(fetcherRegistry.getFetcher(any())).thenReturn(Optional.of(mock(AtsFetcher.class)));
        when(fetcherRegistry.getFetcher(AtsPlatform.SMARTRECRUITERS)).thenReturn(Optional.of(smartRecruiters));
        when(fetcherRegistry.getFetcher(AtsPlatform.UNSUPPORTED)).thenReturn(Optional.empty());

        WatchlistService watchlistService = new WatchlistService(companyRepository,
                watchedCompanyRepository, matchingService, matchRepository);
        controller = new WatchlistController(watchedCompanyRepository, companyRepository,
                matchRepository, watchlistService, mock(FetchScheduler.class), fetcherRegistry,
                mock(CompanyResolver.class), mock(UnsupportedBoardRequestRepository.class), 300_000);

        user = new User();
        user.setId(UUID.randomUUID());

        phonepe = new Company();
        phonepe.setId(UUID.randomUUID());
        phonepe.setDisplayName("PhonePe");
        phonepe.setAtsPlatform(AtsPlatform.GREENHOUSE);
        phonepe.setBoardToken("phonepe");
        phonepe.setLastFetchStatus(Company.FetchStatus.FAILED);
        phonepe.setCareersUrl("https://phonepe.com/careers");
        phonepe.proposeMove(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED", Company.MoveReason.FAILING);

        watch = new WatchedCompany();
        watch.setId(UUID.randomUUID());
        watch.setUser(user);
        watch.setCompany(phonepe);
        watch.setStatus(WatchedCompany.CompanyStatus.PAUSED);
        when(watchedCompanyRepository.findById(watch.getId())).thenReturn(Optional.of(watch));
        when(watchedCompanyRepository.findByUserAndCompany(any(), any())).thenReturn(Optional.empty());
        when(watchedCompanyRepository.saveAndFlush(any(WatchedCompany.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(companyRepository.saveAndFlush(any(Company.class))).thenAnswer(inv -> {
            Company created = inv.getArgument(0);
            created.setId(UUID.randomUUID());
            return created;
        });
        when(smartRecruiters.previewBoard(any())).thenReturn(new BoardPreview("PhonePe", 6, List.of()));
    }

    private MoveWatchRequest proposed() {
        return new MoveWatchRequest(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED");
    }

    @Test
    void moveRepointsTheWatchToANewBoardAndBackfills() {
        WatchedCompanyResponse response = controller.move(watch.getId(), user, proposed());

        assertEquals(watch.getId(), response.id());           // same row, same id
        assertEquals(AtsPlatform.SMARTRECRUITERS, response.atsPlatform());
        assertEquals("PHONEPELIMITED", response.boardToken());
        assertEquals("PhonePe", response.companyName());       // old canonical name carried over
        assertEquals(WatchedCompany.CompanyStatus.PAUSED, response.status());
        assertNull(response.movedTo());

        ArgumentCaptor<Company> created = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).saveAndFlush(created.capture());
        assertEquals("https://phonepe.com/careers", created.getValue().getCareersUrl());

        // Same as unwatching the old board: this user's matches there go
        verify(matchRepository).deleteByUserAndCompany(user, phonepe);
        verify(matchingService).backfillForWatcher(user, created.getValue());
        // The old company row stays, like any orphan
        verify(companyRepository, never()).delete(any());
    }

    @Test
    void moveJoinsAnAlreadyKnownBoard() {
        Company existing = new Company();
        existing.setId(UUID.randomUUID());
        existing.setDisplayName("PhonePe Ltd");
        existing.setAtsPlatform(AtsPlatform.SMARTRECRUITERS);
        existing.setBoardToken("PHONEPELIMITED");
        existing.setCareersUrl("https://other.example/careers");
        when(companyRepository.findByAtsPlatformAndBoardToken(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED"))
                .thenReturn(Optional.of(existing));

        WatchedCompanyResponse response = controller.move(watch.getId(), user, proposed());

        assertEquals(existing.getId(), response.companyId());
        verify(companyRepository, never()).saveAndFlush(any(Company.class));
        // Its own careers page is kept
        verify(companyRepository, never()).fillCareersUrlIfMissing(any(), any());
    }

    @Test
    void moveWhenAlreadyWatchingTheTargetDropsTheOldRow() {
        Company existing = new Company();
        existing.setId(UUID.randomUUID());
        existing.setDisplayName("PhonePe");
        existing.setAtsPlatform(AtsPlatform.SMARTRECRUITERS);
        existing.setBoardToken("PHONEPELIMITED");
        when(companyRepository.findByAtsPlatformAndBoardToken(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED"))
                .thenReturn(Optional.of(existing));
        WatchedCompany other = new WatchedCompany();
        other.setId(UUID.randomUUID());
        other.setUser(user);
        other.setCompany(existing);
        other.setStatus(WatchedCompany.CompanyStatus.ACTIVE);
        when(watchedCompanyRepository.findByUserAndCompany(user, existing)).thenReturn(Optional.of(other));

        WatchedCompanyResponse response = controller.move(watch.getId(), user, proposed());

        assertEquals(other.getId(), response.id());
        verify(watchedCompanyRepository).delete(watch);
        verify(matchingService, never()).backfillForWatcher(any(), any());
    }

    @Test
    void moveToABoardThatIsNoLongerProposedIs409() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.move(watch.getId(), user,
                        new MoveWatchRequest(AtsPlatform.LEVER, "phonepe")));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());

        phonepe.clearMoveProposal();
        e = assertThrows(ResponseStatusException.class, () ->
                controller.move(watch.getId(), user, proposed()));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verifyNoInteractions(matchRepository);
    }

    @Test
    void moveToABoardWithNoLiveRolesIs409AndChangesNothing() {
        when(smartRecruiters.previewBoard(any())).thenReturn(new BoardPreview(null, 0, List.of()));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.move(watch.getId(), user, proposed()));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());

        when(smartRecruiters.previewBoard(any())).thenThrow(new AtsFetchException("down"));
        assertThrows(ResponseStatusException.class, () ->
                controller.move(watch.getId(), user, proposed()));

        verifyNoInteractions(matchRepository);
        verify(watchedCompanyRepository, never()).saveAndFlush(any());
        assertSame(phonepe, watch.getCompany());
    }

    @Test
    void moveOnSomeoneElsesWatchIs404() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                controller.move(watch.getId(), stranger, proposed()));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void listShowsTheProposal() {
        when(watchedCompanyRepository.findByUser(user)).thenReturn(List.of(watch));

        WatchedCompanyResponse.MovedTo movedTo = controller.list(user).get(0).movedTo();

        assertEquals(AtsPlatform.SMARTRECRUITERS, movedTo.atsPlatform());
        assertEquals("PHONEPELIMITED", movedTo.boardToken());
        assertEquals("https://jobs.smartrecruiters.com/PHONEPELIMITED", movedTo.boardUrl());
        assertEquals(Company.MoveReason.FAILING, movedTo.reason());
    }

    // ---- careersUrl on POST /watchlist ----

    @Test
    void newCompanyStoresTheCareersUrl() {
        controller.add(user, new WatchedCompanyRequest("Razorpay", AtsPlatform.GREENHOUSE,
                "razorpaysoftwareprivatelimited", " https://razorpay.com/jobs "));

        ArgumentCaptor<Company> created = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).saveAndFlush(created.capture());
        assertEquals("https://razorpay.com/jobs", created.getValue().getCareersUrl());
    }

    @Test
    void existingCompanyGetsTheCareersUrlOnlyIfItHasNone() {
        Company razorpay = new Company();
        razorpay.setId(UUID.randomUUID());
        razorpay.setDisplayName("Razorpay");
        razorpay.setAtsPlatform(AtsPlatform.GREENHOUSE);
        razorpay.setBoardToken("razorpay");
        when(companyRepository.findByAtsPlatformAndBoardToken(AtsPlatform.GREENHOUSE, "razorpay"))
                .thenReturn(Optional.of(razorpay));

        controller.add(user, new WatchedCompanyRequest("Razorpay", AtsPlatform.GREENHOUSE,
                "razorpay", "https://razorpay.com/jobs"));
        verify(companyRepository).fillCareersUrlIfMissing(razorpay.getId(), "https://razorpay.com/jobs");

        reset(companyRepository);
        when(companyRepository.findByAtsPlatformAndBoardToken(AtsPlatform.GREENHOUSE, "razorpay"))
                .thenReturn(Optional.of(razorpay));
        when(watchedCompanyRepository.saveAndFlush(any(WatchedCompany.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        controller.add(new User(), new WatchedCompanyRequest("Razorpay", AtsPlatform.GREENHOUSE,
                "razorpay", "https://evil.example/"));
        verify(companyRepository, never()).fillCareersUrlIfMissing(any(), eq("https://evil.example/"));
    }

    @Test
    void noCareersUrlWritesNothing() {
        controller.add(user, new WatchedCompanyRequest("Razorpay", AtsPlatform.GREENHOUSE, "razorpay"));
        verify(companyRepository, never()).fillCareersUrlIfMissing(any(), any());
    }
}
