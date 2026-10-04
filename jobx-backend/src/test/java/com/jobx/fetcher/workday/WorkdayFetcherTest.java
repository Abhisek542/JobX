package com.jobx.fetcher.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.BoardTokens.WorkdayToken;
import com.jobx.fetcher.FetchFilter;
import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures are real responses from Salesforce's live Workday board
 * (salesforce/wd12/External_Career_Site), captured 2026-10-04: the first list
 * page (total 1523, 20 rows) and one posting's detail.
 */
class WorkdayFetcherTest {

    private static final String TOKEN = "salesforce/wd12/External_Career_Site";
    private static final WorkdayToken WD = WorkdayToken.parse(TOKEN);

    private ObjectMapper mapper;
    private WorkdayFetcher fetcher;
    private Company company;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        // webClientBuilder is never touched by the parse seams.
        fetcher = new WorkdayFetcher(null, mapper, 60);
        company = FixtureSupport.company("Salesforce", AtsPlatform.WORKDAY, TOKEN);
    }

    private List<JsonNode> listRows() throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        mapper.readTree(FixtureSupport.fixture("workday-salesforce-list.json")).get("jobPostings").forEach(rows::add);
        return rows;
    }

    @Test
    void previewReadsTotalAndTheFirstTitles() {
        BoardPreview preview = fetcher.parsePreview(FixtureSupport.fixture("workday-salesforce-list.json"), WD);

        assertNull(preview.displayName());
        assertEquals(1523, preview.jobCount());
        assertEquals(BoardPreview.SAMPLE_SIZE, preview.sampleTitles().size());
        assertEquals("Renewals Manager - Informatica", preview.sampleTitles().get(0));
    }

    @Test
    void mapsAListRow() throws Exception {
        JsonNode first = listRows().get(0);
        String path = first.path("externalPath").asText();
        Job job = fetcher.mapListItem(first, company, WD, path);

        assertEquals("/job/Australia---Sydney/Renewals-Manager---Informatica_JR353789-1", job.getExternalId());
        assertEquals("Renewals Manager - Informatica", job.getTitle());
        assertEquals("Australia - Sydney", job.getLocation());
        assertEquals("https://salesforce.wd12.myworkdayjobs.com/External_Career_Site"
                + "/job/Australia---Sydney/Renewals-Manager---Informatica_JR353789-1", job.getApplyUrl());
        assertEquals(AtsPlatform.WORKDAY, job.getAtsPlatform());
        assertSame(company, job.getCompany());
    }

    @Test
    void aLocationCountIsNotALocation() throws Exception {
        JsonNode multi = listRows().stream()
                .filter(n -> n.path("locationsText").asText().endsWith("Locations"))
                .findFirst().orElseThrow();
        Job job = fetcher.mapListItem(multi, company, WD, multi.path("externalPath").asText());

        assertNull(job.getLocation());
    }

    @Test
    void appliesTheDetail() throws Exception {
        Job job = new Job();
        job.setExternalId("/job/India---Hyderabad/Performance-Engineer---Software-Engineering-SMTS_JR358291");

        fetcher.applyDetail(FixtureSupport.fixture("workday-salesforce-detail.json"), job);

        assertNotNull(job.getDescription());
        assertFalse(job.getDescription().contains("<"), "description must be HTML-stripped");
        assertTrue(job.getDescription().length() > 1000);
        assertEquals(Instant.parse("2026-10-03T00:00:00Z"), job.getPlatformPostedAt());
        assertEquals("India - Hyderabad", job.getLocation());
        assertFalse(job.getRawJson().contains("similarJobs"));
    }

    @Test
    void detailJoinsAdditionalLocations() throws Exception {
        Job job = new Job();
        fetcher.applyDetail("""
                {"jobPostingInfo":{"jobDescription":"<p>Hi</p>","location":"Texas - Dallas",
                 "additionalLocations":["Georgia - Atlanta","Indiana - Indianapolis"],"startDate":"2026-10-02"}}
                """, job);

        assertEquals("Texas - Dallas; Georgia - Atlanta; Indiana - Indianapolis", job.getLocation());
    }

    @Test
    void translateUsesTheDetailForEveryNewPosting() throws Exception {
        WorkdayFetcher counting = new WorkdayFetcher(null, mapper, 60) {
            @Override
            String fetchDetail(WorkdayToken wd, String externalPath) {
                return FixtureSupport.fixture("workday-salesforce-detail.json");
            }
        };
        // The fixture's startDate is 2026-10-03, so judge it against a cutoff before that.
        FetchFilter filter = new FetchFilter(java.util.Set.of(), Instant.parse("2026-09-01T00:00:00Z"));

        List<Job> jobs = counting.translate(listRows(), company, filter);

        assertEquals(20, jobs.size());
        jobs.forEach(j -> assertNotNull(j.getDescription()));
    }

    @Test
    void anUnexpectedExternalPathIsDropped() throws Exception {
        List<String> called = new ArrayList<>();
        WorkdayFetcher counting = new WorkdayFetcher(null, mapper, 60) {
            @Override
            String fetchDetail(WorkdayToken wd, String externalPath) {
                called.add(externalPath);
                return "{\"jobPostingInfo\":{\"jobDescription\":\"x\"}}";
            }
        };
        List<JsonNode> rows = new ArrayList<>();
        for (String path : List.of("/job/../../admin", "//evil.com/job/x", "/job/a?b=c", "/other/x",
                "/job/a/{b}", "/job/Ok_JR1")) {
            rows.add(mapper.readTree("{\"title\":\"T\",\"externalPath\":\"" + path + "\"}"));
        }

        counting.translate(rows, company, FetchFilter.none());

        assertEquals(List.of("/job/Ok_JR1"), called);
    }

    @Test
    void missingJobPostingsIsAFailureNotAnEmptyBoard() {
        assertThrows(AtsFetchException.class,
                () -> fetcher.parsePreview("{\"errorCode\":\"S21\",\"httpStatus\":404}", WD));
    }

    @Test
    void anEmptyBoardPreviewsAsZero() {
        assertEquals(0, fetcher.parsePreview("{\"total\":0,\"jobPostings\":[]}", WD).jobCount());
    }

    @Test
    void aHostileTokenIsRejectedBeforeAnyRequest() {
        // webClientBuilder is null: reaching HTTP would be a NullPointerException.
        for (String token : List.of("evil.com#/wd1/x", "a.b/wd1/site", "acme/wd1/site/extra", "acme/xx1/site")) {
            Company hostile = FixtureSupport.company("Evil", AtsPlatform.WORKDAY, token);
            assertThrows(AtsFetchException.class, () -> fetcher.fetch(hostile, FetchFilter.none()), token);
            assertThrows(AtsFetchException.class, () -> fetcher.previewBoard(hostile), token);
        }
    }
}
