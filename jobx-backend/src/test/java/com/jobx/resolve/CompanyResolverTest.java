package com.jobx.resolve;

import com.jobx.dto.ResolveResponse;
import com.jobx.dto.ResolvedBoardResponse;
import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.fetcher.FixtureSupport;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.JobRepository;
import com.jobx.repository.WatchedCompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Strategy ordering, and the guarantee that nothing is ever offered without
 * evidence behind it.
 *
 * The four strategies are cheapest-and-most-certain first, and each is only
 * reached because the ones before it found nothing. The two middle ones are
 * complementary rather than alternatives, which these tests show directly:
 * sniffing recovers Razorpay's unguessable token from real page markup, and
 * probing recovers Atlan, whose page names Ashby but never names the board.
 */
class CompanyResolverTest {

    private CompanyRepository companyRepository;
    private WatchedCompanyRepository watchedCompanyRepository;
    private JobRepository jobRepository;
    private FetcherRegistry fetcherRegistry;
    private SafeUrlFetcher safeUrlFetcher;
    private BoardProbe boardProbe;
    private CompanyResolver resolver;

    private User user;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        watchedCompanyRepository = mock(WatchedCompanyRepository.class);
        jobRepository = mock(JobRepository.class);
        fetcherRegistry = mock(FetcherRegistry.class);
        safeUrlFetcher = mock(SafeUrlFetcher.class);
        boardProbe = mock(BoardProbe.class);

        when(companyRepository.searchByNameOrToken(anyString(), any())).thenReturn(List.of());
        when(companyRepository.findByPlatformAndTokenIgnoreCase(any(), anyString()))
                .thenReturn(Optional.empty());
        when(fetcherRegistry.getFetcher(any())).thenReturn(Optional.empty());
        when(safeUrlFetcher.fetch(anyString())).thenReturn(Optional.empty());
        when(boardProbe.probe(any(), any())).thenReturn(List.of());
        // Catalog rows are assumed to have stored jobs unless a test says
        // otherwise; a row with none falls back to a live board preview.
        when(jobRepository.countByCompany(any())).thenReturn(7L);
        when(jobRepository.findTitlesByCompany(any(), any()))
                .thenReturn(List.of("Backend Engineer"));

