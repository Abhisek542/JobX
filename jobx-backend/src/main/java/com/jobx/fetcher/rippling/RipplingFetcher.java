package com.jobx.fetcher.rippling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.BoardTokens;
import com.jobx.fetcher.ExperienceParser;
import com.jobx.fetcher.FetchFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetcher for Rippling ATS — VERIFIED live against Rippling's own board (2026-10-04).
 *
 * TWO-CALL DESIGN — the list has no description and no date:
 *   List:   GET https://api.rippling.com/platform/api/ats/v1/board/{slug}/jobs
 *           → a bare array of {uuid, name, department, url, workLocation}, the
 *             whole board in one call (no paging)
 *   Detail: GET https://ats.rippling.com/api/v2/board/{slug}/jobs/{uuid}
 *           → description{company, role} (HTML), createdOn (ISO with offset),
 *             workLocations[], employmentType, companyName
 *
 * Quirks from live recon:
 *  - the list repeats a job ONCE PER LOCATION under the same uuid — 651 rows,
 *    331 jobs on Rippling's board. Rows are grouped by uuid and their
 *    locations joined.
 *  - slugs are CASE-SENSITIVE: "rippling" is a board, "Rippling" is a 404.
 *  - an unknown slug is a clean 404 RESOURCE_NOT_FOUND, so a dead board always
 *    surfaces as an AtsFetchException.
 *
 * N+1 mitigation: detail is fetched only for uuids the FetchFilter doesn't
 * already know. Unlike Workable, the age gate can't run before the detail call,
 * because the list carries no date. Checking the age after the call still stops
 * a posting past the TTL from being stored, only to be swept a day later. A
 * failed detail call skips the job so the next cycle retries it (see Workable).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RipplingFetcher implements AtsFetcher {

    private static final String LIST_BASE = "https://api.rippling.com/platform/api/ats/v1/board/";
    private static final String DETAIL_BASE = "https://ats.rippling.com/api/v2/board/";
    /** A job listed in 40 cities would otherwise produce a location string nobody reads. */
    private static final int MAX_LOCATIONS = 3;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.RIPPLING;
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        String slug = BoardTokens.requirePathSegment(company.getBoardToken());
        log.info("Fetching Rippling board: {} ({})", company.getDisplayName(), slug);

        String responseBody = fetchList(slug);
        try {
            return parseList(responseBody, company, filter, true);
        } catch (AtsFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse Rippling response for token " + slug, e);
        }
    }

    private String fetchList(String slug) {
        String responseBody;
        try {
            responseBody = webClientBuilder.build()
                    .get()
                    .uri(LIST_BASE + slug + "/jobs")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            throw new AtsFetchException("Rippling board request failed for token " + slug, e);
        }
        if (responseBody == null) {
            throw new AtsFetchException("Empty response from Rippling for token " + slug);
        }
        return responseBody;
    }

    /**
     * Package-private seam so fixture tests can exercise the mapping without HTTP.
     * fetchDetails=false lets tests cover the list mapping in isolation.
     */
    List<Job> parseList(String responseBody, Company company, FetchFilter filter,
                        boolean fetchDetails) throws Exception {
        Map<String, List<JsonNode>> rowsByUuid = groupByUuid(responseBody, company.getBoardToken());

        List<Job> results = new ArrayList<>();
        int detailCalls = 0;
        int skipped = 0;
        int tooOld = 0;

        for (Map.Entry<String, List<JsonNode>> entry : rowsByUuid.entrySet()) {
            String uuid = entry.getKey();

            // N+1 guard: a stored or tombstoned posting would be dropped by the
            // scheduler anyway, so its detail call would be pure waste.
            if (filter.isKnown(uuid)) {
                continue;
            }

            JsonNode first = entry.getValue().get(0);
            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.RIPPLING);
            job.setExternalId(uuid);
            job.setTitle(first.path("name").asText("").trim());
            job.setApplyUrl(first.path("url").asText(""));

            List<String> labels = new ArrayList<>();
            for (JsonNode row : entry.getValue()) {
                labels.add(row.path("workLocation").path("label").asText(""));
            }
            job.setLocation(joinLocations(labels));
            job.setRawJson(first.toString());

            if (fetchDetails) {
                // Own try/catch per job: one bad detail call must not kill the batch.
                // On failure the job is SKIPPED, not stored list-only. The list has
                // no description, and the N+1 guard skips anything already stored,
                // so a job saved without a description could never be repaired.
                // Skipping leaves it absent, so the next cycle retries it.
                String detailBody;
                try {
                    detailBody = fetchDetail(company.getBoardToken(), uuid);
                    detailCalls++;
                } catch (Exception e) {
                    log.warn("Rippling detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                            uuid, company.getDisplayName(), e.getMessage());
                    skipped++;
                    continue;
                }

                if (detailBody == null) {
                    log.warn("Rippling detail was empty for {} ({}) — skipping, will retry next cycle",
                            uuid, company.getDisplayName());
                    skipped++;
                    continue;
                }

                applyDetail(detailBody, job);

                // Past the TTL already. The detail call is spent, but storing the
                // posting would only have the sweep delete it within a day.
                if (filter.isTooOld(job.getPlatformPostedAt())) {
                    tooOld++;
                    continue;
                }
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (Rippling, {} detail calls, {} skipped pending retry, "
                        + "{} past the TTL)",
                results.size(), company.getDisplayName(), detailCalls, skipped, tooOld);
        return results;
    }

    /** List rows grouped by uuid, in board order. Throws if the payload isn't a board. */
    private Map<String, List<JsonNode>> groupByUuid(String responseBody, String token) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse Rippling response for token " + token, e);
        }
        // An empty board is an empty array. Anything else that isn't an array
        // is not a board response at all.
        if (root == null || !root.isArray()) {
            throw new AtsFetchException("Rippling response for token " + token + " is not a job list");
        }

        Map<String, List<JsonNode>> rowsByUuid = new LinkedHashMap<>();
        for (JsonNode row : root) {
            String uuid = row.path("uuid").asText("");
            if (!uuid.isEmpty()) {
                rowsByUuid.computeIfAbsent(uuid, k -> new ArrayList<>()).add(row);
            }
        }
        return rowsByUuid;
    }

    /** The per-posting detail call. Package-private seam so tests can count calls. */
    String fetchDetail(String slug, String uuid) {
        return webClientBuilder.build()
                .get()
                .uri(DETAIL_BASE + slug + "/jobs/" + uuid)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    // Package-private seam for fixture tests.
    void applyDetail(String detailBody, Job job) throws Exception {
        JsonNode detail = objectMapper.readTree(detailBody);

        // Both parts are HTML. "role" is the job itself; "company" is the
        // employer's standard about-us block.
        StringBuilder sb = new StringBuilder();
        for (String field : List.of("role", "company")) {
            String html = detail.path("description").path(field).asText("");
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

        // "2026-08-13T08:54:48.317000-07:00" — full ISO with offset.
        String createdOn = detail.path("createdOn").asText("");
        if (!createdOn.isEmpty()) {
            try {
                job.setPlatformPostedAt(OffsetDateTime.parse(createdOn).toInstant());
            } catch (Exception e) {
                log.debug("Could not parse createdOn '{}' for job {}", createdOn, job.getExternalId());
            }
        }

        JsonNode workLocations = detail.path("workLocations");
        if (workLocations.isArray() && !workLocations.isEmpty()) {
            List<String> labels = new ArrayList<>();
            workLocations.forEach(node -> labels.add(node.asText("")));
            String location = joinLocations(labels);
            if (location != null) {
                job.setLocation(location);
            }
        }

        // The detail response is a superset of the list row. Its application
        // form (activeJobApplication) is large and of no use to us.
        if (detail instanceof ObjectNode object) {
            object.remove("activeJobApplication");
        }
        job.setRawJson(detail.toString());
    }

    /**
     * One list call — never the detail calls fetch() makes. The v1 list carries
     * no company name, so displayName is null and callers fall back to the
     * catalog or domain name.
     */
    @Override
    public BoardPreview previewBoard(Company company) {
        String slug = BoardTokens.requirePathSegment(company.getBoardToken());
        return parsePreview(fetchList(slug), slug);
    }

    // Package-private seam so tests can exercise preview parsing without HTTP.
    BoardPreview parsePreview(String responseBody, String token) {
        Map<String, List<JsonNode>> rowsByUuid = groupByUuid(responseBody, token);

        List<String> titles = new ArrayList<>();
        for (List<JsonNode> rows : rowsByUuid.values()) {
            if (titles.size() >= BoardPreview.SAMPLE_SIZE) {
                break;
            }
            String title = rows.get(0).path("name").asText("").trim();
            if (!title.isEmpty()) {
                titles.add(title);
            }
        }
        // Count jobs, not rows: the list repeats a job once per location.
        return new BoardPreview(null, rowsByUuid.size(), titles);
    }

    /** Distinct non-blank labels, at most MAX_LOCATIONS, then "+N more". Null if none. */
    static String joinLocations(List<String> labels) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String label : labels) {
            if (label != null && !label.isBlank()) {
                distinct.add(label.trim());
            }
        }
        if (distinct.isEmpty()) {
            return null;
        }
        List<String> shown = distinct.stream().limit(MAX_LOCATIONS).toList();
        String joined = String.join("; ", shown);
        int more = distinct.size() - shown.size();
        return more > 0 ? joined + " +" + more + " more" : joined;
    }
}
