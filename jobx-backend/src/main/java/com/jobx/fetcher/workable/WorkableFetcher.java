package com.jobx.fetcher.workable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.ExperienceParser;
import com.jobx.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetcher for Workable ATS — VERIFIED live against Apna (2026-08-02).
 *
 * TWO-CALL DESIGN — the list endpoint has no description field at all:
 *   List:   GET https://apply.workable.com/api/v1/widget/accounts/{token}
 *           → { name, description, jobs: [] }, items carry shortcode/title/city/url/published_on
 *   Detail: GET https://apply.workable.com/api/v2/accounts/{token}/jobs/{shortcode}
 *           → description + requirements + benefits (HTML), published (full ISO)
 *           (v1 widget /jobs/{code} and v3 paths both 404 — v2 is the working detail path)
 *
 * N+1 mitigation: detail is fetched ONLY for shortcodes not already in the DB
 * (JobRepository check). First fetch of a board pays full price (~128 calls for
 * Apna); steady state is ~0–2 per cycle. If a detail call fails, the job is
 * still emitted from list data (null description) rather than dropped.
 *
 * Other quirks from live recon:
 *  - list "experience" is a seniority label ("Associate"), NOT years — ignored;
 *    years are parsed from description text like every other platform
 *  - list published_on is DATE-ONLY — detail "published" (full ISO) preferred
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkableFetcher implements AtsFetcher {

    private static final String BASE_URL = "https://apply.workable.com";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final JobRepository jobRepository;

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.WORKABLE;
    }

    @Override
    public List<Job> fetch(WatchedCompany company) {
        String token = company.getBoardToken();

        try {
            String url = BASE_URL + "/api/v1/widget/accounts/" + token;
            log.info("Fetching Workable board: {} ({})", company.getCompanyName(), token);

            String responseBody = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseBody == null) {
                log.warn("Empty response from Workable for token: {}", token);
                return List.of();
            }

            return parseList(responseBody, company, true);

        } catch (Exception e) {
            // Never throw — log and return empty list, next poll cycle will retry
            log.error("Failed to fetch Workable board for {} (token={}): {}",
                    company.getCompanyName(), token, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Package-private seam so fixture tests can exercise the mapping without HTTP.
     * fetchDetails=false lets tests cover the list mapping in isolation.
     */
    List<Job> parseList(String responseBody, WatchedCompany company, boolean fetchDetails) throws Exception {
        List<Job> results = new ArrayList<>();

        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode jobs = root.get("jobs");

        if (jobs == null || !jobs.isArray()) {
            log.warn("No jobs array in Workable response for {}", company.getCompanyName());
            return results;
        }

        int detailCalls = 0;
        // The list repeats a job once per posting location, same shortcode —
        // observed live on Apna (128 rows, 96 unique). Dedupe within the batch.
        java.util.Set<String> seenShortcodes = new java.util.HashSet<>();

        for (JsonNode node : jobs) {
            String shortcode = node.path("shortcode").asText("");
            if (shortcode.isEmpty() || !seenShortcodes.add(shortcode)) {
                continue;
            }

            // N+1 guard: known jobs get skipped entirely — the scheduler would
            // dedup them anyway, so a detail call would be pure waste
            if (fetchDetails && jobRepository.existsByCompanyAndExternalId(company, shortcode)) {
                continue;
            }

            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.WORKABLE);
            job.setExternalId(shortcode);
            job.setTitle(node.path("title").asText(""));
            job.setApplyUrl(node.path("url").asText(""));

            String city = node.path("city").asText("");
            String country = node.path("country").asText("");
            String location = String.join(", ",
                    List.of(city, country).stream().filter(s -> !s.isBlank()).toList());
            if (!location.isBlank()) {
                job.setLocation(location);
            }

            // published_on is date-only ("2026-06-27") — midnight UTC fallback;
            // overwritten by the detail call's full ISO timestamp when available
            String publishedOn = node.path("published_on").asText("");
            if (!publishedOn.isEmpty()) {
                try {
                    job.setPlatformPostedAt(LocalDate.parse(publishedOn).atStartOfDay(ZoneOffset.UTC).toInstant());
                } catch (Exception e) {
                    log.debug("Could not parse published_on '{}' for job {}", publishedOn, shortcode);
                }
            }

            job.setRawJson(node.toString());

            if (fetchDetails) {
                // Own try/catch per job — one bad detail call must not kill the batch
                try {
                    String detailBody = webClientBuilder.build()
                            .get()
                            .uri(BASE_URL + "/api/v2/accounts/" + company.getBoardToken() + "/jobs/" + shortcode)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
                    detailCalls++;
                    if (detailBody != null) {
                        applyDetail(detailBody, job);
                    }
                } catch (Exception e) {
                    log.warn("Workable detail fetch failed for {} ({}) — emitting list-only job: {}",
                            shortcode, company.getCompanyName(), e.getMessage());
                }
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (Workable, {} detail calls)",
                results.size(), company.getCompanyName(), detailCalls);
        return results;
    }

    // Package-private seam for fixture tests.
    void applyDetail(String detailBody, Job job) throws Exception {
        JsonNode detail = objectMapper.readTree(detailBody);

        // description + requirements + benefits are all HTML — strip and concatenate
        StringBuilder sb = new StringBuilder();
        for (String field : List.of("description", "requirements", "benefits")) {
            String html = detail.path(field).asText("");
            if (!html.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(Jsoup.parse(html).text().trim());
            }
        }
        String description = sb.toString().trim();
        if (!description.isEmpty()) {
            job.setDescription(description);
            ExperienceParser.parse(description, job);
        }

        // Full ISO timestamp — better than the list's date-only published_on
        String published = detail.path("published").asText("");
        if (!published.isEmpty()) {
            try {
                job.setPlatformPostedAt(OffsetDateTime.parse(published).toInstant());
            } catch (Exception e) {
                log.debug("Could not parse published '{}' for job {}", published, job.getExternalId());
            }
        }

        // Detail response is a superset of the list item — store it instead
        job.setRawJson(detail.toString());
    }
}
