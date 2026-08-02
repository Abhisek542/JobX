package com.jobx.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalization for user-entered word lists (FilterProfile keywords and
 * exclude words): trim entries, drop blanks, dedupe case-insensitively while
 * preserving first-seen order and original casing.
 */
public final class TextLists {

    private TextLists() {
    }

    public static List<String> normalize(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
