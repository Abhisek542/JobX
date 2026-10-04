package com.jobx.fetcher.workday;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workday's list rows carry no date, only relative text: "Posted Today",
 * "Posted Yesterday", "Posted 3 Days Ago", "Posted 30+ Days Ago" (verified live
 * on Salesforce, 2026-10-04). The real date ({@code startDate}) is only on the
 * detail response, which costs a request per posting. This turns the text into
 * something {@code FetchFilter.isTooOld} can judge BEFORE that request.
 *
 * The answer is deliberately on the SAME clock as the date we store: the
 * detail's date-only {@code startDate} read as midnight UTC, which is what the
 * scheduler and the retention sweep both judge. Reading "Posted 6 Days Ago" more
 * generously (as now − 6 days, say) looks kinder but isn't: verified live, such
 * a posting passes this check, costs a detail call, is then dropped by its real
 * date — and because nothing was stored, costs that same call again every cycle
 * until it ages out. The cost of matching the clock is that a posting with less
 * than a day left in the window can be skipped; the sweep would delete it by
 * tomorrow anyway.
 */
final class WorkdayPostedOn {

    private static final Pattern PHRASE = Pattern.compile(
            "posted\\s+(?:(today)|(yesterday)|(\\d{1,4})\\+?\\s+days?\\s+ago)");

    private WorkdayPostedOn() {
    }

    /**
     * @return the posting's approximate date at midnight UTC, or empty when the
     *         text is missing or in a form we don't know (another locale, say).
     *         Empty is never "too old", the same rule as a posting with no date.
     */
    static Optional<Instant> approxPostedAt(String text, Instant now) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = PHRASE.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return Optional.empty();
        }
        long daysAgo = m.group(1) != null ? 0 : m.group(2) != null ? 1 : Long.parseLong(m.group(3));
        LocalDate day = LocalDate.ofInstant(now.minus(Duration.ofDays(daysAgo)), ZoneOffset.UTC);
        return Optional.of(day.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
