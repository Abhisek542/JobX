package com.jobx.resolve;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns what the user typed into board tokens worth trying.
 *
 * This is guesswork and is treated as such: nothing here ever becomes a watched
 * company on its own. A candidate only survives if
 * {@link com.jobx.fetcher.AtsFetcher#previewBoard} finds live roles on it, and
 * even then a human confirms it against real job titles. What makes that safe is
 * the confirmation step, not the cleverness of the guess.
 *
 * Measured behaviour on the boards in {@code docs/ats-test-data.md}:
 *  - the DOMAIN LABEL is by far the better guess. atlan.com to "atlan" (Ashby),
 *    groww.in to "groww" (Greenhouse), fampay.in to "fampay" (Lever) all hit.
 *  - CASE VARIANTS ARE NOT OPTIONAL. Lever and Ashby 404 on the wrong case, and
 *    two of the verified test boards are capitalised ("Sprinto", "Aspora"). A
 *    lowercase-only candidate list misses them entirely.
 *  - some companies simply cannot be guessed. Razorpay's token is
 *    "razorpaysoftwareprivatelimited"; no derivation from "Razorpay" reaches it,
 *    which is why sniffing the careers page exists alongside this.
 */
public final class SlugCandidates {

    private SlugCandidates() {
    }

    /** Corporate suffixes that are in a legal name but never in a board token. */
    private static final List<String> SUFFIXES = List.of(
            "privatelimited", "pvtltd", "pvt", "private", "limited", "ltd", "llp", "llc",
            "inc", "corp", "corporation", "company", "technologies", "technology",
            "software", "solutions", "systems", "labs", "global", "india");

    /** Subdomains to strip before taking the domain label. */
    private static final Set<String> HOST_PREFIXES = Set.of(
            "www", "careers", "career", "jobs", "job", "apply", "hiring", "work", "join");

    /**
     * Ordered, deduplicated tokens to probe for a query that may be a company
     * name, a bare domain, or any URL.
     *
     * @param limit hard cap, because every candidate costs one HTTP request per
     *              platform being probed.
     */
    public static List<String> from(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String trimmed = query.trim();
        Set<String> bases = new LinkedHashSet<>();

        // A domain label beats a company name whenever there is one, so it goes first.
        String host = hostOf(trimmed);
        if (host != null) {
            String label = domainLabel(host);
            if (label != null) {
                bases.add(label);
            }
        } else {
            String compact = compact(trimmed);
            if (!compact.isEmpty()) {
                bases.add(compact);
                String stripped = stripSuffixes(compact);
                if (!stripped.isEmpty()) {
                    bases.add(stripped);
                }
            }
        }

        List<String> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Whatever the user typed, exactly as typed, FIRST — but only when the
        // query is a bare word rather than a host, since "atlan.com" is a
        // website and not a board token.
        //
        // It goes first because it is the highest-information candidate there
        // is: the derived variants are lowercase and Capitalised, neither of
        // which can produce a camelCase or SHOUTING token, and both exist
        // (SmartRecruiters' verified boards are "BoschGroup" and
        // "PHONEPELIMITED"). Appending it instead would let the candidate cap
        // discard the one spelling that was certain to be right.
        if (host == null && trimmed.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            seen.add(trimmed);
            candidates.add(trimmed);
        }

        for (String base : bases) {
            for (String variant : caseVariants(base)) {
                if (seen.add(variant) && candidates.size() < limit) {
                    candidates.add(variant);
                }
            }
        }

        return List.copyOf(candidates);
    }

    /**
     * Lowercase and Capitalised, in that order. Lowercase is much the more
     * common form, so it is tried first; Capitalised is what rescues Sprinto and
     * Aspora, whose Lever and Ashby boards 404 in lowercase.
     */
    private static List<String> caseVariants(String base) {
        String lower = base.toLowerCase(Locale.ROOT);
        String capitalised = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        return lower.equals(capitalised) ? List.of(lower) : List.of(lower, capitalised);
    }

    /** The host of a URL-ish string, or null when the query is just a name. */
    static String hostOf(String query) {
        String candidate = query;
        int scheme = candidate.indexOf("://");
        if (scheme >= 0) {
            candidate = candidate.substring(scheme + 3);
        }
        int slash = candidate.indexOf('/');
        if (slash >= 0) {
            candidate = candidate.substring(0, slash);
        }
        int at = candidate.indexOf('@');
        if (at >= 0) {
            candidate = candidate.substring(at + 1);
        }
        int colon = candidate.indexOf(':');
        if (colon >= 0) {
            candidate = candidate.substring(0, colon);
        }
        candidate = candidate.trim().toLowerCase(Locale.ROOT);

        // A name with a space in it is a name, even if it also contains a dot.
        if (candidate.isEmpty() || candidate.contains(" ") || !candidate.contains(".")) {
            return null;
        }
        return candidate.matches("[a-z0-9.-]+") ? candidate : null;
    }

    /**
     * "careers.atlan.com" to "atlan": drop a careers-ish subdomain, then take the
     * label before the public suffix. Handles the two-part suffixes that matter
     * for an India-first product ("co.in", "co.uk") without carrying a full
     * public-suffix list for a guess that a human confirms anyway.
     */
    static String domainLabel(String host) {
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return null;
        }

        int start = 0;
        while (start < parts.length - 2 && HOST_PREFIXES.contains(parts[start])) {
            start++;
        }

        int end = parts.length - 1;
        if (parts.length - start >= 3 && parts[parts.length - 2].length() <= 3
                && parts[parts.length - 1].length() <= 3) {
            end = parts.length - 2;
        }

        String label = parts[Math.max(start, end - 1)];
        return label.isBlank() || HOST_PREFIXES.contains(label) ? null : label;
    }

    /** "FamPay Technologies!" to "fampaytechnologies". */
    private static String compact(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String stripSuffixes(String compact) {
        String result = compact;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : SUFFIXES) {
                if (result.length() > suffix.length() + 2 && result.endsWith(suffix)) {
                    result = result.substring(0, result.length() - suffix.length());
                    changed = true;
                }
            }
        }
        return result;
    }
}
