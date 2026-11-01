package com.jobx.crawler;

import java.time.Instant;

/**
 * One posting read off a careers page by {@link CareerPageExtractor}. Every
 * field but {@code title} may be null: a JSON-LD block can lack a URL, and a
 * heuristic listing rarely carries a date or a description.
 *
 * @param url the posting's canonical URL ({@link CanonicalUrl}), or null
 */
public record ExtractedJob(String title,
                           String url,
                           String location,
                           String department,
                           String employmentType,
                           Instant postedAt,
                           String description) {
}
