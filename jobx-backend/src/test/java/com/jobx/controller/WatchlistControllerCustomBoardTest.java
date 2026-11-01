package com.jobx.controller;

import com.jobx.crawler.CrawlerTestSupport;
import com.jobx.dto.WatchedCompanyRequest;
import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.fetcher.custom.CustomPageFetcher;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.UnsupportedBoardRequestRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.resolve.CompanyResolver;
import com.jobx.resolve.SafeUrlFetcher;
import com.jobx.scheduler.FetchScheduler;
import com.jobx.service.MatchingService;
import com.jobx.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** PR E: adding a crawled (CUSTOM) board through POST /watchlist. */
class WatchlistControllerCustomBoardTest {

    private CompanyRepository companyRepository;
    private FetcherRegistry fetcherRegistry;
    private WatchlistController controller;
    private User user;

    @BeforeEach
    void setUp() {
        WatchedCompanyRepository watchedCompanyRepository = mock(WatchedCompanyRepository.class);
        companyRepository = mock(CompanyRepository.class);
        MatchRepository matchRepository = mock(MatchRepository.class);
        fetcherRegistry = mock(FetcherRegistry.class);
        WatchlistService watchlistService = new WatchlistService(companyRepository,
                watchedCompanyRepository, mock(MatchingService.class), matchRepository);
        controller = new WatchlistController(watchedCompanyRepository, companyRepository,
                matchRepository, watchlistService, mock(FetchScheduler.class), fetcherRegistry,
                mock(CompanyResolver.class), mock(UnsupportedBoardRequestRepository.class), 300_000);

        when(companyRepository.findByAtsPlatformAndBoardToken(any(), any())).thenReturn(Optional.empty());
        when(companyRepository.findByPlatformAndTokenIgnoreCase(any(), any())).thenReturn(Optional.empty());
        when(companyRepository.saveAndFlush(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));
        when(watchedCompanyRepository.saveAndFlush(any(WatchedCompany.class))).thenAnswer(inv -> inv.getArgument(0));

        user = new User();
        user.setId(UUID.randomUUID());
    }

    @Test
    void theCareersUrlIsCanonicalisedSoSpellingsShareOneBoard() {
        when(fetcherRegistry.getFetcher(AtsPlatform.CUSTOM)).thenReturn(Optional.of(mock(AtsFetcher.class)));

        controller.add(user, new WatchedCompanyRequest("Acme", AtsPlatform.CUSTOM,
                " HTTPS://Acme.example/careers/?utm_source=linkedin#roles "));

        verify(companyRepository).findByAtsPlatformAndBoardToken(AtsPlatform.CUSTOM, "https://acme.example/careers");
    }

    @Test
    void aBareHostGetsHttps() {
        when(fetcherRegistry.getFetcher(AtsPlatform.CUSTOM)).thenReturn(Optional.of(mock(AtsFetcher.class)));

        controller.add(user, new WatchedCompanyRequest("Acme", AtsPlatform.CUSTOM, "acme.example/careers"));

        verify(companyRepository).findByAtsPlatformAndBoardToken(AtsPlatform.CUSTOM, "https://acme.example/careers");
    }

    @Test
    void aNonWebLinkIsA400() {
        when(fetcherRegistry.getFetcher(AtsPlatform.CUSTOM)).thenReturn(Optional.of(mock(AtsFetcher.class)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.add(user,
                new WatchedCompanyRequest("Acme", AtsPlatform.CUSTOM, "ftp://acme.example/careers")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(companyRepository, never()).saveAndFlush(any(Company.class));
    }

    /** SSRF: a token pointing at a private address is refused with no request and no row. */
    @Test
    void aPrivateAddressTokenIsRefusedAtAdd() {
        CustomPageFetcher real = new CustomPageFetcher(
                CrawlerTestSupport.crawler(new SafeUrlFetcher(1000, 1000)), 25);
        when(fetcherRegistry.getFetcher(AtsPlatform.CUSTOM)).thenReturn(Optional.of(real));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.add(user,
                new WatchedCompanyRequest("Local", AtsPlatform.CUSTOM, "http://127.0.0.1/careers")));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().startsWith("we couldn't read job listings from that careers page"), e.getReason());
        assertTrue(e.getReason().contains("not a public web address"), e.getReason());
        verify(companyRepository, never()).saveAndFlush(any(Company.class));
    }
}
