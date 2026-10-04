package com.jobx.fetcher.jazzhr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetchFilter;
import com.jobx.fetcher.FixtureSupport;
import com.jobx.fetcher.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping and dead-board tests against real JazzHR pages captured 2026-10-04:
 * Brennan Center (21 live roles), Inflow (a real board with nothing open) and
 * the jazzhr subdomain itself (a cancelled account's "Inactive Career Page").
 */
class JazzHrFetcherTest {

    private static final String SUB = "brennancenter";

    private JazzHrFetcher fetcher;
    private Company company;

    @BeforeEach
    void setUp() {
        fetcher = new JazzHrFetcher(null, new ObjectMapper());
        company = FixtureSupport.company("Brennan Center", AtsPlatform.JAZZHR, SUB);
    }

    private List<Job> list(String fixture) {
        return fetcher.parseList(FixtureSupport.fixture(fixture), company, SUB, FetchFilter.none(), false);
    }

    // ------------------------------------------------------------------ list

    @Test
    void mapsEveryCardOnTheBoard() {
        List<Job> jobs = list("jazzhr-brennancenter-list.html");

        assertEquals(21, jobs.size());
        assertEquals(21, jobs.stream().map(Job::getExternalId).distinct().count());
        for (Job job : jobs) {
            assertEquals(AtsPlatform.JAZZHR, job.getAtsPlatform());
            assertTrue(job.getExternalId().matches("[A-Za-z0-9]{6,20}"));
            assertFalse(job.getTitle().isBlank());
            assertEquals("https://brennancenter.applytojob.com/apply/" + job.getExternalId(), job.getApplyUrl());
        }
    }

    @Test
    void mapsAKnownCard() {
        Job job = list("jazzhr-brennancenter-list.html").stream()
                .filter(j -> j.getExternalId().equals("KJ32rbHkxr"))
                .findFirst().orElseThrow();

        assertEquals("Administrative Assistant (Front Desk & Office Operations)", job.getTitle());
        assertEquals("New York, NY", job.getLocation());
        // The list carries neither — they come from the detail page
        assertNull(job.getDescription());
        assertNull(job.getPlatformPostedAt());
    }

    @Test
    void aRealEmptyBoardIsAnEmptyListNotAFailure() {
        assertTrue(list("jazzhr-getinflow-empty.html").isEmpty());
    }

    @Test
    void anInactiveCareerPageIsADeadBoard() {
        AtsFetchException e = assertThrows(AtsFetchException.class, () -> list("jazzhr-inactive.html"));
        assertTrue(e.getMessage().contains("inactive"));
    }

    /** A 200 page with no cards and no "no openings" marker is a layout change, not a quiet board. */
    @Test
    void aPageWithNoCardsAndNoMarkerFailsLoudly() {
        assertThrows(AtsFetchException.class, () -> fetcher.parseList(
                "<html><head><title>Brennan Center - Career Page</title></head><body><ul></ul></body></html>",
                company, SUB, FetchFilter.none(), false));
    }

    @Test
    void aLinkToAnotherBoardIsNotThisBoardsPosting() {
        String html = """
                <ul><li class="list-group-item"><h3 class="list-group-item-heading">
                  <a href="https://someoneelse.applytojob.com/apply/AbCdEf1234/Engineer">Engineer</a>
                </h3></li></ul>""";

        // The only card points off-board, so the page has no cards of its own.
        assertThrows(AtsFetchException.class,
                () -> fetcher.parseList(html, company, SUB, FetchFilter.none(), false));
    }

    // ---------------------------------------------------------------- detail

    @Test
    void detailFillsDescriptionDateAndExperienceFromJsonLd() {
        Job job = new Job();
        job.setExternalId("KJ32rbHkxr");

        assertTrue(fetcher.applyDetail(FixtureSupport.fixture("jazzhr-brennancenter-detail.html"), job));

        assertNotNull(job.getDescription());
        assertTrue(job.getDescription().startsWith("The Brennan Center for Justice"));
        assertFalse(job.getDescription().contains("<p>"));
        // datePosted is date-only → midnight UTC
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), job.getPlatformPostedAt());
        // the card had no location here, so JSON-LD supplies it
        assertEquals("New York, NY", job.getLocation());
        assertTrue(job.getRawJson().contains("\"JobPosting\""));
    }

    /**
     * 2 of Brennan Center's 21 postings carry only an Organization block (live).
     * They fall back to the rendered #job-description rather than being skipped —
     * a skipped posting is never stored, so it would be lost and re-fetched every cycle.
     */
    @Test
    void aPostingWithoutJsonLdFallsBackToTheRenderedDescription() {
        Job job = new Job();
        job.setExternalId("Vq9OI5O13x");

        assertTrue(fetcher.applyDetail(FixtureSupport.fixture("jazzhr-brennancenter-detail-no-jsonld.html"), job));

        assertTrue(job.getDescription().startsWith("The Brennan Center for Justice"));
        // no date on the page; the TTL runs from first_seen_at instead
        assertNull(job.getPlatformPostedAt());
        assertNull(job.getRawJson());
    }

    @Test
    void aPageWithNeitherJsonLdNorADescriptionIsReported() {
        // The board page: an Organization block only, and no #job-description.
        assertFalse(fetcher.applyDetail(FixtureSupport.fixture("jazzhr-brennancenter-list.html"), new Job()));
    }

    // --------------------------------------------------------------- preview

    @Test
    void previewCountsCardsAndNamesTheCompany() {
        BoardPreview preview = fetcher.parsePreview(FixtureSupport.fixture("jazzhr-brennancenter-list.html"), SUB);

        assertEquals("Brennan Center for Justice", preview.displayName());
        assertEquals(21, preview.jobCount());
        assertEquals(BoardPreview.SAMPLE_SIZE, preview.sampleTitles().size());
    }

    @Test
    void anEmptyBoardPreviewsAsZeroSoValidateBoardRejectsIt() {
        assertEquals(0, fetcher.parsePreview(FixtureSupport.fixture("jazzhr-getinflow-empty.html"), "getinflow")
                .jobCount());
    }

    // ------------------------------------------------------------------ HTTP

    /** A fetcher whose every request answers with this status and Location, through a real WebClient. */
    private static JazzHrFetcher answering(HttpStatus status, String location, List<String> requested) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requested.add(request.url().toString());
            ClientResponse.Builder response = ClientResponse.create(status);
            if (location != null) {
                response.header("Location", location);
            }
            return Mono.just(response.build());
        });
        return new JazzHrFetcher(builder, new ObjectMapper());
    }

    /** JazzHR sends an unknown subdomain to its marketing site; that must never read as an empty board. */
    @Test
    void aRedirectIsADeadBoard() {
        List<String> requested = new ArrayList<>();
        JazzHrFetcher redirecting = answering(HttpStatus.FOUND,
                "https://info.jazzhr.com/job-seekers.html", requested);
        Company bogus = FixtureSupport.company("Bogus", AtsPlatform.JAZZHR, "zzqq-not-real");

        AtsFetchException fetchError = assertThrows(AtsFetchException.class,
                () -> redirecting.fetch(bogus, FetchFilter.none()));
        assertTrue(fetchError.getMessage().contains("does not exist"));
        assertThrows(AtsFetchException.class, () -> redirecting.validateBoard(bogus));
        assertEquals("https://zzqq-not-real.applytojob.com/apply", requested.get(0));
    }

    @Test
    void aServerErrorIsAFailure() {
        JazzHrFetcher failing = answering(HttpStatus.SERVICE_UNAVAILABLE, null, new ArrayList<>());

        assertThrows(AtsFetchException.class, () -> failing.fetch(company, FetchFilter.none()));
    }

    /** The subdomain becomes a hostname, so a hostile token must fail before any request is made. */
    @Test
    void aHostileTokenIsRejectedBeforeAnyRequest() {
        List<String> requested = new ArrayList<>();
        JazzHrFetcher fetcherUnderTest = answering(HttpStatus.OK, null, requested);

        for (String token : List.of("evil.com#", "a.b", "x@y", "a/../b", "")) {
            Company hostile = FixtureSupport.company("Hostile", AtsPlatform.JAZZHR, token);
            assertThrows(AtsFetchException.class, () -> fetcherUnderTest.fetch(hostile, FetchFilter.none()));
            assertThrows(AtsFetchException.class, () -> fetcherUnderTest.previewBoard(hostile));
        }
        assertTrue(requested.isEmpty());
    }

    @Test
    void aClosedPostingDetailIsAFailureForThatPostingOnly() {
        JazzHrFetcher fetcherUnderTest = new JazzHrFetcher(null, new ObjectMapper()) {
            @Override
            HtmlPage fetchPage(String url) {
                return new HtmlPage(404, null, "");
            }
        };

        assertThrows(AtsFetchException.class, () -> fetcherUnderTest.fetchDetail(SUB, "KJ32rbHkxr"));
    }

    // ---------------------------------------------------------------- filter

    /** Detail calls requested, in order; each answers with the captured detail page. */
    private final List<String> detailCalls = new ArrayList<>();

    private JazzHrFetcher countingDetails(boolean fail) {
        return new JazzHrFetcher(null, new ObjectMapper()) {
            @Override
            String fetchDetail(String sub, String code) {
                detailCalls.add(code);
                if (fail) {
                    throw new AtsFetchException("503 Service Unavailable");
                }
                return FixtureSupport.fixture("jazzhr-brennancenter-detail.html");
            }
        };
    }

    @Test
    void aKnownPostingCostsNoDetailCall() {
        List<Job> all = list("jazzhr-brennancenter-list.html");
        Set<String> known = Set.copyOf(all.stream().skip(1).map(Job::getExternalId).toList());
        String fresh = all.get(0).getExternalId();

        List<Job> jobs = countingDetails(false).parseList(FixtureSupport.fixture("jazzhr-brennancenter-list.html"),
                company, SUB, new FetchFilter(known, Instant.MIN), true);

        assertEquals(List.of(fresh), detailCalls);
        assertEquals(1, jobs.size());
    }

    @Test
    void aPostingPastTheTtlIsDroppedAfterItsDetailCall() {
        // The fixture's datePosted is 2026-10-01; a cutoff of 10-05 makes it too old.
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-10-05T00:00:00Z"));

        assertTrue(countingDetails(false).parseList(FixtureSupport.fixture("jazzhr-brennancenter-list.html"),
                company, SUB, filter, true).isEmpty());
    }

    /**
     * The list has no dates, so a stale posting is only discovered on its
     * detail page. It is never stored or tombstoned, so without the memo the
     * next cycle would pay that detail call again — every cycle, forever.
     */
    @Test
    void aStalePostingCostsADetailCallOnlyOnce() {
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-10-05T00:00:00Z"));
        JazzHrFetcher fetcherUnderTest = countingDetails(false);
        String board = FixtureSupport.fixture("jazzhr-brennancenter-list.html");

        fetcherUnderTest.parseList(board, company, SUB, filter, true);
        assertEquals(21, detailCalls.size());

        fetcherUnderTest.parseList(board, company, SUB, filter, true);
        assertEquals(21, detailCalls.size(), "the second cycle must not re-pay for stale postings");
    }

    @Test
    void aPostingDatedOnTheCutoffDayIsKept() {
        // datePosted has no time; the whole of 2026-10-01 counts as inside the window.
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-10-01T15:00:00Z"));

        assertEquals(21, countingDetails(false).parseList(FixtureSupport.fixture("jazzhr-brennancenter-list.html"),
                company, SUB, filter, true).size());
    }

    /**
     * A detail failure must SKIP the posting, not persist it without a
     * description — the known-id guard would then never revisit it.
     */
    @Test
    void aFailedDetailSkipsThePostingWithoutFailingTheBoard() {
        List<Job> jobs = countingDetails(true).parseList(FixtureSupport.fixture("jazzhr-brennancenter-list.html"),
                company, SUB, FetchFilter.none(), true);

        assertTrue(jobs.isEmpty());
        assertEquals(21, detailCalls.size());
    }
}
