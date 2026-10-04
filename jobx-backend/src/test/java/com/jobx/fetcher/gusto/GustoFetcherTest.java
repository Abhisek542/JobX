package com.jobx.fetcher.gusto;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping, dead-board and bot-protection tests against real Gusto pages
 * captured 2026-10-04: Sage Veterinary Imaging's board (14 postings), one of
 * its postings, and the Cloudflare challenge page curl's user agent is served.
 */
class GustoFetcherTest {

    private static final String SLUG = "sage-veterinary-imaging-07e81227-32b5-482d-9fc0-c99bc9ad2f96";
    private static final String VET_UUID = "91d7fb91-e85c-4fed-adc7-dd4330c75cfe";

    private Company company;
    /** URLs requested through the fetchPage seam, in order. */
    private final List<String> requested = new ArrayList<>();
    private int pauses;

    @BeforeEach
    void setUp() {
        company = FixtureSupport.company("Sage Veterinary Imaging", AtsPlatform.GUSTO, SLUG);
    }

    /**
     * A fetcher that answers the board URL with the board fixture and each
     * posting URL with {@code postingAnswer}, recording requests and pauses.
     */
    private GustoFetcher fetcher(HtmlPage boardAnswer, HtmlPage postingAnswer) {
        return new GustoFetcher(null, new ObjectMapper()) {
            @Override
            HtmlPage fetchPage(String url) {
                requested.add(url);
                return url.contains("/boards/") ? boardAnswer : postingAnswer;
            }

            @Override
            void pause() {
                pauses++;
            }
        };
    }

    private static HtmlPage ok(String fixture) {
        return new HtmlPage(200, null, FixtureSupport.fixture(fixture));
    }

    private static HtmlPage challenge() {
        return new HtmlPage(403, null, FixtureSupport.fixture("gusto-challenge.html"));
    }

    private GustoFetcher plain() {
        return new GustoFetcher(null, new ObjectMapper());
    }

    private List<Job> list() {
        return plain().parseList(FixtureSupport.fixture("gusto-sage-board.html"), company, SLUG,
                FetchFilter.none(), false);
    }

    // ------------------------------------------------------------------ list

