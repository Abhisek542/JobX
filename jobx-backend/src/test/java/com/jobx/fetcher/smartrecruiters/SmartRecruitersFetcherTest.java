package com.jobx.fetcher.smartrecruiters;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures are real captured responses from PhonePe's live SmartRecruiters
 * board (PHONEPELIMITED, 2026-08-29) — the board they moved to after their
 * Greenhouse token went dead.
 */
class SmartRecruitersFetcherTest {

    private SmartRecruitersFetcher fetcher;
    private Company company;

    @BeforeEach
    void setUp() {
        // webClientBuilder is never touched by the parse
        // seams, same convention as the other fetcher tests.
        fetcher = new SmartRecruitersFetcher(null, new ObjectMapper());
        company = FixtureSupport.company("PhonePe", AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED");
    }

    private List<JsonNode> page() throws Exception {
        return fetcher.parsePage(FixtureSupport.fixture("smartrecruiters-phonepe.json"), "PHONEPELIMITED");
    }

    @Test
    void readsEveryPostingOnThePage() throws Exception {
        assertEquals(6, page().size());
    }

    @Test
    void mapsTheKnownFirstPosting() throws Exception {
        JsonNode first = page().get(0);
        Job job = fetcher.mapListItem(first, company, first.path("id").asText());

        assertEquals("1000000000000954", job.getExternalId());
        assertEquals("Associate Director Product Design - UX, Product Design", job.getTitle());
        assertEquals("Bengaluru, Karnataka, India", job.getLocation());
        assertEquals(Instant.parse("2026-08-28T11:20:45.290Z"), job.getPlatformPostedAt());
        assertEquals(AtsPlatform.SMARTRECRUITERS, job.getAtsPlatform());
        assertSame(company, job.getCompany());
        assertNotNull(job.getRawJson());
    }

    @Test
    void everyPostingHasTheFieldsThePipelineRequires() throws Exception {
        for (JsonNode node : page()) {
            Job job = fetcher.mapListItem(node, company, node.path("id").asText());
            assertFalse(job.getExternalId().isBlank());
            assertFalse(job.getTitle().isBlank());
            assertNotNull(job.getPlatformPostedAt(), "releasedDate drives the retention TTL");
        }
    }

    @Test
    void detailSuppliesTheApplyUrlAndAssembledDescription() throws Exception {
        JsonNode first = page().get(0);
        Job job = fetcher.mapListItem(first, company, first.path("id").asText());

        // The list carries neither, which is the whole reason for the second call.
        assertNull(job.getApplyUrl());
        assertNull(job.getDescription());

        fetcher.applyDetail(FixtureSupport.fixture("smartrecruiters-phonepe-detail.json"), job);

        // postingUrl is preferred over applyUrl's ?oga=true tracking variant.
        assertEquals("https://jobs.smartrecruiters.com/PHONEPELIMITED/"
                        + "1000000000000954-associate-director-product-design-ux-product-design",
                job.getApplyUrl());
        assertFalse(job.getApplyUrl().contains("oga=true"));

        // jobAd.sections are HTML blobs, concatenated and stripped.
        String description = job.getDescription();
        assertNotNull(description);
        assertFalse(description.contains("<"), "HTML should be stripped");
        assertTrue(description.contains("Why PhonePe"), "companyDescription section");
        assertTrue(description.length() > 2000, "all sections concatenated, not just one");
    }

    @Test
    void aMissingContentArrayIsAFailureNotAnEmptyBoard() {
        // Distinguishes "not a board response" from a board with nothing open.
        assertThrows(AtsFetchException.class,
                () -> fetcher.parsePage("{\"error\":\"nope\"}", "PHONEPELIMITED"));
    }

    @Test
    void anEmptyContentArrayParsesAsAnEmptyPage() throws Exception {
        // This is exactly what a BOGUS company id returns — 200 with an empty
        // list, never a 404. parsePage cannot tell the difference and must not
        // try; that judgement belongs to validateBoard, at add time only.
        assertTrue(fetcher.parsePage(
                "{\"offset\":0,\"limit\":100,\"totalFound\":0,\"content\":[]}",
                "zzz-not-real").isEmpty());
    }

    @Test
    void locationFallsBackToCityWhenFullLocationIsAbsent() {
        JsonNode node = jsonOf("""
                {"id":"1","name":"Engineer","location":{"city":"Pune"}}""");
        Job job = fetcher.mapListItem(node, company, "1");
        assertEquals("Pune", job.getLocation());
    }

    @Test
    void anUnparseableReleasedDateLeavesThePostingUsableWithNoDate() {
        // Non-fatal, same as every other fetcher: a bad date must not cost us
        // the posting. The TTL then falls back to firstSeenAt.
        JsonNode node = jsonOf("""
                {"id":"1","name":"Engineer","releasedDate":"not-a-date"}""");
        Job job = fetcher.mapListItem(node, company, "1");
        assertEquals("Engineer", job.getTitle());
        assertNull(job.getPlatformPostedAt());
    }

    /**
     * Bug #2: the detail call is an HTTP request per posting, so the filter must
     * run before it. Pre-fix the guard knew only the jobs table, and on a
     * swept Bosch board that was ~4,774 wasted detail calls every cycle.
     */
    private SmartRecruitersFetcher countingDetailCalls(List<String> detailCalls) {
        return new SmartRecruitersFetcher(null, new ObjectMapper()) {
            @Override
            String fetchDetail(String token, String externalId) {
                detailCalls.add(externalId);
                return FixtureSupport.fixture("smartrecruiters-phonepe-detail.json");
            }
        };
    }

    private JsonNode posting(String id, String releasedDate) {
        String date = releasedDate == null ? "" : ",\"releasedDate\":\"" + releasedDate + "\"";
        return jsonOf("{\"id\":\"" + id + "\",\"name\":\"Engineer\"" + date + "}");
    }

    @Test
    void aTombstonedPostingCostsNoDetailCall() {
        List<String> detailCalls = new java.util.ArrayList<>();
        FetchFilter filter = new FetchFilter(Set.of("tomb"), Instant.MIN);

        List<Job> jobs = countingDetailCalls(detailCalls).translate(
                List.of(posting("tomb", "2026-08-28T11:20:45.290Z"), posting("new", "2026-08-28T11:20:45.290Z")),
                company, filter);

        assertEquals(List.of("new"), detailCalls);
        assertEquals(List.of("new"), jobs.stream().map(Job::getExternalId).toList());
    }

    @Test
    void aPostingPastTheTtlCostsNoDetailCall() {
        List<String> detailCalls = new java.util.ArrayList<>();
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-08-22T00:00:00Z"));

        countingDetailCalls(detailCalls).translate(
                List.of(posting("old", "2026-08-21T23:59:59Z"), posting("fresh", "2026-08-22T00:00:01Z")),
                company, filter);

        assertEquals(List.of("fresh"), detailCalls);
    }

    @Test
    void aPostingWithNoDateIsStillFetched() {
        List<String> detailCalls = new java.util.ArrayList<>();
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-08-22T00:00:00Z"));

        countingDetailCalls(detailCalls).translate(List.of(posting("undated", null)), company, filter);

        assertEquals(List.of("undated"), detailCalls);
    }

    private JsonNode jsonOf(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * jobCount is the API's own totalFound, not the rows returned — the preview
     * asks for a sample page, so counting rows would report 3 for Bosch's 4,774.
     * SmartRecruiters is also one of only two platforms that names the company.
     */
    @Test
    void previewReportsTotalFoundAndNamesTheCompany() {
        BoardPreview preview = fetcher.parsePreview(
                FixtureSupport.fixture("smartrecruiters-phonepe.json"), "PHONEPELIMITED");

        assertTrue(preview.jobCount() > 0);
        assertEquals("PHONEPE LIMITED", preview.displayName());
        assertFalse(preview.sampleTitles().isEmpty());
        assertTrue(preview.sampleTitles().size() <= BoardPreview.SAMPLE_SIZE);
        preview.sampleTitles().forEach(title -> assertFalse(title.isBlank()));
    }

    /**
     * The original reason this platform needed a validation hook: a bogus
     * company id returns 200 with an empty list rather than 404, so a typo would
     * otherwise look like a healthy board that simply never posts a job.
     */
    @Test
    void aBogusTokenPreviewsAsEmptyAndFailsValidation() {
        String emptyBoard = "{\"offset\":0,\"limit\":3,\"totalFound\":0,\"content\":[]}";
        BoardPreview preview = fetcher.parsePreview(emptyBoard, "PHONEPELIMTED");

        assertEquals(0, preview.jobCount());
        assertNull(preview.displayName());

        SmartRecruitersFetcher stub = new SmartRecruitersFetcher(null, new ObjectMapper()) {
            @Override
            public BoardPreview previewBoard(Company company) {
                return parsePreview(emptyBoard, company.getBoardToken());
            }
        };
        assertThrows(AtsFetchException.class, () -> stub.validateBoard(
                FixtureSupport.company("Typo", AtsPlatform.SMARTRECRUITERS, "PHONEPELIMTED")));
    }

    @Test
    void previewRejectsAPayloadThatIsNotABoard() {
        assertThrows(AtsFetchException.class, () -> fetcher.parsePreview("{\"nope\":1}", "bogus"));
    }
}
