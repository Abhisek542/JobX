package com.jobx.fetcher.jobvite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetchFilter;
import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests against real Jobvite pages captured 2026-10-04: Egnyte's one-page board
 * (26 jobs), the first and last search pages of Nutanix's (266 jobs, 50 a
 * page), an Egnyte job page, and the "No results found" state.
 */
class JobviteFetcherTest {

    private JobviteFetcher fetcher;
    private Company egnyte;

    @BeforeEach
    void setUp() {
        fetcher = new JobviteFetcher(null, new ObjectMapper(), 20);
        egnyte = FixtureSupport.company("Egnyte", AtsPlatform.JOBVITE, "egnyte");
    }

    /** A fetcher that serves pages by URL and records every URL requested. */
    private static JobviteFetcher serving(Function<String, ResponseEntity<String>> pages,
                                          List<String> urls, int maxPages) {
        return new JobviteFetcher(null, new ObjectMapper(), maxPages) {
            @Override
            ResponseEntity<String> get(String url) {
                urls.add(url);
                return pages.apply(url);
            }
        };
    }

    private static ResponseEntity<String> ok(String fixture) {
        return ResponseEntity.ok(FixtureSupport.fixture(fixture));
    }

    // ------------------------------------------------------------- list pages

    @Test
    void parsesAOnePageBoard() {
        JobviteFetcher.Page page = fetcher.parsePage(FixtureSupport.fixture("jobvite-egnyte-search.html"), "egnyte");

        assertEquals(26, page.cards().size());
        assertEquals(26, page.total());
        assertFalse(page.hasNext());
        assertFalse(page.saysNoOpenings());
        assertEquals("Egnyte", page.displayName());

        JobviteFetcher.Card first = page.cards().get(0);
        assertEquals("oBjQAfw4", first.id());
        assertEquals("Creative Director", first.title());
        assertEquals("https://jobs.jobvite.com/egnyte/job/oBjQAfw4", first.url());
        // A multi-site job shows a count on the list; the detail page has the places.
        assertEquals("2 Locations", first.location());

        JobviteFetcher.Card accountant = page.cards().stream()
                .filter(card -> card.id().equals("oNyuAfw9")).findFirst().orElseThrow();
        assertEquals("Mountain View, California", accountant.location());
    }

    @Test
    void readsPaginationFromTheFirstAndLastPages() {
        JobviteFetcher.Page first = fetcher.parsePage(
                FixtureSupport.fixture("jobvite-nutanix-search-p0.html"), "nutanix");
        JobviteFetcher.Page last = fetcher.parsePage(
                FixtureSupport.fixture("jobvite-nutanix-search-p5.html"), "nutanix");

        assertEquals(50, first.cards().size());
        assertEquals(266, first.total());
        assertTrue(first.hasNext());
        assertEquals(16, last.cards().size());
        assertFalse(last.hasNext());
    }

    @Test
    void recognisesJobvitesNoOpeningsMessage() {
        JobviteFetcher.Page page = fetcher.parsePage(
                FixtureSupport.fixture("jobvite-egnyte-noresults.html"), "egnyte");

        assertTrue(page.cards().isEmpty());
        assertTrue(page.saysNoOpenings());
    }

    @Test
    void ignoresLinksToAnotherCompanysJobs() {
        String html = "<table><tr><td class='jv-job-list-name'><a href='/other/job/abc'>X</a></td></tr></table>";

        assertTrue(fetcher.parsePage(html, "egnyte").cards().isEmpty());
    }

    // ---------------------------------------------------------------- detail

    @Test
    void detailAddsDescriptionDateLocationAndExperience() {
        Job job = new Job();
        job.setExternalId("oNyuAfw9");

        var posted = fetcher.applyDetail(FixtureSupport.fixture("jobvite-egnyte-detail.html"), job);

        assertTrue(job.getDescription().startsWith("SENIOR G/L ACCOUNTANT"), job.getDescription());
        assertTrue(job.getDescription().contains("5+ years of progressive work experience"));
        assertEquals(5, job.getExpMin());
        assertEquals("2026-07-14", posted.toString());
        assertEquals(Instant.parse("2026-07-14T00:00:00Z"), job.getPlatformPostedAt());
        assertEquals("Mountain View, California", job.getLocation());
        assertTrue(job.getRawJson().contains("\"identifier\":\"oNyuAfw9\""));
    }

