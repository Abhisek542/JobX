package com.jobx.fetcher.rippling;

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
 * Every detail call is an HTTP request, so known postings must be filtered out
 * before it. The age check has to come after it, because Rippling's list has
 * no date.
 */
class RipplingFetchFilterTest {

    private Company company;
    /** Uuids whose detail was requested, in order. */
    private List<String> detailCalls;
    /** Uuids whose detail call fails. */
    private Set<String> failing;
    private RipplingFetcher fetcher;

    @BeforeEach
    void setUp() {
        company = FixtureSupport.company("Rippling", AtsPlatform.RIPPLING, "rippling");
        detailCalls = new ArrayList<>();
        failing = Set.of();
        fetcher = new RipplingFetcher(null, new ObjectMapper()) {
            @Override
            String fetchDetail(String slug, String uuid) {
                detailCalls.add(uuid);
                if (failing.contains(uuid)) {
                    throw new RuntimeException("503 Service Unavailable");
                }
                // createdOn 2026-08-13T15:54:48.317Z
                return FixtureSupport.fixture("rippling-rippling-v2-detail.json");
            }
        };
    }

    private static String board(String... uuids) {
        List<String> rows = new ArrayList<>();
        for (String uuid : uuids) {
            rows.add("{\"uuid\":\"" + uuid + "\",\"name\":\"Engineer\","
                    + "\"url\":\"https://ats.rippling.com/rippling/jobs/" + uuid + "\","
                    + "\"workLocation\":{\"label\":\"Remote\",\"id\":\"Remote\"}}");
        }
        return "[" + String.join(",", rows) + "]";
    }

    private static List<String> ids(List<Job> jobs) {
        return jobs.stream().map(Job::getExternalId).toList();
    }

    @Test
    void aKnownPostingCostsNoDetailCall() throws Exception {
        FetchFilter filter = new FetchFilter(Set.of("KNOWN"), Instant.MIN);

        List<Job> jobs = fetcher.parseList(board("KNOWN", "NEW"), company, filter, true);

        assertEquals(List.of("NEW"), detailCalls);
        assertEquals(List.of("NEW"), ids(jobs));
    }

    @Test
    void aRepeatedUuidCostsOneDetailCall() throws Exception {
        fetcher.parseList(board("SAME", "SAME", "SAME"), company, FetchFilter.none(), true);

        assertEquals(List.of("SAME"), detailCalls);
    }

    @Test
    void aPostingPastTheTtlIsNotStored() throws Exception {
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-09-01T00:00:00Z"));

        List<Job> jobs = fetcher.parseList(board("OLD"), company, filter, true);

        assertEquals(List.of("OLD"), detailCalls, "the list has no date, so the call can't be avoided");
        assertTrue(jobs.isEmpty());
    }

    @Test
    void aPostingInsideTheTtlIsStored() throws Exception {
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-08-10T00:00:00Z"));

        assertEquals(List.of("FRESH"), ids(fetcher.parseList(board("FRESH"), company, filter, true)));
    }

    @Test
    void aFailedDetailCallSkipsOnlyThatJob() throws Exception {
        failing = Set.of("BROKEN");

        List<Job> jobs = fetcher.parseList(board("BROKEN", "FINE"), company, FetchFilter.none(), true);

        // Nothing is stored for BROKEN, so the next cycle retries it instead of
        // skipping it as "already known" with no description.
        assertEquals(List.of("FINE"), ids(jobs));
        assertNotNull(jobs.get(0).getDescription());
    }
}
