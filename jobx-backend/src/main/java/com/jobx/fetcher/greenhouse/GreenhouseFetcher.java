package com.jobx.fetcher.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.ExperienceParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.GREENHOUSE;
    }

    @Override
    public List<Job> fetch(WatchedCompany company) {
        String token = company.getBoardToken();
        String url = BASE_URL + "/v1/boards/" + token + "/jobs?content=true";
        log.info("Fetching Greenhouse board: {} ({})", company.getCompanyName(), token);

        String responseBody;
        try {
            responseBody = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            throw new AtsFetchException("Greenhouse board request failed for token " + token, e);
        }

        if (responseBody == null) {
            throw new AtsFetchException("Empty response from Greenhouse for token " + token);
        }

        try {
            return parse(responseBody, company);
        } catch (AtsFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse Greenhouse response for token " + token, e);
        }
    }

    // Package-private seam so fixture tests can exercise the mapping without HTTP.
    List<Job> parse(String responseBody, WatchedCompany company) throws Exception {
        List<Job> results = new ArrayList<>();

        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode jobs = root.get("jobs");

        // A board with no openings returns an empty array, not a missing one —
        // a missing/!array "jobs" means the payload isn't a board response.
        if (jobs == null || !jobs.isArray()) {
            throw new AtsFetchException(
                    "No jobs array in Greenhouse response for token " + company.getBoardToken());
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
                ExperienceParser.parse(plainText, job);
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

        return results;
    }
}
