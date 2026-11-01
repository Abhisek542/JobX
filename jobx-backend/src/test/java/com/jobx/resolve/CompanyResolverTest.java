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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.jobx.enums.ResolveMode.NAME;
import static com.jobx.enums.ResolveMode.URL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * Strategy ordering per mode, and the guarantee that nothing is ever offered
 * without evidence behind it.
 *
 * NAME mode is catalog then probe and never fetches a page; URL mode is parse,
 * sniff, probe and never consults the catalog. Within a mode each step is only
 * reached because the ones before it found nothing. SNIFF and PROBE are
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
    private CareersLinkFollower follower;
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
        when(safeUrlFetcher.fetchPage(anyString())).thenReturn(Optional.empty());
        when(boardProbe.probe(any(), any())).thenReturn(List.of());
        // Catalog rows are assumed to have stored jobs unless a test says
        // otherwise; a row with none falls back to a live board preview.
        when(jobRepository.countByCompany(any())).thenReturn(7L);
        when(jobRepository.findTitlesByCompany(any(), any()))
                .thenReturn(List.of("Backend Engineer"));

        // A real follower over the mocked fetcher, so tests see every follow fetch.
        follower = new CareersLinkFollower(safeUrlFetcher, 3, 5000, 3);
        resolver = new CompanyResolver(companyRepository, watchedCompanyRepository, jobRepository,
                fetcherRegistry, safeUrlFetcher, boardProbe, follower, 4, 3, 8);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("resolver@jobx.dev");
    }

    @AfterEach
    void tearDown() {
        follower.shutdown();
    }

    /** The page {@code url} serves, as SafeUrlFetcher would return it with no redirect. */
    private void serve(String url, String html) {
        when(safeUrlFetcher.fetchPage(url))
                .thenReturn(Optional.of(new SafeUrlFetcher.FetchedPage(URI.create(url), html)));
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

        ResolveResponse response = resolver.resolve(user, NAME, "Razorpay");

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

        assertTrue(resolver.resolve(user, NAME, "Groww").candidates().get(0).alreadyWatched());
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

        List<ResolvedBoardResponse> candidates = resolver.resolve(user, NAME, "PhonePe").candidates();

        assertEquals("PHONEPELIMITED", candidates.get(0).boardToken());
        assertEquals("phonepe", candidates.get(1).boardToken());
    }

    /** A one-character query would match most of the table. */
    @Test
    void catalogIgnoresVeryShortQueries() {
        resolver.search(user, "a");
        verify(companyRepository, never()).searchByNameOrToken(anyString(), any());
    }

    /** A typed "%" would otherwise return the whole companies table. */
    @Test
    void catalogEscapesLikeWildcards() {
        resolver.search(user, "50%_off!");
        verify(companyRepository).searchByNameOrToken(eq("50!%!_off!!"), any());
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

        ResolvedBoardResponse candidate = resolver.resolve(user, NAME, "Atlan").candidates().get(0);

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

        assertTrue(resolver.resolve(user, NAME, "PhonePe").isEmpty());
    }

    // -------------------------------------------------------------------- URL

    @Test
    void aPastedAtsLinkIsReadDirectly() {
        stubBoard(AtsPlatform.LEVER, new BoardPreview(null, 14, List.of("Android Engineer")));

        ResolveResponse response = resolver.resolve(user, URL, "https://jobs.lever.co/fampay");

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

        ResolveResponse response = resolver.resolve(user, URL, "https://boards.greenhouse.io/phonepe");

        assertTrue(response.isEmpty());
        verify(safeUrlFetcher).fetchPage("https://boards.greenhouse.io/phonepe");
    }

    // ----------------------------------------------------------- empty boards

    /**
     * Greenhouse 404s an unknown token, so an empty preview of a token the user
     * pasted proves the board is real: it is offered with zero roles, so they
     * can watch the company until it starts hiring.
     */
    @Test
    void anEmptyBoardFromAPastedLinkIsOfferedOnAPlatformThat404sUnknownTokens() {
        AtsFetcher greenhouse = stubBoard(AtsPlatform.GREENHOUSE, new BoardPreview(null, 0, List.of()));
        when(greenhouse.distinguishesMissingBoard()).thenReturn(true);

        ResolveResponse response = resolver.resolve(user, URL, "https://boards.greenhouse.io/quietco");

        ResolvedBoardResponse candidate = response.candidates().get(0);
        assertEquals("URL", candidate.source());
        assertEquals("quietco", candidate.boardToken());
        assertEquals(0, candidate.jobCount());
        assertEquals(List.of(), candidate.sampleTitles());
        verifyNoInteractions(safeUrlFetcher);
        verifyNoInteractions(boardProbe);
    }

    @Test
    void anEmptyBoardSniffedOffTheCareersPageIsOfferedOnAPlatformThat404sUnknownTokens() {
        serve("https://razorpay.com/jobs/", FixtureSupport.fixture("careers-razorpay-excerpt.html"));
        AtsFetcher greenhouse = stubBoard(AtsPlatform.GREENHOUSE, new BoardPreview(null, 0, List.of()));
        when(greenhouse.distinguishesMissingBoard()).thenReturn(true);

        ResolvedBoardResponse candidate =
                resolver.resolve(user, URL, "https://razorpay.com/jobs/").candidates().get(0);

        assertEquals("SNIFF", candidate.source());
        assertEquals("razorpaysoftwareprivatelimited", candidate.boardToken());
        assertEquals(0, candidate.jobCount());
        verifyNoInteractions(boardProbe);
    }

    /**
     * Workable answers a token that was never theirs with an empty 200, so an
     * empty preview proves nothing even for a pasted link.
     */
    @Test
    void anEmptyBoardFromAPastedLinkIsNotOfferedOnAPlatformThatCannotTellMissingFromEmpty() {
        stubBoard(AtsPlatform.WORKABLE, new BoardPreview("Razorpay", 0, List.of()));

        ResolveResponse response = resolver.resolve(user, URL, "https://apply.workable.com/razorpay/");

        assertTrue(response.candidates().isEmpty());
    }

    /**
     * A catalog row is a name match, not the user's own link, so an empty board
     * there is still not offered, even on Greenhouse.
     */
    @Test
    void anEmptyCatalogBoardIsNotOfferedEvenOnAPlatformThat404sUnknownTokens() {
        Company quiet = company(AtsPlatform.GREENHOUSE, "quietco", "QuietCo");
        when(companyRepository.findById(quiet.getId())).thenReturn(Optional.of(quiet));
        when(jobRepository.countByCompany(quiet)).thenReturn(0L);
        AtsFetcher greenhouse = stubBoard(AtsPlatform.GREENHOUSE, new BoardPreview(null, 0, List.of()));
        when(greenhouse.distinguishesMissingBoard()).thenReturn(true);

        assertTrue(resolver.previewCatalog(user, quiet.getId()).isEmpty());
    }

    @Test
    void onlyUrlAndSniffSourcesOnAMissingBoardAwarePlatformAcceptAnEmptyBoard() {
        AtsFetcher strict = mock(AtsFetcher.class);
        AtsFetcher aware = mock(AtsFetcher.class);
        when(aware.distinguishesMissingBoard()).thenReturn(true);

        assertTrue(CompanyResolver.acceptsEmptyBoard(CompanyResolver.Source.URL, aware));
        assertTrue(CompanyResolver.acceptsEmptyBoard(CompanyResolver.Source.SNIFF, aware));
        assertFalse(CompanyResolver.acceptsEmptyBoard(CompanyResolver.Source.PROBE, aware));
        assertFalse(CompanyResolver.acceptsEmptyBoard(CompanyResolver.Source.CATALOG, aware));
        for (CompanyResolver.Source source : CompanyResolver.Source.values()) {
            assertFalse(CompanyResolver.acceptsEmptyBoard(source, strict), source.name());
        }
    }

    /**
     * A link to a platform Jobx recognises but has no fetcher for is answered at
     * once, naming the platform. Sniffing the ATS's own page or probing other
     * platforms can't make it watchable.
     */
    @Test
    void aPastedLinkToAnUnwatchablePlatformIsNamedWithoutAnyNetwork() {
        ResolveResponse response = resolver.resolve(user, URL, "https://ats.rippling.com/acme/jobs");

        assertTrue(response.candidates().isEmpty());
        assertEquals(AtsPlatform.RIPPLING, response.platformHint());
        verifyNoInteractions(safeUrlFetcher);
        verifyNoInteractions(boardProbe);
    }

    // ------------------------------------------------------------------ sniff

    /**
     * The case that justifies fetching careers pages at all: no derivation from
     * "Razorpay" reaches razorpaysoftwareprivatelimited.
     */
    @Test
    void sniffsAnUnguessableTokenOutOfARealCareersPage() {
        serve("https://razorpay.com/jobs/", FixtureSupport.fixture("careers-razorpay-excerpt.html"));
        stubBoard(AtsPlatform.GREENHOUSE, new BoardPreview(null, 25, List.of("Engineering Manager")));

        ResolvedBoardResponse candidate =
                resolver.resolve(user, URL, "https://razorpay.com/jobs/").candidates().get(0);

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
        serve("https://atlan.com/careers/", FixtureSupport.fixture("careers-atlan-excerpt.html"));
        when(boardProbe.probe(eq(List.of(AtsPlatform.ASHBY)), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.ASHBY, "atlan"),
                        new BoardPreview(null, 5, List.of("Senior Security Engineer")))));

        ResolveResponse response = resolver.resolve(user, URL, "https://atlan.com/careers/");

        ResolvedBoardResponse candidate = response.candidates().get(0);
        assertEquals("PROBE", candidate.source());
        assertEquals(AtsPlatform.ASHBY, candidate.atsPlatform());
        assertEquals("atlan", candidate.boardToken());
        assertEquals(AtsPlatform.ASHBY, response.platformHint());
        verify(boardProbe).probe(List.of(AtsPlatform.ASHBY), List.of("atlan", "Atlan"));
    }

    /**
     * A careers page that links a board on a platform with no fetcher yields
     * that platform as the hint, even when it also mentions another ATS's host
     * in passing: the page named its board, which beats a bare host mention.
     */
    @Test
    void aSniffedBoardOnAnUnwatchablePlatformBecomesTheHint() {
        serve("https://acme.com/careers/", """
                <script src="https://boards.greenhouse.io/static/widget.js"></script>
                <a href="https://careers-acme.icims.com/jobs/search">Open roles</a>
                """);

        ResolveResponse response = resolver.resolve(user, URL, "https://acme.com/careers/");

        assertTrue(response.candidates().isEmpty());
        // iCIMS is the one recognised platform with no fetcher (dropped 2026-10-25),
        // and its token can't be guessed, so nothing is probed.
        assertEquals(AtsPlatform.ICIMS, response.platformHint());
        verifyNoInteractions(boardProbe);
    }

    /**
     * A hint naming a platform that can't be probed ends in the honest empty
     * answer, hint attached — even once that platform has a fetcher. Workday's
     * tenant/wdN/site token can't be guessed from a name, so a probe would only
     * try slugs it can't possibly accept.
     */
    @Test
    void aHintForANonProbeablePlatformIsAnHonestEmptyAnswer() {
        stubBoard(AtsPlatform.WORKDAY, new BoardPreview(null, 1523, List.of("Engineer")));
        serve("https://acme.com/careers/", "<link rel=\"preconnect\" href=\"https://myworkdayjobs.com\">");

        ResolveResponse response = resolver.resolve(user, URL, "https://acme.com/careers/");

        assertTrue(response.candidates().isEmpty());
        assertEquals(AtsPlatform.WORKDAY, response.platformHint());
        verifyNoInteractions(boardProbe);
    }

    /** With its fetcher shipped, a pasted Workday link resolves straight to its board. */
    @Test
    void aPastedWorkdayLinkResolvesToTheCompositeToken() {
        stubBoard(AtsPlatform.WORKDAY, new BoardPreview(null, 1523, List.of("Software Engineering MTS")));

        ResolveResponse response = resolver.resolve(user, URL,
                "https://salesforce.wd12.myworkdayjobs.com/en-US/External_Career_Site");

        ResolvedBoardResponse candidate = response.candidates().get(0);
        assertEquals(AtsPlatform.WORKDAY, candidate.atsPlatform());
        assertEquals("salesforce/wd12/External_Career_Site", candidate.boardToken());
        assertEquals(1523, candidate.jobCount());
        verifyNoInteractions(boardProbe);
    }

    // ------------------------------------------------------------------ probe

    @Test
    void probesEveryPlatformWhenThereIsNoHint() {
        when(boardProbe.probe(any(), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.LEVER, "fampay"),
                        new BoardPreview(null, 14, List.of("Android Engineer")))));

        ResolvedBoardResponse candidate = resolver.resolve(user, NAME, "FamPay").candidates().get(0);

        assertEquals("PROBE", candidate.source());
        assertEquals("fampay", candidate.boardToken());
        // Rippling and BambooHR joined once their fetchers shipped. Jobvite,
        // Workday and the other recognised-only platforms are never guessed.
        verify(boardProbe).probe(
                eq(List.of(AtsPlatform.GREENHOUSE, AtsPlatform.LEVER, AtsPlatform.ASHBY,
                        AtsPlatform.WORKABLE, AtsPlatform.SMARTRECRUITERS,
                        AtsPlatform.RIPPLING, AtsPlatform.BAMBOOHR)),
                eq(List.of("FamPay", "fampay", "Fampay")));
    }

    /** A plain name never triggers an outbound page fetch. */
    @Test
    void aCompanyNameIsNotTreatedAsAUrl() {
        resolver.resolve(user, NAME, "Razorpay");
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
                resolver.resolve(user, NAME, "PHONEPELIMITED").candidates().get(0).companyName());

        when(boardProbe.probe(any(), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.ASHBY, "atlan"),
                        new BoardPreview(null, 5, List.of("Engineer")))));
        when(companyRepository.findByPlatformAndTokenIgnoreCase(AtsPlatform.ASHBY, "atlan"))
                .thenReturn(Optional.of(company(AtsPlatform.ASHBY, "atlan", "Atlan")));
        assertEquals("Atlan", resolver.resolve(user, NAME, "atlan").candidates().get(0).companyName());

        when(companyRepository.findByPlatformAndTokenIgnoreCase(AtsPlatform.ASHBY, "atlan"))
                .thenReturn(Optional.empty());
        assertEquals("atlan", resolver.resolve(user, NAME, "atlan").candidates().get(0).companyName());
    }

    /** Nothing found is a normal answer — plenty of portals have no public API. */
    @Test
    void anEmptyResultIsAnHonestAnswer() {
        ResolveResponse response = resolver.resolve(user, NAME, "Some Company On Workday");
        assertTrue(response.isEmpty());
        assertNull(response.platformHint());
    }

    @Test
    void blankQueryResolvesToNothingWithoutAnyWork() {
        assertTrue(resolver.resolve(user, NAME, "   ").isEmpty());
        assertTrue(resolver.resolve(user, NAME, null).isEmpty());
        assertTrue(resolver.resolve(user, URL, "   ").isEmpty());
        assertTrue(resolver.resolve(user, URL, null).isEmpty());
        verifyNoInteractions(boardProbe);
        verifyNoInteractions(safeUrlFetcher);
    }

    // --------------------------------------------------------- one-hop follow

    /**
     * A landing page that never names its ATS: the board is one click away,
     * behind "See open roles". Following that link finds it.
     */
    @Test
    void followsACareersLinkOneHopToFindTheBoard() {
        serve("https://acme.com/careers", FixtureSupport.fixture("careers-landing-onehop.html"));
        serve("https://acme.com/careers/open-roles", FixtureSupport.fixture("careers-onehop-target.html"));
        stubBoard(AtsPlatform.GREENHOUSE, new BoardPreview(null, 12, List.of("Backend Engineer")));

        ResolveResponse response = resolver.resolve(user, URL, "https://acme.com/careers");

        ResolvedBoardResponse candidate = response.candidates().get(0);
        assertEquals("SNIFF", candidate.source());
        assertEquals(AtsPlatform.GREENHOUSE, candidate.atsPlatform());
        assertEquals("acme", candidate.boardToken());
        verifyNoInteractions(boardProbe);
    }

    /** The cross-domain "Careers" link, the mailto and the PDF are never fetched. */
    @Test
    void neverFollowsOffSiteOrNonPageLinks() {
        serve("https://acme.com/careers", FixtureSupport.fixture("careers-landing-onehop.html"));

        resolver.resolve(user, URL, "https://acme.com/careers");

        verify(safeUrlFetcher).fetchPage("https://acme.com/careers");
        verify(safeUrlFetcher).fetchPage("https://acme.com/careers/open-roles");
        verify(safeUrlFetcher, never()).fetchPage(contains("partner-careers.example.org"));
        verify(safeUrlFetcher, never()).fetchPage(contains(".pdf"));
        verify(safeUrlFetcher, never()).fetchPage(startsWith("mailto:"));
        verifyNoMoreInteractions(safeUrlFetcher);
    }

    /** One hop and at most three links, however many careers links the page has. */
    @Test
    void followsAtMostThreeLinks() {
        serve("https://acme.com/", """
                <a href="/jobs/engineering">Engineering jobs</a>
                <a href="/jobs/design">Design jobs</a>
                <a href="/jobs/sales">Sales jobs</a>
                <a href="/jobs/ops">Ops jobs</a>
                <a href="/jobs/support">Support jobs</a>
                """);

        resolver.resolve(user, URL, "https://acme.com/");

        verify(safeUrlFetcher, times(4)).fetchPage(anyString()); // landing + 3
        verify(safeUrlFetcher, never()).fetchPage("https://acme.com/jobs/ops");
    }

    /**
     * A link the SSRF guard refuses was the page's choice, not the user's, so it
     * is a dead end rather than a 400, and resolution carries on to the probe.
     */
    @Test
    void aRefusedFollowedLinkIsADeadEndNotA400() {
        serve("https://acme.com/careers", "<a href=\"https://jobs.acme.com/\">Jobs</a>");
        when(safeUrlFetcher.fetchPage("https://jobs.acme.com"))
                .thenThrow(new SafeUrlFetcher.UnsafeUrlException("that address is not on the public internet"));

        ResolveResponse response = assertDoesNotThrow(
                () -> resolver.resolve(user, URL, "https://acme.com/careers"));

        assertTrue(response.isEmpty());
        verify(safeUrlFetcher).fetchPage("https://jobs.acme.com");
        verify(boardProbe).probe(any(), any());
    }

    /** A hint found only one hop away still narrows the probe. */
    @Test
    void aHintOnAFollowedPageNarrowsTheProbe() {
        serve("https://atlan.com/", "<a href=\"/careers\">Careers</a>");
        serve("https://atlan.com/careers", FixtureSupport.fixture("careers-atlan-excerpt.html"));

        ResolveResponse response = resolver.resolve(user, URL, "https://atlan.com/");

        assertEquals(AtsPlatform.ASHBY, response.platformHint());
        verify(boardProbe).probe(eq(List.of(AtsPlatform.ASHBY)), any());
    }

    /** A page that already names its board costs no extra fetches. */
    @Test
    void noFollowWhenTheLandingPageNamesItsBoard() {
        serve("https://razorpay.com/jobs/", FixtureSupport.fixture("careers-razorpay-excerpt.html"));
        stubBoard(AtsPlatform.GREENHOUSE, new BoardPreview(null, 25, List.of("Engineering Manager")));

        resolver.resolve(user, URL, "https://razorpay.com/jobs/");

        verify(safeUrlFetcher, times(1)).fetchPage(anyString());
    }

    // ------------------------------------------------------------------ modes

    /**
     * Someone who chose "Company name" and typed a domain meant the company.
     * NAME mode never fetches a page; slug derivation still uses the label.
     */
    @Test
    void nameModeNeverFetchesAPageEvenForADomain() {
        resolver.resolve(user, NAME, "razorpay.com");

        verifyNoInteractions(safeUrlFetcher);
        verify(boardProbe).probe(any(), eq(List.of("razorpay", "Razorpay")));
    }

    /** NAME mode doesn't read an ATS link as a link either: no URL candidate. */
    @Test
    void nameModeDoesNotParseAnAtsLink() {
        AtsFetcher lever = stubBoard(AtsPlatform.LEVER,
                new BoardPreview(null, 14, List.of("Android Engineer")));

        ResolveResponse response = resolver.resolve(user, NAME, "https://jobs.lever.co/fampay");

        assertTrue(response.candidates().stream().noneMatch(c -> c.source().equals("URL")));
        verify(lever, never()).previewBoard(any());
        verifyNoInteractions(safeUrlFetcher);
    }

    /** URL mode with a bare name is the user's mistake to fix, so it's a 400. */
    @Test
    void urlModeRefusesSomethingThatIsNotALink() {
        SafeUrlFetcher.UnsafeUrlException refused = assertThrows(
                SafeUrlFetcher.UnsafeUrlException.class,
                () -> resolver.resolve(user, URL, "Razorpay"));

        assertTrue(refused.getMessage().contains("careers page link"));
        verifyNoInteractions(safeUrlFetcher);
        verifyNoInteractions(boardProbe);
        verify(companyRepository, never()).searchByNameOrToken(anyString(), any());
    }

    /** A pasted link is more specific than any name match, so the catalog is skipped. */
    @Test
    void urlModeSkipsTheCatalog() {
        Company acme = company(AtsPlatform.GREENHOUSE, "acme", "acme.com");
        when(companyRepository.searchByNameOrToken(anyString(), any())).thenReturn(List.of(acme));

        ResolveResponse response = resolver.resolve(user, URL, "acme.com/careers");

        assertTrue(response.candidates().stream().noneMatch(c -> c.source().equals("CATALOG")));
        verify(companyRepository, never()).searchByNameOrToken(anyString(), any());
        verify(safeUrlFetcher).fetchPage("acme.com/careers");
    }

    /** Resolution is read-only: watching a board is a separate, explicit act. */
    @Test
    void neverWritesAnything() {
        when(boardProbe.probe(any(), any())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.LEVER, "fampay"),
                        new BoardPreview(null, 14, List.of("Android Engineer")))));

        resolver.resolve(user, NAME, "FamPay");

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
        when(safeUrlFetcher.fetchPage("file:///etc/passwd"))
                .thenThrow(new SafeUrlFetcher.UnsafeUrlException("only http and https links can be checked"));

        assertThrows(SafeUrlFetcher.UnsafeUrlException.class,
                () -> resolver.resolve(user, URL, "file:///etc/passwd"));
    }

    // -------------------------------------------------------- catalog preview

    /**
     * A typeahead pick names the exact board, so its evidence comes from the
     * stored jobs with no ATS call — BUG_REPORT #5, which used to show
     * "0 open roles" and a blank board link here.
     */
    @Test
    void catalogPreviewUsesStoredJobs() {
        Company razorpay = company(AtsPlatform.GREENHOUSE, "razorpaysoftwareprivatelimited", "Razorpay");
        when(companyRepository.findById(razorpay.getId())).thenReturn(Optional.of(razorpay));
        when(jobRepository.countByCompany(razorpay)).thenReturn(25L);
        when(jobRepository.findTitlesByCompany(eq(razorpay), any()))
                .thenReturn(List.of("Senior Backend Engineer", "Product Designer"));
        when(watchedCompanyRepository.existsByUserAndCompany(user, razorpay)).thenReturn(true);

        ResolvedBoardResponse board = resolver.previewCatalog(user, razorpay.getId()).orElseThrow();

        assertEquals("CATALOG", board.source());
        assertEquals("Razorpay", board.companyName());
        assertEquals(25, board.jobCount());
        assertEquals(List.of("Senior Backend Engineer", "Product Designer"), board.sampleTitles());
        assertEquals("https://job-boards.greenhouse.io/razorpaysoftwareprivatelimited", board.boardUrl());
        assertTrue(board.alreadyWatched());
        verify(fetcherRegistry, never()).getFetcher(any());
    }

    /** A seeded row with no stored jobs asks the board itself, as the name path does. */
    @Test
    void catalogPreviewFallsBackToALiveBoardWhenNothingIsStored() {
        Company sprinto = company(AtsPlatform.ASHBY, "Sprinto", "Sprinto");
        when(companyRepository.findById(sprinto.getId())).thenReturn(Optional.of(sprinto));
        when(jobRepository.countByCompany(sprinto)).thenReturn(0L);
        stubBoard(AtsPlatform.ASHBY, new BoardPreview(null, 30, List.of("Account Executive")));

        ResolvedBoardResponse board = resolver.previewCatalog(user, sprinto.getId()).orElseThrow();

        assertEquals("CATALOG", board.source());
        assertEquals("Sprinto", board.companyName());
        assertEquals(30, board.jobCount());
        assertEquals(List.of("Account Executive"), board.sampleTitles());
        assertEquals("https://jobs.ashbyhq.com/Sprinto", board.boardUrl());
    }

    /** Nothing stored and nothing live — the company may have moved ATS. */
    @Test
    void catalogPreviewIsEmptyWhenTheBoardHasNothingLive() {
        Company phonepe = company(AtsPlatform.GREENHOUSE, "phonepe", "PhonePe");
        when(companyRepository.findById(phonepe.getId())).thenReturn(Optional.of(phonepe));
        when(jobRepository.countByCompany(phonepe)).thenReturn(0L);
        stubBoard(AtsPlatform.GREENHOUSE, new BoardPreview(null, 0, List.of()));

        assertTrue(resolver.previewCatalog(user, phonepe.getId()).isEmpty());
    }

    @Test
    void catalogPreviewIsEmptyWhenTheBoardCannotBeRead() {
        Company dead = company(AtsPlatform.LEVER, "gone", "Gone");
        when(companyRepository.findById(dead.getId())).thenReturn(Optional.of(dead));
        when(jobRepository.countByCompany(dead)).thenReturn(0L);
        AtsFetcher fetcher = stubBoard(AtsPlatform.LEVER, null);
        when(fetcher.previewBoard(any())).thenThrow(new RuntimeException("404"));

        assertTrue(resolver.previewCatalog(user, dead.getId()).isEmpty());
    }

    @Test
    void catalogPreviewIsEmptyForAnUnknownBoard() {
        UUID unknown = UUID.randomUUID();
        when(companyRepository.findById(unknown)).thenReturn(Optional.empty());

        assertTrue(resolver.previewCatalog(user, unknown).isEmpty());
        verifyNoInteractions(fetcherRegistry);
    }
}