    @Test
    void aJobPageWithNoDescriptionIsAFailure() {
        Job job = new Job();
        job.setExternalId("x");

        assertThrows(AtsFetchException.class,
                () -> fetcher.applyDetail("<html><body><h2 class='jv-header'>Role</h2></body></html>", job));
    }

    // --------------------------------------------------------------- preview

    @Test
    void previewCountsTheWholeBoardFromThePaginationText() {
        BoardPreview preview = fetcher.parsePreview(
                FixtureSupport.fixture("jobvite-nutanix-search-p0.html"), "nutanix");

        assertEquals(266, preview.jobCount());
        assertEquals("Nutanix", preview.displayName());
        assertEquals("Administrative Assistant - Sales", preview.sampleTitles().get(0));
        assertEquals(BoardPreview.SAMPLE_SIZE, preview.sampleTitles().size());
    }

    @Test
    void previewOfAnEmptyBoardIsZero() {
        assertEquals(0, fetcher.parsePreview(
                FixtureSupport.fixture("jobvite-egnyte-noresults.html"), "egnyte").jobCount());
    }

    @Test
    void zeroCardsWithoutTheMarkerIsAFailureNotAnEmptyBoard() {
        // A changed layout looks exactly like this: a 200 page the selectors miss.
        String html = "<html><body><article class='jv-page-body'><ul class='new-job-list'>"
                + "<li><a href='/egnyte/job/abc'>Engineer</a></li></ul></article></body></html>";
        List<String> urls = new ArrayList<>();
        JobviteFetcher changed = serving(url -> ResponseEntity.ok(html), urls, 20);

        assertThrows(AtsFetchException.class, () -> changed.parsePreview(html, "egnyte"));
        assertThrows(AtsFetchException.class, () -> changed.fetch(egnyte, FetchFilter.none()));
    }

    // ----------------------------------------------------------------- fetch

    @Test
    void fetchReadsOnlyUnknownJobsDetailPages() {
        List<String> urls = new ArrayList<>();
        JobviteFetcher live = serving(url -> url.contains("/search")
                ? ok("jobvite-egnyte-search.html") : ok("jobvite-egnyte-detail.html"), urls, 20);
        // Every card on the board except the accountant is already known.
        List<String> allIds = fetcher.parsePage(FixtureSupport.fixture("jobvite-egnyte-search.html"), "egnyte")
                .cards().stream().map(JobviteFetcher.Card::id).toList();
        Set<String> known = new java.util.HashSet<>(allIds);
        known.remove("oNyuAfw9");

        List<Job> jobs = live.fetch(egnyte, new FetchFilter(known, Instant.MIN));

        assertEquals(1, jobs.size());
        assertEquals("oNyuAfw9", jobs.get(0).getExternalId());
        assertEquals(List.of(
                "https://jobs.jobvite.com/egnyte/search?p=0",
                "https://jobs.jobvite.com/egnyte/job/oNyuAfw9"), urls);
    }

    @Test
    void fetchFollowsPaginationUntilTheLastPage() {
        List<String> urls = new ArrayList<>();
        Map<String, String> pages = Map.of(
                "https://jobs.jobvite.com/nutanix/search?p=0", "jobvite-nutanix-search-p0.html",
                // Stand-in for page 1: the real last page, which has no "Next" link.
                "https://jobs.jobvite.com/nutanix/search?p=1", "jobvite-nutanix-search-p5.html");
        JobviteFetcher live = serving(url -> ok(pages.get(url)), urls, 20);
        Company nutanix = FixtureSupport.company("Nutanix", AtsPlatform.JOBVITE, "nutanix");

        // Everything known: only the list pages are read.
        List<String> ids = new ArrayList<>();
        for (String fixture : pages.values()) {
            fetcher.parsePage(FixtureSupport.fixture(fixture), "nutanix").cards().forEach(c -> ids.add(c.id()));
        }
        List<Job> jobs = live.fetch(nutanix, new FetchFilter(Set.copyOf(ids), Instant.MIN));

        assertTrue(jobs.isEmpty());
        assertEquals(List.of(
                "https://jobs.jobvite.com/nutanix/search?p=0",
                "https://jobs.jobvite.com/nutanix/search?p=1"), urls);
    }

