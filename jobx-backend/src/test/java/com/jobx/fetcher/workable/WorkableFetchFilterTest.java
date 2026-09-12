package com.jobx.fetcher.workable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.FetchFilter;
import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug #2: every detail call is an HTTP request, so the filter has to be
 * applied BEFORE it. Pre-fix the guard knew only the jobs table, and each
 * tombstoned posting still listed on the board cost a detail call per cycle.
 */
class WorkableFetchFilterTest {

    private Company company;
    /** Shortcodes whose detail was requested, in order. */
    private List<String> detailCalls;
    private WorkableFetcher fetcher;

    @BeforeEach
    void setUp() {
        company = FixtureSupport.company("Apna", AtsPlatform.WORKABLE, "apna");
        detailCalls = new ArrayList<>();
        fetcher = new WorkableFetcher(null, new ObjectMapper()) {
            @Override
            String fetchDetail(String token, String shortcode) {
                detailCalls.add(shortcode);
                return FixtureSupport.fixture("workable-v2-job.json");
            }
        };
    }

    private static String board(String... rows) {
        return "{\"name\":\"Apna\",\"jobs\":[" + String.join(",", rows) + "]}";
    }

    private static String row(String shortcode, String publishedOn) {
        String date = publishedOn == null ? "" : ",\"published_on\":\"" + publishedOn + "\"";
        return "{\"shortcode\":\"" + shortcode + "\",\"title\":\"Engineer\","
                + "\"url\":\"https://apply.workable.com/apna/j/" + shortcode + "/\"" + date + "}";
    }

    private List<String> ids(List<Job> jobs) {
        return jobs.stream().map(Job::getExternalId).toList();
    }

    @Test
    void aTombstonedPostingCostsNoDetailCall() throws Exception {
        FetchFilter filter = new FetchFilter(Set.of("TOMB"), Instant.MIN);

        List<Job> jobs = fetcher.parseList(
                board(row("TOMB", "2026-06-27"), row("NEW", "2026-06-27")), company, filter, true);

        assertEquals(List.of("NEW"), detailCalls);
        assertEquals(List.of("NEW"), ids(jobs));
    }

    @Test
    void aPostingPastTheTtlCostsNoDetailCall() throws Exception {
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-06-20T00:00:00Z"));

        List<Job> jobs = fetcher.parseList(
                board(row("OLD", "2026-06-01"), row("FRESH", "2026-06-27")), company, filter, true);

        assertEquals(List.of("FRESH"), detailCalls);
        assertEquals(List.of("FRESH"), ids(jobs));
    }

    @Test
    void aPostingDatedOnTheCutoffDayIsStillFetched() throws Exception {
        // published_on has no time. Read as midnight, a posting from the cutoff
        // day would look older than the cutoff and be dropped while still
        // inside the window — so the whole day counts.
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-06-20T15:00:00Z"));

        fetcher.parseList(board(row("EDGE", "2026-06-20")), company, filter, true);

        assertEquals(List.of("EDGE"), detailCalls);
    }

    @Test
    void theDayBeforeTheCutoffDayIsTooOld() throws Exception {
        // Even its last instant (2026-06-19T23:59:59Z) predates the cutoff.
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-06-20T15:00:00Z"));

        fetcher.parseList(board(row("STALE", "2026-06-19")), company, filter, true);

        assertTrue(detailCalls.isEmpty());
    }

    @Test
    void aPostingWithNoDateIsFetched() throws Exception {
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-06-20T00:00:00Z"));

        fetcher.parseList(board(row("UNDATED", null)), company, filter, true);

        assertEquals(List.of("UNDATED"), detailCalls);
    }
}
