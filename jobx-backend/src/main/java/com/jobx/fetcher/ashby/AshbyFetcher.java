package com.jobx.fetcher.ashby;

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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetcher for Ashby ATS — VERIFIED live against Aspora (2026-08-02).
 *
 * Endpoint: GET https://api.ashbyhq.com/posting-api/job-board/{token}
 * (NOT the jobPosting.list RPC endpoint — that requires an API key)
 * No auth required — public job board API. Root shape: { jobs: [], apiVersion }.
 *
 * Translation decisions (from live recon):
 *  - Filter out unlisted posts: isListed == false → skip
 *  - title            → Job.title
 *  - location         → Job.location (trailing whitespace observed live — trim)
 *  - jobUrl           → Job.applyUrl (the posting page; applyUrl field is the bare form)
 *  - publishedAt (ISO 8601 with offset) → Job.platformPostedAt
 *  - descriptionPlain → Job.description directly — no HTML stripping needed
 *  - id (UUID string) → Job.externalId
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AshbyFetcher implements AtsFetcher {

    private static final String BASE_URL = "https://api.ashbyhq.com";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.ASHBY;
    }

    @Override
    public List<Job> fetch(WatchedCompany company) {
        String token = company.getBoardToken();
        String url = BASE_URL + "/posting-api/job-board/" + token;
        log.info("Fetching Ashby board: {} ({})", company.getCompanyName(), token);

        String responseBody;
        try {
            responseBody = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            throw new AtsFetchException("Ashby board request failed for token " + token, e);
        }

        if (responseBody == null) {
            throw new AtsFetchException("Empty response from Ashby for token " + token);
        }

        try {
            return parse(responseBody, company);
        } catch (AtsFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse Ashby response for token " + token, e);
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
                    "No jobs array in Ashby response for token " + company.getBoardToken());
        }

        for (JsonNode node : jobs) {

            // Unlisted postings are hidden on the public board — skip them
            if (!node.path("isListed").asBoolean(true)) {
                log.debug("Skipping unlisted Ashby post id={}", node.path("id").asText());
                continue;
            }

            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.ASHBY);

            job.setExternalId(node.path("id").asText());
            job.setTitle(node.path("title").asText(""));

            // Live data has trailing whitespace ("Bangalore ") — trim
            String location = node.path("location").asText("");
            if (!location.isBlank()) {
                job.setLocation(location.trim());
            }

            job.setApplyUrl(node.path("jobUrl").asText(""));

            // publishedAt: ISO 8601 with offset, e.g. "2026-06-04T12:41:40.046+00:00"
            String publishedAt = node.path("publishedAt").asText("");
            if (!publishedAt.isEmpty()) {
                try {
                    job.setPlatformPostedAt(OffsetDateTime.parse(publishedAt).toInstant());
                } catch (Exception e) {
                    log.debug("Could not parse publishedAt '{}' for job {}", publishedAt, job.getExternalId());
                }
            }

            // descriptionPlain is provided directly — no HTML stripping needed
            String description = node.path("descriptionPlain").asText("");
            if (!description.isEmpty()) {
                job.setDescription(description);
                ExperienceParser.parse(description, job);
            }

            job.setRawJson(node.toString());
            job.setFirstSeenAt(Instant.now());

            results.add(job);
        }

        log.info("Translated {} listed jobs for {} (Ashby)", results.size(), company.getCompanyName());
        return results;
    }
}