        resolver = new CompanyResolver(companyRepository, watchedCompanyRepository, jobRepository,
                fetcherRegistry, safeUrlFetcher, boardProbe, 4, 3, 8);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("resolver@jobx.dev");
    }

    private AtsFetcher stubBoard(AtsPlatform platform, BoardPreview preview) {
        AtsFetcher fetcher = mock(AtsFetcher.class);
        when(fetcher.previewBoard(any())).thenReturn(preview);
        when(fetcherRegistry.getFetcher(platform)).thenReturn(Optional.of(fetcher));
        return fetcher;
    }

    private static Company company(AtsPlatform platform, String token, String name) {
        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setAtsPlatform(platform);
        company.setBoardToken(token);
        company.setDisplayName(name);
        return company;
    }

    // ---------------------------------------------------------------- catalog

    /**
     * A board Jobx already knows needs no network at all, and its evidence comes
     * from jobs already in Postgres.
     */
    @Test
    void catalogHitShortCircuitsEverythingElse() {
        Company razorpay = company(AtsPlatform.GREENHOUSE, "razorpaysoftwareprivatelimited", "Razorpay");
        when(companyRepository.searchByNameOrToken(eq("Razorpay"), any())).thenReturn(List.of(razorpay));
        when(jobRepository.countByCompany(razorpay)).thenReturn(25L);
        when(jobRepository.findTitlesByCompany(eq(razorpay), any()))
                .thenReturn(List.of("Senior Backend Engineer", "Product Designer"));

        ResolveResponse response = resolver.resolve(user, "Razorpay");

        ResolvedBoardResponse candidate = response.candidates().get(0);
        assertEquals("CATALOG", candidate.source());
        assertEquals("razorpaysoftwareprivatelimited", candidate.boardToken());
        assertEquals(25, candidate.jobCount());
        assertEquals(List.of("Senior Backend Engineer", "Product Designer"), candidate.sampleTitles());

        verifyNoInteractions(safeUrlFetcher);
        verifyNoInteractions(boardProbe);
    }

    @Test
    void catalogHitFlagsBoardsTheUserAlreadyWatches() {
        Company groww = company(AtsPlatform.GREENHOUSE, "groww", "Groww");
        when(companyRepository.searchByNameOrToken(anyString(), any())).thenReturn(List.of(groww));
        when(watchedCompanyRepository.existsByUserAndCompany(user, groww)).thenReturn(true);

        assertTrue(resolver.resolve(user, "Groww").candidates().get(0).alreadyWatched());
    }

    /**
     * PhonePe's Greenhouse board went from 68 jobs to a hard 404 in six days. A
     * board in that state must never outrank a healthy one with a similar name.
     */
    @Test
    void failedBoardsSinkBelowHealthyOnes() {
        Company dead = company(AtsPlatform.GREENHOUSE, "phonepe", "PhonePe");
        dead.setLastFetchStatus(Company.FetchStatus.FAILED);
        Company alive = company(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED", "PhonePe Limited");
        alive.setLastFetchStatus(Company.FetchStatus.SUCCESS);
        when(companyRepository.searchByNameOrToken(anyString(), any())).thenReturn(List.of(dead, alive));

        List<ResolvedBoardResponse> candidates = resolver.resolve(user, "PhonePe").candidates();

        assertEquals("PHONEPELIMITED", candidates.get(0).boardToken());
        assertEquals("phonepe", candidates.get(1).boardToken());
    }

    /** A one-character query would match most of the table. */
    @Test
    void catalogIgnoresVeryShortQueries() {
        resolver.search(user, "a");
        verify(companyRepository, never()).searchByNameOrToken(anyString(), any());
    }


    /**
     * A seeded catalog entry has no stored jobs yet, and offering it with "0 open
     * roles" and no titles would be a candidate with nothing behind it — the one
     * thing this flow exists to avoid. So the board itself is asked.
     */
    @Test
    void aCatalogRowWithNoStoredJobsAsksTheBoardForEvidence() {
        Company atlan = company(AtsPlatform.ASHBY, "atlan", "Atlan");
        when(companyRepository.searchByNameOrToken(anyString(), any())).thenReturn(List.of(atlan));
        when(jobRepository.countByCompany(atlan)).thenReturn(0L);
        stubBoard(AtsPlatform.ASHBY, new BoardPreview(null, 6, List.of("Field Security Engineer")));

        ResolvedBoardResponse candidate = resolver.resolve(user, "Atlan").candidates().get(0);

        assertEquals("CATALOG", candidate.source());
        assertEquals("Atlan", candidate.companyName());
        assertEquals(6, candidate.jobCount());
        assertEquals(List.of("Field Security Engineer"), candidate.sampleTitles());
    }

    /** A known board that is now dead is not offered — it has no evidence left. */
    @Test
    void aCatalogRowWhoseBoardIsGoneIsNotOffered() {
        Company dead = company(AtsPlatform.GREENHOUSE, "phonepe", "PhonePe");
        when(companyRepository.searchByNameOrToken(anyString(), any())).thenReturn(List.of(dead));
        when(jobRepository.countByCompany(dead)).thenReturn(0L);
        AtsFetcher greenhouse = mock(AtsFetcher.class);
        when(greenhouse.previewBoard(any())).thenThrow(new AtsFetchException("404"));
        when(fetcherRegistry.getFetcher(AtsPlatform.GREENHOUSE)).thenReturn(Optional.of(greenhouse));

        assertTrue(resolver.resolve(user, "PhonePe").isEmpty());
    }

    // -------------------------------------------------------------------- URL

    @Test
    void aPastedAtsLinkIsReadDirectly() {
        stubBoard(AtsPlatform.LEVER, new BoardPreview(null, 14, List.of("Android Engineer")));

        ResolveResponse response = resolver.resolve(user, "https://jobs.lever.co/fampay");

        ResolvedBoardResponse candidate = response.candidates().get(0);
        assertEquals("URL", candidate.source());
        assertEquals(AtsPlatform.LEVER, candidate.atsPlatform());
        assertEquals("fampay", candidate.boardToken());
        assertEquals("https://jobs.lever.co/fampay", candidate.boardUrl());
        verifyNoInteractions(safeUrlFetcher);
    }

    /** A link can be stale; the later strategies may find where the company moved. */
    @Test
    void aParsedButDeadLinkFallsThroughInsteadOfBeingOffered() {
        AtsFetcher greenhouse = mock(AtsFetcher.class);
        when(greenhouse.previewBoard(any())).thenThrow(new AtsFetchException("404"));
        when(fetcherRegistry.getFetcher(AtsPlatform.GREENHOUSE)).thenReturn(Optional.of(greenhouse));

        ResolveResponse response = resolver.resolve(user, "https://boards.greenhouse.io/phonepe");

        assertTrue(response.isEmpty());
        verify(safeUrlFetcher).fetch("https://boards.greenhouse.io/phonepe");
    }

    // ------------------------------------------------------------------ sniff

    /**
     * The case that justifies fetching careers pages at all: no derivation from
     * "Razorpay" reaches razorpaysoftwareprivatelimited.
     */
    @Test
    void sniffsAnUnguessableTokenOutOfARealCareersPage() {
        when(safeUrlFetcher.fetch("https://razorpay.com/jobs/"))
                .thenReturn(Optional.of(FixtureSupport.fixture("careers-razorpay-excerpt.html")));
        stubBoard(AtsPlatform.GREENHOUSE, new BoardPreview(null, 25, List.of("Engineering Manager")));

        ResolvedBoardResponse candidate =
                resolver.resolve(user, "https://razorpay.com/jobs/").candidates().get(0);

        assertEquals("SNIFF", candidate.source());
        assertEquals("razorpaysoftwareprivatelimited", candidate.boardToken());
        assertEquals(25, candidate.jobCount());
        verifyNoInteractions(boardProbe);
    }

    /**
     * Atlan renders its board in JavaScript, so the page names Ashby without
     * naming the board. The hint is what turns the probe from five platforms
     * into one.
     */
    @Test
    void aPlatformHintNarrowsTheProbeToOnePlatform() {
        when(safeUrlFetcher.fetch("https://atlan.com/careers/"))
                .thenReturn(Optional.of(FixtureSupport.fixture("careers-atlan-excerpt.html")));
        when(boardProbe.probe(eq(List.of(AtsPlatform.ASHBY)), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.ASHBY, "atlan"),
                        new BoardPreview(null, 5, List.of("Senior Security Engineer")))));

        ResolveResponse response = resolver.resolve(user, "https://atlan.com/careers/");

        ResolvedBoardResponse candidate = response.candidates().get(0);
        assertEquals("PROBE", candidate.source());
        assertEquals(AtsPlatform.ASHBY, candidate.atsPlatform());
        assertEquals("atlan", candidate.boardToken());
        assertEquals(AtsPlatform.ASHBY, response.platformHint());
        verify(boardProbe).probe(List.of(AtsPlatform.ASHBY), List.of("atlan", "Atlan"));
    }

    // ------------------------------------------------------------------ probe

    @Test
    void probesEveryPlatformWhenThereIsNoHint() {
        when(boardProbe.probe(any(), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.LEVER, "fampay"),
                        new BoardPreview(null, 14, List.of("Android Engineer")))));

        ResolvedBoardResponse candidate = resolver.resolve(user, "FamPay").candidates().get(0);

        assertEquals("PROBE", candidate.source());
        assertEquals("fampay", candidate.boardToken());
        verify(boardProbe).probe(
                argThat(platforms -> platforms.size() == 5),
                eq(List.of("FamPay", "fampay", "Fampay")));
    }

    /** A plain name never triggers an outbound page fetch. */
    @Test
    void aCompanyNameIsNotTreatedAsAUrl() {
        resolver.resolve(user, "Razorpay");
        verifyNoInteractions(safeUrlFetcher);
    }

    // ----------------------------------------------------------- naming, misc

    /**
     * Only Workable and SmartRecruiters name the company; the other three carry
     * no name at all, so the token stands in rather than something invented.
     */
    @Test
    void prefersTheBoardsOwnNameThenTheCatalogsThenTheToken() {
        when(boardProbe.probe(any(), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED"),
                        new BoardPreview("PHONEPE LIMITED", 6, List.of("SRE")))));
        assertEquals("PHONEPE LIMITED",
                resolver.resolve(user, "PHONEPELIMITED").candidates().get(0).companyName());

        when(boardProbe.probe(any(), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.ASHBY, "atlan"),
                        new BoardPreview(null, 5, List.of("Engineer")))));
        when(companyRepository.findByPlatformAndTokenIgnoreCase(AtsPlatform.ASHBY, "atlan"))
                .thenReturn(Optional.of(company(AtsPlatform.ASHBY, "atlan", "Atlan")));
        assertEquals("Atlan", resolver.resolve(user, "atlan").candidates().get(0).companyName());

        when(companyRepository.findByPlatformAndTokenIgnoreCase(AtsPlatform.ASHBY, "atlan"))
                .thenReturn(Optional.empty());
        assertEquals("atlan", resolver.resolve(user, "atlan").candidates().get(0).companyName());
    }

    /** Nothing found is a normal answer — plenty of portals have no public API. */
    @Test
    void anEmptyResultIsAnHonestAnswer() {
        ResolveResponse response = resolver.resolve(user, "Some Company On Workday");
        assertTrue(response.isEmpty());
        assertNull(response.platformHint());
    }

    @Test
    void blankQueryResolvesToNothingWithoutAnyWork() {
        assertTrue(resolver.resolve(user, "   ").isEmpty());
        assertTrue(resolver.resolve(user, null).isEmpty());
        verifyNoInteractions(boardProbe);
        verifyNoInteractions(safeUrlFetcher);
    }

    /** Resolution is read-only: watching a board is a separate, explicit act. */
    @Test
    void neverWritesAnything() {
        when(boardProbe.probe(any(), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.LEVER, "fampay"),
                        new BoardPreview(null, 14, List.of("Android Engineer")))));

        resolver.resolve(user, "FamPay");

        verify(companyRepository, never()).save(any());
        verify(companyRepository, never()).saveAndFlush(any());
        verify(watchedCompanyRepository, never()).save(any());
    }

    /**
     * Someone who pastes a file:// URL has unambiguously given us a URL. Saying
     * "only http and https links are checked" is a better answer than treating it
     * as a company name and reporting that no board was found.
     */
    @Test
    void anExplicitSchemeIsTreatedAsAUrlEvenWhenWeWillNotFetchIt() {
        when(safeUrlFetcher.fetch("file:///etc/passwd"))
                .thenThrow(new SafeUrlFetcher.UnsafeUrlException("only http and https links can be checked"));

        assertThrows(SafeUrlFetcher.UnsafeUrlException.class,
                () -> resolver.resolve(user, "file:///etc/passwd"));
    }
}
