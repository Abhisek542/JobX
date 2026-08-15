package com.jobx.fetcher.lever;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping tests against real Lever responses (FamPay + Sprinto, captured
 * 2026-08-02). Exercises the parse seam directly — no HTTP involved.
 */
class LeverFetcherTest {

    private LeverFetcher fetcher;
    private WatchedCompany company;

    @BeforeEach
    void setUp() {
        fetcher = new LeverFetcher(null, new ObjectMapper());
        company = FixtureSupport.company("FamPay", AtsPlatform.LEVER, "fampay");
    }

    @Test
    void mapsBareArrayRoot() throws Exception {
        List<Job> jobs = fetcher.parse(FixtureSupport.fixture("lever-fampay.json"), company);

        assertEquals(14, jobs.size());
        for (Job job : jobs) {
            assertEquals(AtsPlatform.LEVER, job.getAtsPlatform());
            assertFalse(job.getExternalId().isBlank());
            assertFalse(job.getTitle().isBlank());
            assertFalse(job.getApplyUrl().isBlank());
            // createdAt (epoch ms) must convert to a sane instant, not year 1970
            assertNotNull(job.getPlatformPostedAt());
            assertTrue(job.getPlatformPostedAt().isAfter(Instant.parse("2015-01-01T00:00:00Z")));
        }
    }

    @Test
    void assemblesDescriptionFromListsAndAdditional() throws Exception {
        Job job = fetcher.parse(FixtureSupport.fixture("lever-fampay.json"), company).get(0);

        String description = job.getDescription();
        assertNotNull(description);
        // lists[] section headers and bullet content must be included —
        // descriptionPlain alone is only the intro and misses the requirements
        assertTrue(description.contains("Must Haves"));
        assertTrue(description.contains("3-5 years of experience"));
        // additionalPlain (perks/about section) must be appended
        assertTrue(description.contains("Perks That Go Beyond the Paycheck"));
        // lists[].content is HTML — must be stripped
        assertFalse(description.contains("<li>"));
        // Experience parsed out of the assembled text
        assertNotNull(job.getExpMin());
    }

    @Test
    void mapsSprintoBoardWithSameShape() throws Exception {
        WatchedCompany sprinto = FixtureSupport.company("Sprinto", AtsPlatform.LEVER, "Sprinto");

        List<Job> jobs = fetcher.parse(FixtureSupport.fixture("lever-sprinto.json"), sprinto);

        assertEquals(29, jobs.size());
        // categories.location is null-safe mapped
        assertEquals("Bengaluru", jobs.get(0).getLocation());
    }

    @Test
    void nonArrayRootIsAFailureNotAnEmptyBoard() {
        // Lever's error shape for a dead/renamed token. Returning an empty list
        // here would report a broken board as "no jobs today" and still stamp
        // last_fetched_at as a success.
        assertThrows(AtsFetchException.class,
                () -> fetcher.parse("{\"error\":\"not found\"}", company));
    }

    @Test
    void emptyBoardIsASuccessfulEmptyResult() throws Exception {
        // The other side of the same coin: a live board with nothing open is
        // an empty ARRAY, and must NOT be treated as a failure.
        assertTrue(fetcher.parse("[]", company).isEmpty());
    }
}
