package com.jobx.fetcher.workable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping tests against real Workable responses (Apna, captured 2026-08-02).
 * List mapping is tested with fetchDetails=false (no HTTP, no repository);
 * detail enrichment is tested via the applyDetail seam.
 */
class WorkableFetcherTest {

    private WorkableFetcher fetcher;
    private Company company;

    @BeforeEach
    void setUp() {
        fetcher = new WorkableFetcher(null, new ObjectMapper(), null);
        company = FixtureSupport.company("Apna", AtsPlatform.WORKABLE, "apna");
    }

    @Test
    void mapsListItemsDedupingRepeatedShortcodes() throws Exception {
        List<Job> jobs = fetcher.parseList(FixtureSupport.fixture("workable-apna.json"), company, false);

        // Fixture has 128 list rows but only 96 unique shortcodes — the list
        // repeats a job once per posting location. In-batch dedup collapses them.
        assertEquals(96, jobs.size());
        assertEquals(96, jobs.stream().map(Job::getExternalId).distinct().count());
        for (Job job : jobs) {
            assertEquals(AtsPlatform.WORKABLE, job.getAtsPlatform());
            assertFalse(job.getExternalId().isBlank());
            assertFalse(job.getTitle().isBlank());
            assertTrue(job.getApplyUrl().startsWith("https://apply.workable.com/"));
        }
    }

    @Test
    void mapsKnownFirstJobFromList() throws Exception {
        Job job = fetcher.parseList(FixtureSupport.fixture("workable-apna.json"), company, false).get(0);

        assertEquals("01B0CB39DD", job.getExternalId());
        assertEquals("Admission Counsellor", job.getTitle());
        assertEquals("Bengaluru, India", job.getLocation());
        // published_on is date-only → midnight UTC
        assertEquals(Instant.parse("2026-06-27T00:00:00Z"), job.getPlatformPostedAt());
        // List endpoint has no description at all — that comes from the detail call
        assertNull(job.getDescription());
    }

    @Test
    void detailEnrichesDescriptionAndTimestamp() throws Exception {
        Job job = fetcher.parseList(FixtureSupport.fixture("workable-apna.json"), company, false).get(0);

        fetcher.applyDetail(FixtureSupport.fixture("workable-v2-job.json"), job);

        String description = job.getDescription();
        assertNotNull(description);
        // description + requirements + benefits all concatenated, HTML stripped
        assertTrue(description.contains("About Apna"));
        assertFalse(description.contains("<p>"));
        // Detail "published" (full ISO) replaces the date-only fallback
        assertEquals(Instant.parse("2026-06-27T00:00:00Z"), job.getPlatformPostedAt());
        // rawJson upgraded to the richer detail response
        assertTrue(job.getRawJson().contains("accountUid"));
    }

    /**
     * The ghost-account case, captured live on 2026-09-06 from
     * apply.workable.com/api/v1/widget/accounts/razorpay. Razorpay is a
     * Greenhouse customer; this Workable account has never posted a job, yet the
     * API answers 200 and echoes back a perfectly plausible company name.
     *
     * The same is true for groww, atlan, meesho and sprinto. If resolution
     * trusted the name — or merely a 200 — it would confidently hand a user a
     * board that can never produce a single match, which is the silent-empty-feed
     * failure this project has already hit three times. jobCount 0 is the only
     * honest reading, and validateBoard turns it into a rejection.
     */
    @Test
    void aGhostAccountReportsNoJobsEvenThoughItNamesTheCompany() {
        BoardPreview preview = fetcher.parsePreview(
                FixtureSupport.fixture("workable-ghost-account.json"), "razorpay");

        assertEquals("Razorpay", preview.displayName());
        assertEquals(0, preview.jobCount());
        assertTrue(preview.sampleTitles().isEmpty());

        Company ghost = FixtureSupport.company("Razorpay", AtsPlatform.WORKABLE, "razorpay");
        WorkableFetcher spy = new WorkableFetcher(null, new ObjectMapper(), null) {
            @Override
            public BoardPreview previewBoard(Company company) {
                return parsePreview(FixtureSupport.fixture("workable-ghost-account.json"),
                        company.getBoardToken());
            }
        };
        assertThrows(AtsFetchException.class, () -> spy.validateBoard(ghost));
    }

    @Test
    void previewCountsUniqueShortcodesAndNamesTheCompany() {
        BoardPreview preview = fetcher.parsePreview(
                FixtureSupport.fixture("workable-apna.json"), "apna");

        // Same dedup the full mapping applies: 128 list rows, 96 real jobs.
        assertEquals(96, preview.jobCount());
        assertEquals("Apna", preview.displayName());
        assertEquals(BoardPreview.SAMPLE_SIZE, preview.sampleTitles().size());
        preview.sampleTitles().forEach(title -> assertFalse(title.isBlank()));
    }

    @Test
    void previewRejectsAPayloadThatIsNotABoard() {
        assertThrows(AtsFetchException.class,
                () -> fetcher.parsePreview("{\"error\":\"nope\"}", "bogus"));
        assertThrows(AtsFetchException.class,
                () -> fetcher.parsePreview("not json at all", "bogus"));
    }
}
