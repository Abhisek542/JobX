package com.jobx.fetcher.workable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
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
    private WatchedCompany company;

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
}
