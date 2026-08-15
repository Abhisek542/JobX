package com.jobx.fetcher.workable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.FixtureSupport;
import com.jobx.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Covers the two Workable failure paths that used to fail quietly:
 *  - a detail call that fails must NOT persist a description-less job, because
 *    the N+1 guard would then skip it forever and it could never be repaired
 *  - a malformed list payload must surface as a failure, not an empty board
 */
class WorkableDetailFailureTest {

    private WatchedCompany company;

    @BeforeEach
    void setUp() {
        company = FixtureSupport.company("Apna", AtsPlatform.WORKABLE, "apna");
    }

    /** A fetcher whose detail call always blows up, with no real HTTP involved. */
    private WorkableFetcher fetcherWithFailingDetail() {
        WebClient.Builder builder = mock(WebClient.Builder.class, RETURNS_DEEP_STUBS);
        when(builder.build().get().uri(anyString()).retrieve().bodyToMono(String.class).block())
                .thenThrow(new RuntimeException("503 Service Unavailable"));

        JobRepository jobRepository = mock(JobRepository.class);
        when(jobRepository.existsByCompanyAndExternalId(any(), anyString())).thenReturn(false);

        return new WorkableFetcher(builder, new ObjectMapper(), jobRepository);
    }

    @Test
    void jobIsSkippedRatherThanPersistedWithoutADescription() throws Exception {
        List<Job> jobs = fetcherWithFailingDetail()
                .parseList(FixtureSupport.fixture("workable-apna.json"), company, true);

        // Nothing is emitted, so nothing is written, so the next cycle retries
        // these shortcodes instead of skipping them as "already known".
        assertTrue(jobs.isEmpty(),
                "a job whose detail call failed must not be persisted description-less");
    }

    @Test
    void detailFailureDoesNotFailTheWholeBoard() throws Exception {
        // The board itself was fetched fine — per-job detail trouble is not a
        // board outage, so parseList returns normally rather than throwing.
        assertDoesNotThrow(() -> fetcherWithFailingDetail()
                .parseList(FixtureSupport.fixture("workable-apna.json"), company, true));
    }

    @Test
    void missingJobsArrayIsAFailureNotAnEmptyBoard() {
        WorkableFetcher fetcher = new WorkableFetcher(null, new ObjectMapper(), null);

        assertThrows(AtsFetchException.class,
                () -> fetcher.parseList("{\"error\":\"account not found\"}", company, false));
    }

    @Test
    void emptyBoardIsASuccessfulEmptyResult() throws Exception {
        WorkableFetcher fetcher = new WorkableFetcher(null, new ObjectMapper(), null);

        assertTrue(fetcher.parseList("{\"jobs\":[]}", company, false).isEmpty());
    }
}
