package com.jobx.fetcher.greenhouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Board-preview parsing for Greenhouse — the cheap one-call description of a
 * board that the add-company confirmation card and validateBoard both read.
 *
 * The payloads here are trimmed to the fields preview actually looks at, in the
 * shapes verified in Phase 0 against Razorpay and PhonePe. Full-response mapping
 * is covered by the fetcher's live verification rather than a fixture, since
 * Greenhouse boards are megabytes of JSON.
 */
class GreenhouseFetcherTest {

    private GreenhouseFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new GreenhouseFetcher(null, new ObjectMapper());
    }

    @Test
    void countsJobsAndSamplesRealTitles() {
        String body = """
                {"jobs":[
                  {"id":1,"internal_job_id":11,"title":"Senior Backend Engineer"},
                  {"id":2,"internal_job_id":12,"title":"Product Designer"},
                  {"id":3,"internal_job_id":13,"title":"Data Analyst"},
                  {"id":4,"internal_job_id":14,"title":"Engineering Manager"}
                ],"meta":{"total":4}}
                """;

        BoardPreview preview = fetcher.parsePreview(body, "razorpaysoftwareprivatelimited");

        assertEquals(4, preview.jobCount());
        assertEquals(BoardPreview.SAMPLE_SIZE, preview.sampleTitles().size());
        assertEquals("Senior Backend Engineer", preview.sampleTitles().get(0));
        // Greenhouse's jobs endpoint carries no company name.
        assertNull(preview.displayName());
    }

    /**
     * Prospect posts ("register your interest" pages, internal_job_id null) are
     * not openings. fetch() drops them, so preview must too — otherwise the
     * count a user confirms against is not the feed they end up with.
     */
    @Test
    void excludesProspectPostsExactlyAsFetchDoes() {
        String body = """
                {"jobs":[
                  {"id":1,"internal_job_id":null,"title":"Join our talent community"},
                  {"id":2,"internal_job_id":12,"title":"Backend Engineer"}
                ]}
                """;

        BoardPreview preview = fetcher.parsePreview(body, "groww");

        assertEquals(1, preview.jobCount());
        assertEquals("Backend Engineer", preview.sampleTitles().get(0));
    }

    /** A real board with nothing open parses fine; validateBoard is what rejects it. */
    @Test
    void anEmptyBoardIsParseableButFailsValidation() {
        BoardPreview preview = fetcher.parsePreview("{\"jobs\":[]}", "quiet");
        assertEquals(0, preview.jobCount());
        assertTrue(preview.sampleTitles().isEmpty());

        GreenhouseFetcher stub = new GreenhouseFetcher(null, new ObjectMapper()) {
            @Override
            public BoardPreview previewBoard(Company company) {
                return parsePreview("{\"jobs\":[]}", company.getBoardToken());
            }
        };
        assertThrows(AtsFetchException.class, () -> stub.validateBoard(
                FixtureSupport.company("Quiet", AtsPlatform.GREENHOUSE, "quiet")));
    }

    @Test
    void rejectsAPayloadThatIsNotABoard() {
        assertThrows(AtsFetchException.class, () -> fetcher.parsePreview("{\"error\":\"x\"}", "bogus"));
        assertThrows(AtsFetchException.class, () -> fetcher.parsePreview("<html>404</html>", "bogus"));
    }
}
