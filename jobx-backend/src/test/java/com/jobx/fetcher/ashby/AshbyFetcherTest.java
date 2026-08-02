package com.jobx.fetcher.ashby;

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
 * Mapping tests against a real Ashby response (Aspora, captured 2026-08-02).
 * Exercises the parse seam directly — no HTTP involved.
 */
class AshbyFetcherTest {

    private AshbyFetcher fetcher;
    private WatchedCompany company;

    @BeforeEach
    void setUp() {
        fetcher = new AshbyFetcher(null, new ObjectMapper());
        company = FixtureSupport.company("Aspora", AtsPlatform.ASHBY, "Aspora");
    }

    @Test
    void mapsAllListedJobs() throws Exception {
        List<Job> jobs = fetcher.parse(FixtureSupport.fixture("ashby-aspora.json"), company);

        // Fixture holds 18 jobs, all isListed=true
        assertEquals(18, jobs.size());
        for (Job job : jobs) {
            assertEquals(AtsPlatform.ASHBY, job.getAtsPlatform());
            assertSame(company, job.getCompany());
            assertFalse(job.getExternalId().isBlank());
            assertFalse(job.getTitle().isBlank());
            assertTrue(job.getApplyUrl().startsWith("https://jobs.ashbyhq.com/"));
            assertNotNull(job.getFirstSeenAt());
            assertNotNull(job.getRawJson());
        }
    }

    @Test
    void mapsKnownFirstJob() throws Exception {
        Job job = fetcher.parse(FixtureSupport.fixture("ashby-aspora.json"), company).get(0);

        assertEquals("2315ceee-2b8a-4c94-8866-39a4ec49d5f0", job.getExternalId());
        assertEquals("Senior DevOps Engineer", job.getTitle());
        // Live data has a trailing space ("Bangalore ") — must be trimmed
        assertEquals("Bangalore", job.getLocation());
        assertEquals(Instant.parse("2026-06-04T12:41:40.046Z"), job.getPlatformPostedAt());
        // descriptionPlain used directly — plain text, no residual HTML tags
        assertNotNull(job.getDescription());
        assertFalse(job.getDescription().contains("<"));
    }

    @Test
    void skipsUnlistedJobs() throws Exception {
        String body = """
                {"jobs":[
                  {"id":"a","title":"Hidden","isListed":false,"jobUrl":"u"},
                  {"id":"b","title":"Visible","isListed":true,"jobUrl":"u"}
                ],"apiVersion":"1"}""";

        List<Job> jobs = fetcher.parse(body, company);

        assertEquals(1, jobs.size());
        assertEquals("b", jobs.get(0).getExternalId());
    }
}
