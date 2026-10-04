package com.jobx.fetcher.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.BoardTokens.WorkdayToken;
import com.jobx.fetcher.FetchFilter;
import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every detail call is an HTTP request, and a Workday board lists ~1,500
 * postings, so the filter has to run BEFORE it — on the list's relative
 * "Posted N Days Ago" text, since the real date is only on the detail.
 * Also covers the detail-failure rule (copied from Workable).
 */
class WorkdayFetchFilterTest {

    private static final String TOKEN = "salesforce/wd12/External_Career_Site";

    private final ObjectMapper mapper = new ObjectMapper();
    private Company company;
    private List<String> detailCalls;
    /** startDate the stub detail reports; today by default, so nothing is too old. */
    private LocalDate detailDate;
    private boolean detailFails;
    private WorkdayFetcher fetcher;

    @BeforeEach
    void setUp() {
        company = FixtureSupport.company("Salesforce", AtsPlatform.WORKDAY, TOKEN);
        detailCalls = new ArrayList<>();
        detailDate = LocalDate.now(ZoneOffset.UTC);
        detailFails = false;
        fetcher = new WorkdayFetcher(null, mapper, 60) {
            @Override
            String fetchDetail(WorkdayToken wd, String externalPath) {
                detailCalls.add(externalPath);
                if (detailFails && externalPath.endsWith("FAIL")) {
                    throw new RuntimeException("503 Service Unavailable");
                }
                return "{\"jobPostingInfo\":{\"jobDescription\":\"<p>Java</p>\",\"startDate\":\""
                        + detailDate + "\"}}";
            }
        };
    }

    private JsonNode row(String id, String postedOn) throws Exception {
        String posted = postedOn == null ? "" : ",\"postedOn\":\"" + postedOn + "\"";
        return mapper.readTree("{\"title\":\"Engineer\",\"externalPath\":\"/job/X/" + id + "\"" + posted + "}");
    }

    private static FetchFilter sixDayWindow(Set<String> known) {
        return new FetchFilter(known, Instant.now().minus(Duration.ofDays(6)));
    }

    private List<String> ids(List<Job> jobs) {
        return jobs.stream().map(Job::getExternalId).toList();
    }

    @Test
    void aKnownPostingCostsNoDetailCall() throws Exception {
        List<Job> jobs = fetcher.translate(
                List.of(row("TOMB", "Posted Today"), row("NEW", "Posted Today")),
                company, sixDayWindow(Set.of("/job/X/TOMB")));

        assertEquals(List.of("/job/X/NEW"), detailCalls);
        assertEquals(List.of("/job/X/NEW"), ids(jobs));
    }

    @Test
    void aPostingPastTheTtlCostsNoDetailCall() throws Exception {
        fetcher.translate(List.of(
                row("OLD", "Posted 30+ Days Ago"),
                row("WEEK", "Posted 7 Days Ago"),
                row("FRESH", "Posted 2 Days Ago")), company, sixDayWindow(Set.of()));

        assertEquals(List.of("/job/X/FRESH"), detailCalls);
    }

    /**
     * Found live: the posting on the last day of the window would be dropped by
     * its real (midnight UTC) date after the detail call — and, never stored,
     * cost that call again every cycle. So the list check uses the same clock.
     */
    @Test
    void theLastDayOfTheWindowCostsNoRepeatedDetailCall() throws Exception {
        fetcher.translate(List.of(row("EDGE", "Posted 6 Days Ago"), row("IN", "Posted 5 Days Ago")),
                company, sixDayWindow(Set.of()));

        assertEquals(List.of("/job/X/IN"), detailCalls);
    }

    @Test
    void anUnreadableOrMissingPostedOnIsFetched() throws Exception {
        fetcher.translate(List.of(row("FR", "Publié aujourd'hui"), row("NONE", null)),
                company, sixDayWindow(Set.of()));

        assertEquals(List.of("/job/X/FR", "/job/X/NONE"), detailCalls);
    }

    @Test
    void theDetailDateStillDecides() throws Exception {
        // The list text said fresh, but the real date is past the window: the
        // scheduler would drop it, so the fetcher doesn't hand it back.
        detailDate = LocalDate.now(ZoneOffset.UTC).minusDays(10);

        List<Job> jobs = fetcher.translate(List.of(row("LIAR", "Posted Today")), company, sixDayWindow(Set.of()));

        assertTrue(jobs.isEmpty());
    }

    @Test
    void aFailedDetailSkipsOnlyThatPosting() throws Exception {
        detailFails = true;

        List<Job> jobs = fetcher.translate(
                List.of(row("A-FAIL", "Posted Today"), row("B", "Posted Today")),
                company, FetchFilter.none());

        // Skipped, not emitted description-less, so the next cycle retries it.
        assertEquals(List.of("/job/X/B"), ids(jobs));
    }

    @Test
    void duplicateRowsCostOneDetailCall() throws Exception {
        fetcher.translate(List.of(row("DUP", "Posted Today"), row("DUP", "Posted Today")),
                company, FetchFilter.none());

        assertEquals(List.of("/job/X/DUP"), detailCalls);
    }
}