    @Test
    void mapsEveryPostingOnTheBoard() {
        List<Job> jobs = list();

        assertEquals(14, jobs.size());
        assertEquals(14, jobs.stream().map(Job::getExternalId).distinct().count());
        for (Job job : jobs) {
            assertEquals(AtsPlatform.GUSTO, job.getAtsPlatform());
            assertTrue(job.getExternalId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
            assertFalse(job.getTitle().isBlank());
            assertTrue(job.getApplyUrl().startsWith("https://jobs.gusto.com/postings/sage-veterinary-imaging-"));
            assertTrue(job.getApplyUrl().endsWith(job.getExternalId()));
        }
    }

    @Test
    void mapsAKnownCard() {
        Job job = list().stream().filter(j -> j.getExternalId().equals(VET_UUID)).findFirst().orElseThrow();

        assertEquals("Associate Veterinarian", job.getTitle());
        assertEquals("Spring, TX", job.getLocation());
        assertNull(job.getDescription());
        assertNull(job.getPlatformPostedAt());
    }

    @Test
    void onlyRelativePostingLinksEndingInAUuidAreFollowed() {
        String html = """
                <html><head><title>Careers at Acme</title></head><body><ul class="divide-y">
                <li><a href="/postings/../admin">x</a></li>
                <li><a href="/postings/acme-engineer">no uuid</a></li>
                <li><a href="https://evil.example/postings/acme-91d7fb91-e85c-4fed-adc7-dd4330c75cfe">abs</a></li>
                <li><a href="/postings/acme-engineer-91d7fb91-e85c-4fed-adc7-dd4330c75cfe?x=1">query</a></li>
                <li><a href="/postings/acme-engineer-11111111-2222-3333-4444-555555555555"><h3>Engineer</h3></a></li>
                </ul></body></html>""";

        List<Job> jobs = plain().parseList(html, company, SLUG, FetchFilter.none(), false);

        assertEquals(List.of("11111111-2222-3333-4444-555555555555"),
                jobs.stream().map(Job::getExternalId).toList());
        assertEquals("Engineer", jobs.get(0).getTitle());
    }

    @Test
    void aBoardPageWithAnEmptyJobListIsAQuietBoard() {
        String html = "<html><head><title>Careers at Acme</title></head>"
                + "<body><ul class=\"divide-y divide-gray-200\"></ul></body></html>";

        assertTrue(plain().parseList(html, company, SLUG, FetchFilter.none(), false).isEmpty());
        assertEquals(0, plain().parsePreview(html, SLUG).jobCount());
    }

    @Test
    void aPageWithNothingRecognisableFailsLoudly() {
        // Not the board layout at all — e.g. a redesign or an error page served as 200.
        assertThrows(AtsFetchException.class, () -> plain().parseList(
                "<html><head><title>Gusto</title></head><body><p>Hello</p></body></html>",
                company, SLUG, FetchFilter.none(), false));
    }

    // ---------------------------------------------------------------- detail

    @Test
    void detailFillsDescriptionDateAndExperienceFromJsonLd() {
        Job job = new Job();
        job.setExternalId("1f5bd318-a71d-45e4-b377-f0df3ba1b89f");

        assertTrue(plain().applyDetail(FixtureSupport.fixture("gusto-sage-posting.html"), job));

        assertTrue(job.getDescription().startsWith("Drive Care Forward."));
        assertFalse(job.getDescription().contains("<p>"));
        assertEquals(Instant.parse("2026-05-14T20:01:59Z"), job.getPlatformPostedAt());
        assertEquals("Round Rock, TX, US", job.getLocation());
        assertTrue(job.getRawJson().contains("\"JobPosting\""));
    }

    @Test
    void aMultiLocationCardKeepsTheLocationsApart() {
        Job job = list().stream().filter(j -> j.getExternalId().equals("e5bfd443-8811-45e6-a052-3cc4d8b30946"))
                .findFirst().orElseThrow();

        assertEquals("Round Rock, TX / Spring, TX", job.getLocation());
    }

    /**
     * Contractor postings carry no JSON-LD (3 of Sage's 14, live). They fall
     * back to the rendered body rather than being skipped — a skipped posting
     * is never stored, so it would be lost and re-fetched every cycle.
     */
    @Test
    void aPostingWithoutJsonLdFallsBackToThePageBody() {
        Job job = new Job();
        job.setExternalId("63bc2ad0-e455-4890-90b1-e92ea9269d83");

        assertTrue(plain().applyDetail(FixtureSupport.fixture("gusto-sage-posting-no-jsonld.html"), job));

        assertTrue(job.getDescription().startsWith("Remotely veterinary radiologists"));
        assertTrue(job.getDescription().contains("About Sage Veterinary Imaging"));
        // no date on the page; the TTL runs from first_seen_at instead
        assertNull(job.getPlatformPostedAt());
        assertNull(job.getRawJson());
    }

    @Test
    void aPageWithNeitherJsonLdNorABodyIsReported() {
        assertFalse(plain().applyDetail("<html><body><p>Nothing here</p></body></html>", new Job()));
    }

    // --------------------------------------------------------------- preview

    @Test
    void previewCountsPostingsAndNamesTheCompany() {
        BoardPreview preview = plain().parsePreview(FixtureSupport.fixture("gusto-sage-board.html"), SLUG);

        assertEquals("Sage Veterinary Imaging", preview.displayName());
        assertEquals(14, preview.jobCount());
        assertEquals(BoardPreview.SAMPLE_SIZE, preview.sampleTitles().size());
    }

    // ------------------------------------------------------- HTTP and pacing

    @Test
    void anUnknownBoardIsADeadBoard() {
        GustoFetcher fetcher = fetcher(new HtmlPage(404, null, "<title>404 Error - Page Not Found</title>"), null);

        AtsFetchException e = assertThrows(AtsFetchException.class, () -> fetcher.fetch(company, FetchFilter.none()));
        assertTrue(e.getMessage().contains("does not exist"));
        assertThrows(AtsFetchException.class, () -> fetcher.validateBoard(company));
    }

    /** Never bypass bot protection: a challenge is a FAILED fetch, with no second attempt. */
    @Test
    void aChallengedBoardFailsWithoutRetrying() {
        GustoFetcher fetcher = fetcher(challenge(), null);

        AtsFetchException e = assertThrows(AtsFetchException.class, () -> fetcher.fetch(company, FetchFilter.none()));
        assertTrue(e.getMessage().contains("Cloudflare challenge"));
        assertEquals(1, requested.size());
    }

    /** A challenge mid-way through the detail calls stops the fetch instead of hammering the site. */
    @Test
    void aChallengeDuringDetailCallsStopsTheFetch() {
        GustoFetcher fetcher = fetcher(ok("gusto-sage-board.html"), challenge());

        assertThrows(AtsFetchException.class, () -> fetcher.fetch(company, FetchFilter.none()));
        // the board, then exactly one posting
        assertEquals(2, requested.size());
    }

    @Test
    void anOrdinaryDetailFailureSkipsOnlyThatPosting() {
        GustoFetcher fetcher = fetcher(ok("gusto-sage-board.html"), new HtmlPage(500, null, ""));

        assertTrue(fetcher.fetch(company, FetchFilter.none()).isEmpty());
        assertEquals(1 + 14, requested.size());
    }

    @Test
    void everyDetailCallIsPacedByTheCrawlDelay() {
        GustoFetcher fetcher = fetcher(ok("gusto-sage-board.html"), ok("gusto-sage-posting.html"));

        fetcher.fetch(company, FetchFilter.none());

        assertEquals(14, pauses);
        // Detail URLs are rebuilt on Gusto's own host, never taken from the page as-is.
        assertTrue(requested.stream().skip(1).allMatch(url -> url.startsWith("https://jobs.gusto.com/postings/")));
    }

    @Test
    void aHostileTokenIsRejectedBeforeAnyRequest() {
        GustoFetcher fetcher = fetcher(ok("gusto-sage-board.html"), null);

        for (String token : List.of("../admin", "a/b", "a?b", "a#b", "a%2Fb", "", "x".repeat(151))) {
            Company hostile = FixtureSupport.company("Hostile", AtsPlatform.GUSTO, token);
            assertThrows(AtsFetchException.class, () -> fetcher.fetch(hostile, FetchFilter.none()));
            assertThrows(AtsFetchException.class, () -> fetcher.previewBoard(hostile));
        }
        assertTrue(requested.isEmpty());
    }

    // ---------------------------------------------------------------- filter

    @Test
    void aKnownPostingCostsNoDetailCall() {
        Set<String> known = Set.copyOf(list().stream().map(Job::getExternalId)
                .filter(id -> !id.equals(VET_UUID)).toList());
        GustoFetcher fetcher = fetcher(ok("gusto-sage-board.html"), ok("gusto-sage-posting.html"));

        List<Job> jobs = fetcher.fetch(company, new FetchFilter(known, Instant.MIN));

        assertEquals(2, requested.size());
        assertTrue(requested.get(1).endsWith(VET_UUID));
        assertEquals(List.of(VET_UUID), jobs.stream().map(Job::getExternalId).toList());
    }

    /** The posting fixture is dated 2026-05-14, well past a six-day window from October. */
    @Test
    void aStalePostingIsDroppedAndCostsADetailCallOnlyOnce() {
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-09-28T00:00:00Z"));
        GustoFetcher fetcher = fetcher(ok("gusto-sage-board.html"), ok("gusto-sage-posting.html"));

        assertTrue(fetcher.fetch(company, filter).isEmpty());
        assertEquals(1 + 14, requested.size());

        requested.clear();
        assertTrue(fetcher.fetch(company, filter).isEmpty());
        assertEquals(1, requested.size(), "the second cycle reads only the board");
    }
}
