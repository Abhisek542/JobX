package com.jobx.fetcher.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.BoardTokens.WorkdayToken;
import com.jobx.fetcher.FetchFilter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paging rules, verified against the live board's behaviour: total is only
 * set on page 0, pages are 20, and the list is newest-first so paging stops
 * once it has run past the TTL.
 */
class WorkdayPagingTest {

    private static final WorkdayToken WD = WorkdayToken.parse("salesforce/wd12/External_Career_Site");
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final FetchFilter SIX_DAYS = new FetchFilter(Set.of(), NOW.minus(Duration.ofDays(6)));

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Integer> offsets = new ArrayList<>();

    /** A fetcher whose page N is built by {@code postedOnForPage}, on a board of {@code total}. */
    private WorkdayFetcher board(int total, int maxPages, IntFunction<String> postedOnForPage) {
        return new WorkdayFetcher(null, mapper, maxPages) {
            @Override
            String fetchPage(WorkdayToken wd, int offset, int limit) {
                offsets.add(offset);
                int rows = Math.max(0, Math.min(limit, total - offset));
                StringBuilder sb = new StringBuilder("{\"total\":" + (offset == 0 ? total : 0) + ",\"jobPostings\":[");
                for (int i = 0; i < rows; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"title\":\"T\",\"externalPath\":\"/job/X/").append(offset + i)
                            .append("\",\"postedOn\":\"").append(postedOnForPage.apply(offset / limit)).append("\"}");
                }
                return sb.append("]}").toString();
            }
        };
    }

    @Test
    void walksTheWholeBoardUsingTotalFromTheFirstPage() {
        List<JsonNode> rows = board(45, 60, p -> "Posted Today").fetchAllPages(WD, SIX_DAYS, NOW);

        assertEquals(45, rows.size());
        assertEquals(List.of(0, 20, 40), offsets);
    }

    @Test
    void stopsAfterTwoPagesEntirelyPastTheTtl() {
        // Pages 0-1 fresh, 2+ all a month old: reads pages 2 and 3, then stops.
        List<JsonNode> rows = board(1500, 60, p -> p < 2 ? "Posted 2 Days Ago" : "Posted 30+ Days Ago")
                .fetchAllPages(WD, SIX_DAYS, NOW);

        assertEquals(List.of(0, 20, 40, 60), offsets);
        assertEquals(80, rows.size());
    }

    @Test
    void oneStalePageBetweenFreshOnesDoesNotStopPaging() {
        board(100, 60, p -> p == 1 ? "Posted 30+ Days Ago" : "Posted Today").fetchAllPages(WD, SIX_DAYS, NOW);

        assertEquals(List.of(0, 20, 40, 60, 80), offsets);
    }

    @Test
    void unreadableDatesNeverCountAsStale() {
        board(100, 60, p -> "Publié il y a 30 jours").fetchAllPages(WD, SIX_DAYS, NOW);

        assertEquals(5, offsets.size());
    }

    @Test
    void stopsAtThePageCap() {
        List<JsonNode> rows = board(1500, 3, p -> "Posted Today").fetchAllPages(WD, SIX_DAYS, NOW);

        assertEquals(List.of(0, 20, 40), offsets);
        assertEquals(60, rows.size());
    }

    @Test
    void anEmptyBoardIsOneRequest() {
        assertTrue(board(0, 60, p -> "Posted Today").fetchAllPages(WD, SIX_DAYS, NOW).isEmpty());
        assertEquals(List.of(0), offsets);
    }

    @Test
    void anErrorBodyIsAFailure() {
        WorkdayFetcher dead = new WorkdayFetcher(null, mapper, 60) {
            @Override
            String fetchPage(WorkdayToken wd, int offset, int limit) {
                return "{\"errorCode\":\"S21\",\"httpStatus\":404,\"message\":\"not found\"}";
            }
        };

        assertThrows(AtsFetchException.class, () -> dead.fetchAllPages(WD, SIX_DAYS, NOW));
    }
}
