package com.jobx.fetcher.bamboohr;

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
 * Known postings are filtered before the detail call. The age gate runs after
 * it (the list has no date) and judges the date-only datePosted by the end of
 * its day. A failed detail call skips only that job.
 */
class BambooHrFetchFilterTest {

    private Company company;
    private List<String> detailCalls;
    private Set<String> failing;
    private BambooHrFetcher fetcher;

    @BeforeEach
    void setUp() {
        company = FixtureSupport.company("Off Duty Management", AtsPlatform.BAMBOOHR, "offdutymanagement");
        detailCalls = new ArrayList<>();
        failing = Set.of();
        fetcher = new BambooHrFetcher(null, new ObjectMapper()) {
            @Override
            String fetchDetail(String sub, String id) {
                detailCalls.add(id);
                if (failing.contains(id)) {
                    throw new RuntimeException("503 Service Unavailable");
                }
                // datePosted 2026-07-16
                return FixtureSupport.fixture("bamboohr-offdutymanagement-detail.json");
            }
        };
    }

    private static String board(String... ids) {
        List<String> rows = new ArrayList<>();
        for (String id : ids) {
            rows.add("{\"id\":\"" + id + "\",\"jobOpeningName\":\"Engineer\"}");
        }
        return "{\"meta\":{\"totalCount\":" + ids.length + "},\"result\":[" + String.join(",", rows) + "]}";
    }

    private static List<String> ids(List<Job> jobs) {
        return jobs.stream().map(Job::getExternalId).toList();
    }

    @Test
    void aKnownPostingCostsNoDetailCall() throws Exception {
        List<Job> jobs = fetcher.parseList(board("1", "2"), company,
                new FetchFilter(Set.of("1"), Instant.MIN), true);

        assertEquals(List.of("2"), detailCalls);
        assertEquals(List.of("2"), ids(jobs));
    }

    @Test
    void aPostingDatedOnTheCutoffDayIsStillStored() throws Exception {
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-07-16T15:00:00Z"));

        assertEquals(List.of("1"), ids(fetcher.parseList(board("1"), company, filter, true)));
    }

    @Test
    void thePostingIsDroppedOnceItsWholeDayIsPastTheCutoff() throws Exception {
        FetchFilter filter = new FetchFilter(Set.of(), Instant.parse("2026-07-17T00:00:01Z"));

        assertTrue(fetcher.parseList(board("1"), company, filter, true).isEmpty());
        assertEquals(List.of("1"), detailCalls);
    }

    @Test
    void aFailedDetailCallSkipsOnlyThatJob() throws Exception {
        failing = Set.of("1");

        List<Job> jobs = fetcher.parseList(board("1", "2"), company, FetchFilter.none(), true);

        assertEquals(List.of("2"), ids(jobs));
        assertNotNull(jobs.get(0).getDescription());
    }
}
