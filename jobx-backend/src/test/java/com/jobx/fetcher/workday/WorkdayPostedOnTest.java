package com.jobx.fetcher.workday;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phrases seen live on Salesforce's board, 2026-10-04, read onto the same
 * clock as the stored startDate: that day's midnight UTC.
 */
class WorkdayPostedOnTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "Posted Today, 2026-10-04T00:00:00Z",
            "Posted Yesterday, 2026-10-03T00:00:00Z",
            "Posted 2 Days Ago, 2026-10-02T00:00:00Z",
            "Posted 17 Days Ago, 2026-09-17T00:00:00Z",
            "Posted 30+ Days Ago, 2026-09-04T00:00:00Z",
            "'  posted today  ', 2026-10-04T00:00:00Z",
            "POSTED 1 DAY AGO, 2026-10-03T00:00:00Z",
    })
    void readsKnownPhrases(String text, String expected) {
        assertEquals(Optional.of(Instant.parse(expected)), WorkdayPostedOn.approxPostedAt(text, NOW));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Publié aujourd'hui", "Posted", "Posted Last Week", "Today"})
    void unknownTextIsNoDate(String text) {
        assertEquals(Optional.empty(), WorkdayPostedOn.approxPostedAt(text, NOW));
    }

    @Test
    void justAfterMidnightTodayIsStillToday() {
        assertEquals(Optional.of(Instant.parse("2026-10-04T00:00:00Z")),
                WorkdayPostedOn.approxPostedAt("Posted Today", Instant.parse("2026-10-04T00:00:01Z")));
    }
}
