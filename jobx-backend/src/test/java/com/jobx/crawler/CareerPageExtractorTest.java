package com.jobx.crawler;

import com.jobx.enums.CrawlMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fixture-based checks of the extractor and the page verdicts (PR E1). */
class CareerPageExtractorTest {

    private static final URI PAGE = URI.create("https://acme.example/careers");

    static String fixture(String name) {
        try (InputStream in = CareerPageExtractorTest.class.getResourceAsStream("/fixtures/custom/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsJobPostingsOutOfAJsonLdGraph() {
        List<ExtractedJob> jobs = CareerPageExtractor.extract(PAGE, fixture("jsonld-graph.html"));

        assertEquals(List.of("Senior Backend Engineer", "Product Designer"),
                jobs.stream().map(ExtractedJob::title).toList());
        ExtractedJob first = jobs.get(0);
        assertEquals("https://acme.example/careers/backend-engineer", first.url(), "tracking params stripped");
        assertTrue(first.location().contains("Bengaluru"));
        assertEquals(Instant.parse("2026-10-30T00:00:00Z"), first.postedAt());
        assertNotNull(first.description());
        assertTrue(first.description().contains("Build Java services"));
    }

    @Test
    void readsARepeatedStaticListingAndIgnoresNavAndOffSiteLinks() {
        List<ExtractedJob> jobs = CareerPageExtractor.extract(PAGE, fixture("static-listing.html"));

        assertEquals(List.of("Backend Engineer", "Frontend Developer", "Data Analyst", "Engineering Manager"),
                jobs.stream().map(ExtractedJob::title).toList());
        assertEquals("https://acme.example/careers/jobs/1201-backend-engineer", jobs.get(0).url());
        assertEquals("https://acme.example/careers/jobs/1204-engineering-manager", jobs.get(3).url());
        assertEquals("Bengaluru", jobs.get(0).location());
    }

    @Test
    void aPageAboutWorkingThereIsNotAListing() {
        assertEquals(List.of(), CareerPageExtractor.extract(PAGE, fixture("noise.html")));
        assertEquals(CustomCrawler.Status.NOTHING_RECOGNISED,
                CustomCrawler.analyse(PAGE, fixture("noise.html"), CrawlMode.STATIC).status());
    }

    @Test
    void aPageSayingItHasNoOpeningsIsAHealthyEmptyBoard() {
        CustomCrawler.Outcome outcome = CustomCrawler.analyse(PAGE, fixture("no-openings.html"), CrawlMode.STATIC);
        assertEquals(CustomCrawler.Status.NO_OPENINGS, outcome.status());
        assertTrue(outcome.isWatchable());
    }

    @Test
    void aBotChallengeIsReportedAndNeverReadAsAPage() {
        CustomCrawler.Outcome outcome = CustomCrawler.analyse(PAGE, fixture("challenge.html"), CrawlMode.STATIC);
        assertEquals(CustomCrawler.Status.BOT_PROTECTED, outcome.status());
        assertFalse(outcome.isWatchable());
        assertTrue(outcome.describe().contains("bot protection"));
    }

    @Test
    void jobsVerdictCarriesTheCanonicalUrlAndMode() {
        CustomCrawler.Outcome outcome = CustomCrawler.analyse(
                URI.create("https://Acme.example/careers/?utm_source=li#top"), fixture("static-listing.html"),
                CrawlMode.RENDER);
        assertEquals(CustomCrawler.Status.JOBS, outcome.status());
        assertEquals("https://acme.example/careers", outcome.canonicalUrl());
        assertEquals(CrawlMode.RENDER, outcome.mode());
        assertEquals("Acme", outcome.siteName());
    }

    @Test
    void postingLinkShapes() {
        assertTrue(CareerPageExtractor.looksLikePosting("/careers/jobs/1201-backend-engineer", null));
        assertFalse(CareerPageExtractor.looksLikePosting("/careers", null));
        assertFalse(CareerPageExtractor.looksLikePosting("/about", null));
    }
}
