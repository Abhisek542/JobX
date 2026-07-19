package com.jobx.fetcher.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetcher for Greenhouse ATS — VERIFIED in Phase 0 against Razorpay and PhonePe.
 *
 * Endpoint: GET https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true
 * No auth required — public job board API.
 *
 * Translation decisions (from Phase 0 findings):
 *  - Filter out prospect posts: internal_job_id == null → skip
 *  - title          → Job.title
 *  - location.name  → Job.location
 *  - absolute_url   → Job.applyUrl
 *  - first_published (ISO 8601 with tz offset) → Job.platformPostedAt
 *  - content (HTML entity-escaped) → strip HTML → Job.description
 *  - id (integer)   → Job.externalId (stored as String for cross-ATS consistency)
 *  - metadata       → raw_json only, never typed columns (per-company schema varies)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GreenhouseFetcher implements AtsFetcher {

    private static final String BASE_URL = "https://boards-api.greenhouse.io";

    // Patterns for best-effort experience range extraction from JD text
    // e.g. "3-5 years", "5+ years", "minimum 2 years", "0-1 years"
    private static final Pattern EXP_RANGE  = Pattern.compile("(\\d+)\\s*[-–]\\s*(\\d+)\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXP_PLUS   = Pattern.compile("(\\d+)\\+\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXP_MIN_KW = Pattern.compile("(?:minimum|at\\s+least|min\\.?)\\s+(\\d+)\\s*(?:years?|yrs?)", Pattern.CASE_INSENSITIVE);

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.GREENHOUSE;
    }

    @Override
    public List<Job> fetch(WatchedCompany company) {
        List<Job> results = new ArrayList<>();
        String token = company.getBoardToken();

        try {
            String url = BASE_URL + "/v1/boards/" + token + "/jobs?content=true";
            log.info("Fetching Greenhouse board: {} ({})", company.getCompanyName(), token);

            String responseBody = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseBody == null) {
                log.warn("Empty response from Greenhouse for token: {}", token);
                return results;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode jobs = root.get("jobs");

            if (jobs == null || !jobs.isArray()) {
                log.warn("No jobs array in Greenhouse response for token: {}", token);
                return results;
            }

            int total = root.path("meta").path("total").asInt(0);
            log.info("Greenhouse returned {} jobs for {}", total, company.getCompanyName());

            for (JsonNode node : jobs) {

                // CRITICAL: filter out prospect posts — confirmed in Phase 0
                // These are "register your interest" pages, not real openings
                if (node.get("internal_job_id") == null || node.get("internal_job_id").isNull()) {
                    log.debug("Skipping prospect post id={}", node.path("id").asText());
                    continue;
                }

                Job job = new Job();
                job.setCompany(company);
                job.setAtsPlatform(AtsPlatform.GREENHOUSE);

                // external_id: Greenhouse integer job id, stored as String
                job.setExternalId(node.path("id").asText());

                // title: consistent field name across all Greenhouse boards
                job.setTitle(node.path("title").asText(""));

                // location: top-level location.name field
                // (metadata may have more granular location but schema is per-company)
                JsonNode locNode = node.get("location");
                if (locNode != null && locNode.get("name") != null) {
                    job.setLocation(locNode.get("name").asText());
                }

                // apply_url: absolute_url confirmed in Phase 0
                job.setApplyUrl(node.path("absolute_url").asText(""));

                // platform_posted_at: first_published is ISO 8601 with tz offset
                // e.g. "2026-07-02T00:35:43-04:00" — parse directly, no epoch conversion
                String firstPublished = node.path("first_published").asText("");
                if (!firstPublished.isEmpty()) {
                    try {
                        job.setPlatformPostedAt(OffsetDateTime.parse(firstPublished).toInstant());
                    } catch (Exception e) {
                        log.debug("Could not parse first_published '{}' for job {}", firstPublished, job.getExternalId());
                    }
                }

                // description: content field is HTML entity-escaped HTML
                // Strip to plain text for MatchScorer keyword matching
                String content = node.path("content").asText("");
                if (!content.isEmpty()) {
                    String plainText = Jsoup.parse(content).text();
                    job.setDescription(plainText);

                    // Best-effort experience range extraction from description text
                    // Nullable — MatchScorer handles null as distance=0 (full 30 pts)
                    parseExperience(plainText, job);
                }

                // raw_json: full ATS node for escape hatch
                // Captures company-specific metadata (Razorpay's "Job Location",
                // PhonePe's "Requisition Type") without polluting typed schema
                job.setRawJson(node.toString());

                job.setFirstSeenAt(Instant.now());

                results.add(job);
            }

            log.info("Translated {} real jobs (excluding prospect posts) for {}",
                    results.size(), company.getCompanyName());

        } catch (Exception e) {
            // Never throw — log and return empty list, next poll cycle will retry
            log.error("Failed to fetch Greenhouse board for {} (token={}): {}",
                    company.getCompanyName(), token, e.getMessage(), e);
        }

        return results;
    }

    /**
     * Best-effort extraction of experience range from JD free text.
     * Sets expMin and/or expMax on the job. Both remain null if nothing matches.
     *
     * Patterns handled:
     *   "3-5 years"         → min=3, max=5
     *   "5+ years"          → min=5, max=null
     *   "minimum 2 years"   → min=2, max=null
     */
    private void parseExperience(String text, Job job) {
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
