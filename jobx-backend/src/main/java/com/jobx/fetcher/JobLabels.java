package com.jobx.fetcher;

import java.util.Locale;
import java.util.Map;

/**
 * Cleans the display-only labels a fetcher copies onto a job: department, team
 * and employment type. None of them is scored, so the only job here is to make
 * them render consistently on a feed card.
 */
public final class JobLabels {

    /** Long enough for any real department name, short enough for a card. */
    static final int MAX_LENGTH = 120;

    /**
     * Every platform spells the common employment types differently: Ashby
     * {@code FullTime}, Lever {@code Full Time}, Workday {@code Full time},
     * Workable {@code Full-time} (and {@code full} in its v2 detail). Keys are
     * lower-cased with spaces, hyphens and underscores removed.
     */
    private static final Map<String, String> EMPLOYMENT_TYPES = Map.ofEntries(
            Map.entry("fulltime", "Full-time"),
            Map.entry("full", "Full-time"),
            Map.entry("parttime", "Part-time"),
            Map.entry("part", "Part-time"),
            Map.entry("intern", "Internship"),
            Map.entry("internship", "Internship"),
            Map.entry("contract", "Contract"),
            Map.entry("contractor", "Contract"),
            Map.entry("temporary", "Temporary"),
            Map.entry("temp", "Temporary"),
            Map.entry("freelance", "Freelance"));

    private JobLabels() {
    }

    /** Trimmed and capped; null when blank, so the card can leave it out. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH).strip() : trimmed;
    }

    /**
     * {@link #clean} plus one spelling per common employment type. A value
     * outside the known set is kept as the board wrote it.
     */
    public static String employmentType(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        String key = cleaned.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        return EMPLOYMENT_TYPES.getOrDefault(key, cleaned);
    }
}
