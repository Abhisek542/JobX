package com.jobx.fetcher;

import com.jobx.entity.Job;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of an experience range from JD free text.
 * Hoisted from GreenhouseFetcher so all AtsFetcher implementations share it.
 *
 * Sets expMin and/or expMax on the job. Both remain null if nothing matches —
 * MatchScorer treats null as distance=0 (full 30 pts), never hard-excludes.
 *
 * Patterns handled:
 *   "3-5 years"         → min=3, max=5
 *   "5+ years"          → min=5, max=null
 *   "minimum 2 years"   → min=2, max=null
 */
public final class ExperienceParser {

    // e.g. "3-5 years", "5+ years", "minimum 2 years", "0-1 years"
    private static final Pattern EXP_RANGE  = Pattern.compile("(\\d+)\\s*[-–]\\s*(\\d+)\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXP_PLUS   = Pattern.compile("(\\d+)\\+\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXP_MIN_KW = Pattern.compile("(?:minimum|at\\s+least|min\\.?)\\s+(\\d+)\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);

    private ExperienceParser() {
    }

    public static void parse(String text, Job job) {
        // Try "X-Y years" first (most specific)
        Matcher rangeMatcher = EXP_RANGE.matcher(text);
        if (rangeMatcher.find()) {
            job.setExpMin(Integer.parseInt(rangeMatcher.group(1)));
            job.setExpMax(Integer.parseInt(rangeMatcher.group(2)));
            return;
        }

        // Try "X+ years"
        Matcher plusMatcher = EXP_PLUS.matcher(text);
        if (plusMatcher.find()) {
            job.setExpMin(Integer.parseInt(plusMatcher.group(1)));
            // no upper bound
            return;
        }

        // Try "minimum X years"
        Matcher minMatcher = EXP_MIN_KW.matcher(text);
        if (minMatcher.find()) {
            job.setExpMin(Integer.parseInt(minMatcher.group(1)));
        }
    }
}
