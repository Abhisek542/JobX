package com.jobx.fetcher.rippling;

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

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping tests against real Rippling responses (Rippling's own board, captured
 * 2026-10-04). The list fixture is the first five jobs of the live response,
 * every per-location row kept: 15 rows, 5 uuids.
 */
class RipplingFetcherTest {

    private static final String FIRST_UUID = "75ad50c6-778f-42ee-9c63-70d1cd687202";

    private RipplingFetcher fetcher;
    private Company company;

    @BeforeEach
    void setUp() {
        fetcher = new RipplingFetcher(null, new ObjectMapper());
        company = FixtureSupport.company("Rippling", AtsPlatform.RIPPLING, "rippling");
    }

    private List<Job> listOnly() throws Exception {
        return fetcher.parseList(FixtureSupport.fixture("rippling-rippling-v1-list.json"),
                company, FetchFilter.none(), false);
    }

    @Test
    void groupsPerLocationRowsIntoOneJobPerUuid() throws Exception {
        List<Job> jobs = listOnly();

        assertEquals(5, jobs.size());
        assertEquals(5, jobs.stream().map(Job::getExternalId).distinct().count());
        for (Job job : jobs) {
            assertEquals(AtsPlatform.RIPPLING, job.getAtsPlatform());
            assertFalse(job.getTitle().isBlank());
            assertTrue(job.getApplyUrl().startsWith("https://ats.rippling.com/rippling/jobs/"));
            // The list has neither — both come from the detail call.
            assertNull(job.getDescription());
            assertNull(job.getPlatformPostedAt());
        }
    }

    @Test
    void mapsTheFirstListRow() throws Exception {
        Job job = listOnly().get(0);

        assertEquals(FIRST_UUID, job.getExternalId());
        assertEquals("Account Executive, Broker Channel (Austin & San Antonio)", job.getTitle());
        assertEquals("https://ats.rippling.com/rippling/jobs/" + FIRST_UUID, job.getApplyUrl());
        assertEquals("Austin, TX", job.getLocation());
    }

    @Test
    void joinsTheLocationsOfARepeatedJob() throws Exception {
        List<Job> jobs = listOnly();

        assertEquals("Pittsburgh, PA; Cleveland, OH", jobs.get(1).getLocation());
        // Seven locations in the fixture: three shown, the rest counted.
        assertEquals("OK; AR; LA +4 more", jobs.get(2).getLocation());
    }

    @Test
    void trimsTitles() throws Exception {
        // The live row is " Account Executive, Broker Channel (TOLA)".
        assertEquals("Account Executive, Broker Channel (TOLA)", listOnly().get(2).getTitle());
    }

    @Test
    void detailAddsDescriptionDateExperienceAndLocation() throws Exception {
        Job job = listOnly().get(0);

        fetcher.applyDetail(FixtureSupport.fixture("rippling-rippling-v2-detail.json"), job);

        String description = job.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("3+ years sales experience"));
        assertTrue(description.contains("About Rippling"));
        assertFalse(description.contains("<p"));
        assertEquals(3, job.getExpMin());
        // "2026-08-13T08:54:48.317000-07:00"
        assertEquals(Instant.parse("2026-08-13T15:54:48.317Z"), job.getPlatformPostedAt());
        assertEquals("Austin, TX", job.getLocation());
        assertTrue(job.getRawJson().contains("\"companyName\":\"Rippling\""));
        assertFalse(job.getRawJson().contains("activeJobApplication"));
    }

    @Test
    void previewCountsJobsNotRows() {
        BoardPreview preview = fetcher.parsePreview(
                FixtureSupport.fixture("rippling-rippling-v1-list.json"), "rippling");

        assertEquals(5, preview.jobCount());
        assertNull(preview.displayName());
        assertEquals(List.of(
                "Account Executive, Broker Channel (Austin & San Antonio)",
                "Account Executive, Broker Channel (Pittsburgh or Cleveland)",
                "Account Executive, Broker Channel (TOLA)"), preview.sampleTitles());
    }

    @Test
    void anEmptyArrayIsAnEmptyBoard() throws Exception {
        assertTrue(fetcher.parseList("[]", company, FetchFilter.none(), false).isEmpty());
        assertEquals(0, fetcher.parsePreview("[]", "rippling").jobCount());
    }

    @Test
    void aPayloadThatIsNotAnArrayIsAFailure() {
        // The shape of Rippling's own 404 body.
        String notFound = "{\"error_code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Job Board not found\"}";

        assertThrows(AtsFetchException.class,
                () -> fetcher.parseList(notFound, company, FetchFilter.none(), false));
        assertThrows(AtsFetchException.class, () -> fetcher.parsePreview(notFound, "rippling"));
    }

    @Test
    void aHostileTokenIsRejectedBeforeAnyRequest() {
        // A null WebClient.Builder: any request attempt would NPE, not throw this.
        for (String token : List.of("../admin", "a/b", "a?b=c", "x#y", "")) {
            Company hostile = FixtureSupport.company("Evil", AtsPlatform.RIPPLING, token);
            assertThrows(AtsFetchException.class, () -> fetcher.fetch(hostile, FetchFilter.none()), token);
            assertThrows(AtsFetchException.class, () -> fetcher.previewBoard(hostile), token);
        }
    }
}