    @Test
    void fetchStopsAtThePageCap() {
        List<String> urls = new ArrayList<>();
        JobviteFetcher capped = serving(url -> ok("jobvite-nutanix-search-p0.html"), urls, 1);
        Company nutanix = FixtureSupport.company("Nutanix", AtsPlatform.JOBVITE, "nutanix");
        List<String> ids = fetcher.parsePage(FixtureSupport.fixture("jobvite-nutanix-search-p0.html"), "nutanix")
                .cards().stream().map(JobviteFetcher.Card::id).toList();

        capped.fetch(nutanix, new FetchFilter(Set.copyOf(ids), Instant.MIN));

        assertEquals(List.of("https://jobs.jobvite.com/nutanix/search?p=0"), urls);
    }

    @Test
    void anEmptyBoardIsAnEmptyListNotAFailure() {
        List<String> urls = new ArrayList<>();
        JobviteFetcher empty = serving(url -> ok("jobvite-egnyte-noresults.html"), urls, 20);

        assertTrue(empty.fetch(egnyte, FetchFilter.none()).isEmpty());
    }

    @Test
    void aFailedDetailPageSkipsOnlyThatJob() {
        List<String> urls = new ArrayList<>();
        JobviteFetcher flaky = serving(url -> {
            if (url.contains("/search")) {
                return ok("jobvite-egnyte-search.html");
            }
            if (url.endsWith("/oBjQAfw4")) {
                // What a removed job answers, live.
                HttpHeaders headers = new HttpHeaders();
                headers.setLocation(URI.create("https://jobs.jobvite.com/careers/egnyte/jobs?error=404"));
                return new ResponseEntity<>(headers, HttpStatus.SEE_OTHER);
            }
            return ok("jobvite-egnyte-detail.html");
        }, urls, 20);

        List<Job> jobs = flaky.fetch(egnyte, FetchFilter.none());

        assertEquals(25, jobs.size());
        assertTrue(jobs.stream().noneMatch(job -> job.getExternalId().equals("oBjQAfw4")));
    }

    @Test
    void aPostingPastTheTtlIsNotStored() {
        List<String> urls = new ArrayList<>();
        JobviteFetcher live = serving(url -> url.contains("/search")
                ? ok("jobvite-egnyte-search.html") : ok("jobvite-egnyte-detail.html"), urls, 20);

        // The detail fixture is dated 2026-07-14 for every job served here.
        List<Job> jobs = live.fetch(egnyte, new FetchFilter(Set.of(), Instant.parse("2026-07-15T00:00:01Z")));

        assertTrue(jobs.isEmpty());
    }

    @Test
    void aRedirectMeansTheBoardDoesNotExist() {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("http://search.jobvite.com/?invalid=1"));
        List<String> urls = new ArrayList<>();
        JobviteFetcher gone = serving(url -> new ResponseEntity<>(headers, HttpStatus.FOUND), urls, 20);
        Company unknown = FixtureSupport.company("Nope", AtsPlatform.JOBVITE, "nosuchco");

        AtsFetchException e = assertThrows(AtsFetchException.class, () -> gone.previewBoard(unknown));
        assertTrue(e.getMessage().contains("'nosuchco' does not exist"), e.getMessage());
        assertThrows(AtsFetchException.class, () -> gone.fetch(unknown, FetchFilter.none()));
    }

    @Test
    void aHostileTokenIsRejectedBeforeAnyRequest() {
        List<String> urls = new ArrayList<>();
        JobviteFetcher spy = serving(url -> ok("jobvite-egnyte-search.html"), urls, 20);

        for (String token : List.of("../admin", "a/b", "a?b=1", "x#y", ".", "")) {
            Company hostile = FixtureSupport.company("Evil", AtsPlatform.JOBVITE, token);
            assertThrows(AtsFetchException.class, () -> spy.fetch(hostile, FetchFilter.none()), token);
            assertThrows(AtsFetchException.class, () -> spy.previewBoard(hostile), token);
        }
        assertTrue(urls.isEmpty(), "no request may be made for a hostile token: " + urls);
    }
}
