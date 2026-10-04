package com.jobx.fetcher.bamboohr;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping tests against real BambooHR responses (Off Duty Management, captured
 * 2026-10-04), plus the redirect and hostile-token paths.
 */
class BambooHrFetcherTest {

    private BambooHrFetcher fetcher;
    private Company company;

    @BeforeEach
    void setUp() {
        fetcher = new BambooHrFetcher(null, new ObjectMapper());
        company = FixtureSupport.company("Off Duty Management", AtsPlatform.BAMBOOHR, "offdutymanagement");
    }

    private List<Job> listOnly() throws Exception {
        return fetcher.parseList(FixtureSupport.fixture("bamboohr-offdutymanagement-list.json"),
                company, FetchFilter.none(), false);
    }

    /** A fetcher whose every request gets the given response, recording the URLs asked for. */
    private BambooHrFetcher answering(ResponseEntity<String> response, List<String> urls) {
        return new BambooHrFetcher(null, new ObjectMapper()) {
            @Override
            ResponseEntity<String> get(String url) {
                urls.add(url);
                return response;
            }
        };
    }

    @Test
    void mapsListRows() throws Exception {
        List<Job> jobs = listOnly();

        assertEquals(2, jobs.size());
        Job first = jobs.get(0);
        assertEquals(AtsPlatform.BAMBOOHR, first.getAtsPlatform());
        assertEquals("103", first.getExternalId());
        assertEquals("Backend Software Engineer (API-focused)", first.getTitle());
        assertEquals("https://offdutymanagement.bamboohr.com/careers/103", first.getApplyUrl());
        assertEquals("Katy, Texas", first.getLocation());
        // The list has neither — both come from the detail call.
        assertNull(first.getDescription());
        assertNull(first.getPlatformPostedAt());
    }

    @Test
    void fallsBackToAtsLocationWhenLocationIsEmpty() throws Exception {
        // Job 111 has location {city:null, state:null} and the real place in atsLocation.
        assertEquals("Charlotte, North Carolina, United States", listOnly().get(1).getLocation());
    }

    @Test
    void detailAddsDescriptionDateAndExperience() throws Exception {
        Job job = listOnly().get(0);

        fetcher.applyDetail(FixtureSupport.fixture("bamboohr-offdutymanagement-detail.json"), job);

        assertTrue(job.getDescription().contains("3+ years of professional backend"));
        assertFalse(job.getDescription().contains("<p>"));
        assertEquals(3, job.getExpMin());
        // datePosted "2026-07-16" is date-only → midnight UTC
        assertEquals(Instant.parse("2026-07-16T00:00:00Z"), job.getPlatformPostedAt());
        assertEquals("Katy, Texas", job.getLocation());
        assertEquals("https://offdutymanagement.bamboohr.com/careers/103", job.getApplyUrl());
        // Only the posting is kept, not the application form.
        assertFalse(job.getRawJson().contains("formFields"));
    }

    @Test
    void previewCountsRolesWithNoName() {
        BoardPreview preview = fetcher.parsePreview(
                FixtureSupport.fixture("bamboohr-offdutymanagement-list.json"), "offdutymanagement");

        assertEquals(2, preview.jobCount());
        assertNull(preview.displayName());
        assertEquals("Backend Software Engineer (API-focused)", preview.sampleTitles().get(0));
    }

    @Test
    void aDormantAccountIsAnEmptyBoardNotAFailure() throws Exception {
        // andela, captured live: {"meta":{"totalCount":0},"result":[]}
        String empty = FixtureSupport.fixture("bamboohr-andela-empty.json");

        assertTrue(fetcher.parseList(empty, company, FetchFilter.none(), false).isEmpty());
        assertEquals(0, fetcher.parsePreview(empty, "andela").jobCount());
    }

    @Test
    void aPayloadWithoutAResultArrayIsAFailure() {
        assertThrows(AtsFetchException.class,
                () -> fetcher.parseList("{\"meta\":{}}", company, FetchFilter.none(), false));
        assertThrows(AtsFetchException.class, () -> fetcher.parsePreview("<html></html>", "acme"));
    }

    @Test
    void aRedirectMeansTheBoardDoesNotExist() {
        // What every unknown subdomain answers, live.
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("https://www.bamboohr.com/"));
        ResponseEntity<String> redirect = new ResponseEntity<>(headers, HttpStatus.FOUND);
        List<String> urls = new ArrayList<>();

        BambooHrFetcher redirected = answering(redirect, urls);
        Company unknown = FixtureSupport.company("Nope", AtsPlatform.BAMBOOHR, "nosuchtenant");

        AtsFetchException e = assertThrows(AtsFetchException.class, () -> redirected.previewBoard(unknown));
        assertTrue(e.getMessage().contains("'nosuchtenant' does not exist"), e.getMessage());
        assertThrows(AtsFetchException.class, () -> redirected.fetch(unknown, FetchFilter.none()));
        assertEquals("https://nosuchtenant.bamboohr.com/careers/list", urls.get(0));
    }

    @Test
    void theHostIsBuiltFromTheLowerCasedLabel() {
        List<String> urls = new ArrayList<>();
        BambooHrFetcher ok = answering(ResponseEntity.ok(
                FixtureSupport.fixture("bamboohr-andela-empty.json")), urls);

        ok.previewBoard(FixtureSupport.company("Andela", AtsPlatform.BAMBOOHR, "Andela"));

        assertEquals(List.of("https://andela.bamboohr.com/careers/list"), urls);
    }

    @Test
    void aHostileTokenIsRejectedBeforeAnyRequest() {
        List<String> urls = new ArrayList<>();
        BambooHrFetcher spy = answering(ResponseEntity.ok("{\"result\":[]}"), urls);

        for (String token : List.of("evil.com#", "a.b", "x@y", "a/../b", "-acme", "")) {
            Company hostile = FixtureSupport.company("Evil", AtsPlatform.BAMBOOHR, token);
            assertThrows(AtsFetchException.class, () -> spy.fetch(hostile, FetchFilter.none()), token);
            assertThrows(AtsFetchException.class, () -> spy.previewBoard(hostile), token);
        }
        assertTrue(urls.isEmpty(), "no request may be made for a hostile token: " + urls);
    }
}
