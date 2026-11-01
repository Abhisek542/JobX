package com.jobx.fetcher.custom;

import com.jobx.crawler.BrowserRenderer;
import com.jobx.crawler.CustomCrawler;
import com.jobx.crawler.ExtractedJob;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.enums.CrawlMode;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.FetchFilter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CustomPageFetcherTest {

    private static final String BOARD = "https://acme.example/careers";

    private final CustomCrawler crawler = mock(CustomCrawler.class);

    /** Counts detail calls; null URL in {@code failing} answers empty. */
    private static final class CountingFetcher extends CustomPageFetcher {
        final List<String> detailCalls = new ArrayList<>();
        final Set<String> failing;
        final String detailHtml;

        CountingFetcher(CustomCrawler crawler, int cap, Set<String> failing, String detailHtml) {
            super(crawler, cap);
            this.failing = failing;
            this.detailHtml = detailHtml;
        }

        @Override
        Optional<Document> fetchDetail(String url) {
            detailCalls.add(url);
            if (failing.contains(url)) {
                return Optional.empty();
            }
            return Optional.of(Jsoup.parse(detailHtml, url));
        }
    }

    private static Company company(CrawlMode mode) {
        Company c = new Company();
        c.setAtsPlatform(AtsPlatform.CUSTOM);
        c.setBoardToken(BOARD);
        c.setDisplayName("Acme");
        c.setCrawlMode(mode);
        return c;
    }

    private static ExtractedJob listed(int n) {
        return new ExtractedJob("Engineer " + n, BOARD + "/jobs/" + n, "Pune", null, null, null, null);
    }

    private static CustomCrawler.Outcome outcome(CustomCrawler.Status status, List<ExtractedJob> jobs) {
        return new CustomCrawler.Outcome(status, URI.create(BOARD), BOARD, jobs, CrawlMode.STATIC, "Acme", "");
    }

    private static final String DETAIL = "<html><body><main><h1>Engineer</h1>"
            + "<p>We need 3+ years of Java experience building backend services for our customers.</p>"
            + "</main></body></html>";

    @Test
    void knownPostingsCostNoDetailCalls() {
        CountingFetcher fetcher = new CountingFetcher(crawler, 25, Set.of(), DETAIL);
        List<ExtractedJob> jobs = List.of(listed(1), listed(2));
        Set<String> known = Set.of(CustomPageFetcher.externalId(listed(1)), CustomPageFetcher.externalId(listed(2)));

        List<Job> result = fetcher.toJobs(company(CrawlMode.STATIC), jobs, new FetchFilter(known, null));

        assertEquals(List.of(), result);
        assertEquals(List.of(), fetcher.detailCalls);
    }

    @Test
    void newPostingsAreFilledFromTheirDetailPage() {
        CountingFetcher fetcher = new CountingFetcher(crawler, 25, Set.of(), DETAIL);

        List<Job> result = fetcher.toJobs(company(CrawlMode.STATIC), List.of(listed(1)), FetchFilter.none());

        assertEquals(1, result.size());
        Job job = result.get(0);
        assertEquals(AtsPlatform.CUSTOM, job.getAtsPlatform());
        assertEquals(BOARD + "/jobs/1", job.getApplyUrl());
        assertEquals(CustomPageFetcher.externalId(listed(1)), job.getExternalId());
        assertNotNull(job.getDescription());
        assertTrue(job.getDescription().contains("Java"));
        assertNotNull(job.getFirstSeenAt());
    }

    @Test
    void aDescriptionOnTheListSkipsTheDetailCall() {
        CountingFetcher fetcher = new CountingFetcher(crawler, 25, Set.of(), DETAIL);
        ExtractedJob withDesc = new ExtractedJob("Engineer", BOARD + "/jobs/9", null, null, null, null, "Build things");

        List<Job> result = fetcher.toJobs(company(CrawlMode.STATIC), List.of(withDesc), FetchFilter.none());

        assertEquals(1, result.size());
        assertEquals(List.of(), fetcher.detailCalls);
    }

    @Test
    void detailCallsAreCappedAndTheRestDeferred() {
        CountingFetcher fetcher = new CountingFetcher(crawler, 3, Set.of(), DETAIL);
        List<ExtractedJob> jobs = IntStream.range(0, 10).mapToObj(CustomPageFetcherTest::listed).toList();

        List<Job> result = fetcher.toJobs(company(CrawlMode.STATIC), jobs, FetchFilter.none());

        assertEquals(3, fetcher.detailCalls.size());
        assertEquals(3, result.size(), "deferred postings are not stored, so the next cycle picks them up");
    }

    @Test
    void aFailedDetailSkipsThePostingForRetry() {
        CountingFetcher fetcher = new CountingFetcher(crawler, 25, Set.of(BOARD + "/jobs/1"), DETAIL);

        List<Job> result = fetcher.toJobs(company(CrawlMode.STATIC), List.of(listed(1), listed(2)), FetchFilter.none());

        assertEquals(List.of("Engineer 2"), result.stream().map(Job::getTitle).toList());
    }

    @Test
    void tooOldPostingsAreDroppedButTheCutoffDayIsKept() {
        CountingFetcher fetcher = new CountingFetcher(crawler, 25, Set.of(), DETAIL);
        Instant cutoff = Instant.parse("2026-10-26T12:00:00Z");
        ExtractedJob old = new ExtractedJob("Old", BOARD + "/jobs/old", null, null, null,
                Instant.parse("2026-10-20T00:00:00Z"), "d");
        ExtractedJob cutoffDay = new ExtractedJob("Edge", BOARD + "/jobs/edge", null, null, null,
                Instant.parse("2026-10-26T00:00:00Z"), "d");

        List<Job> result = fetcher.toJobs(company(CrawlMode.STATIC), List.of(old, cutoffDay),
                new FetchFilter(Set.of(), cutoff));

        assertEquals(List.of("Edge"), result.stream().map(Job::getTitle).toList());
    }

    @Test
    void aStalePostingFoundOnlyOnItsDetailPageIsRememberedNotRefetched() {
        String staleDetail = "<html><head><script type=\"application/ld+json\">"
                + "{\"@type\":\"JobPosting\",\"title\":\"Engineer\",\"datePosted\":\"2026-01-01\"}"
                + "</script></head><body><p>Old role</p></body></html>";
        CountingFetcher fetcher = new CountingFetcher(crawler, 25, Set.of(), staleDetail);
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-10-26T00:00:00Z"));

        assertEquals(List.of(), fetcher.toJobs(company(CrawlMode.STATIC), List.of(listed(1)), filter));
        assertEquals(List.of(), fetcher.toJobs(company(CrawlMode.STATIC), List.of(listed(1)), filter));
        assertEquals(1, fetcher.detailCalls.size());
    }

    @Test
    void fetchReadsTheBoardTheWayItWasAddedOnTheBackgroundLane() {
        when(crawler.read(BOARD, CrawlMode.RENDER, BrowserRenderer.Lane.BACKGROUND))
                .thenReturn(outcome(CustomCrawler.Status.NO_OPENINGS, List.of()));

        assertEquals(List.of(), new CustomPageFetcher(crawler, 25).fetch(company(CrawlMode.RENDER), FetchFilter.none()));
        verify(crawler).read(BOARD, CrawlMode.RENDER, BrowserRenderer.Lane.BACKGROUND);
    }

    @Test
    void aBoardWithNoCrawlModeIsReadStatically() {
        when(crawler.read(eq(BOARD), any(), any())).thenReturn(outcome(CustomCrawler.Status.NO_OPENINGS, List.of()));

        new CustomPageFetcher(crawler, 25).fetch(company(null), FetchFilter.none());

        verify(crawler).read(BOARD, CrawlMode.STATIC, BrowserRenderer.Lane.BACKGROUND);
    }

    @Test
    void zeroRecognisedJobsFailsLoudly() {
        for (CustomCrawler.Status status : List.of(CustomCrawler.Status.NOTHING_RECOGNISED,
                CustomCrawler.Status.BOT_PROTECTED, CustomCrawler.Status.ROBOTS_DISALLOWED,
                CustomCrawler.Status.UNREACHABLE, CustomCrawler.Status.UNSAFE)) {
            when(crawler.read(eq(BOARD), any(), any())).thenReturn(outcome(status, List.of()));
            AtsFetchException e = assertThrows(AtsFetchException.class,
                    () -> new CustomPageFetcher(crawler, 25).fetch(company(CrawlMode.STATIC), FetchFilter.none()));
            assertTrue(e.getMessage().contains(BOARD), status + ": " + e.getMessage());
        }
    }

    @Test
    void validateBoardRecordsTheModeThatFoundTheJobs() {
        when(crawler.discover(BOARD, BrowserRenderer.Lane.INTERACTIVE)).thenReturn(new CustomCrawler.Outcome(
                CustomCrawler.Status.JOBS, URI.create(BOARD), BOARD, List.of(listed(1)), CrawlMode.RENDER, "Acme", ""));
        Company company = company(null);

        new CustomPageFetcher(crawler, 25).validateBoard(company);

        assertEquals(CrawlMode.RENDER, company.getCrawlMode());
    }

    @Test
    void validateBoardRefusesAnUnreadablePage() {
        when(crawler.discover(BOARD, BrowserRenderer.Lane.INTERACTIVE))
                .thenReturn(outcome(CustomCrawler.Status.NOTHING_RECOGNISED, List.of()));
        Company company = company(null);

        assertThrows(AtsFetchException.class, () -> new CustomPageFetcher(crawler, 25).validateBoard(company));
        assertNull(company.getCrawlMode());
    }

    @Test
    void previewShowsCountAndSampleTitles() {
        when(crawler.discover(BOARD, BrowserRenderer.Lane.INTERACTIVE))
                .thenReturn(outcome(CustomCrawler.Status.JOBS, List.of(listed(1), listed(2))));

        var preview = new CustomPageFetcher(crawler, 25).previewBoard(company(null));

        assertEquals(2, preview.jobCount());
        assertEquals("Engineer 1", preview.sampleTitles().get(0));
    }
}
