package com.jobx.fetcher.workable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
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
        String url = BASE_URL + "/api/v1/widget/accounts/" + token;
        log.info("Fetching Workable board: {} ({})", company.getCompanyName(), token);

        String responseBody;
        try {
            responseBody = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            throw new AtsFetchException("Workable board request failed for token " + token, e);
        }

        if (responseBody == null) {
            throw new AtsFetchException("Empty response from Workable for token " + token);
        }

        try {
            return parseList(responseBody, company, true);
        } catch (AtsFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse Workable response for token " + token, e);
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

        // A board with no openings returns an empty array, not a missing one —
        // a missing/!array "jobs" means the payload isn't a board response.
        if (jobs == null || !jobs.isArray()) {
            throw new AtsFetchException(
                    "No jobs array in Workable response for token " + company.getBoardToken());
        }

        int detailCalls = 0;
        int skipped = 0;
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
                // Own try/catch per job — one bad detail call must not kill the batch.
                //
                // On failure the job is SKIPPED, not emitted list-only. The list
                // endpoint carries no description at all, and because the N+1
                // guard above skips anything already in the DB, a job persisted
                // with a null description would never be revisited — one transient
                // 503 would leave it permanently unscoreable against description
                // keywords. Skipping leaves it absent, so the next cycle retries
                // it and it self-heals. The cost is that a posting whose detail
                // endpoint is durably broken stays invisible; that is the better
                // failure, because the alternative is showing it with a wrong score.
                String detailBody;
                try {
                    detailBody = webClientBuilder.build()
                            .get()
                            .uri(BASE_URL + "/api/v2/accounts/" + company.getBoardToken() + "/jobs/" + shortcode)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
                    detailCalls++;
                } catch (Exception e) {
                    log.warn("Workable detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                            shortcode, company.getCompanyName(), e.getMessage());
                    skipped++;
                    continue;
                }

                if (detailBody == null) {
                    log.warn("Workable detail was empty for {} ({}) — skipping, will retry next cycle",
                            shortcode, company.getCompanyName());
                    skipped++;
                    continue;
                }

                applyDetail(detailBody, job);
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (Workable, {} detail calls, {} skipped pending retry)",
                results.size(), company.getCompanyName(), detailCalls, skipped);
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
