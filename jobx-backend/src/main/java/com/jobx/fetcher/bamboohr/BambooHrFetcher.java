package com.jobx.fetcher.bamboohr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fetcher for BambooHR — VERIFIED live against Off Duty Management (2026-10-04).
 *
 * THE TOKEN IS A SUBDOMAIN: {sub}.bamboohr.com. It goes through
 * {@link BoardTokens#requireSubdomainLabel} before the first request, and the
 * host is only ever built as {@code label + ".bamboohr.com"} — an unchecked
 * token here would be SSRF, because POST /watchlist takes it from the user.
 *
 * TWO-CALL DESIGN — the list has no description and no date:
 *   List:   GET https://{sub}.bamboohr.com/careers/list
 *           → {meta:{totalCount}, result:[{id, jobOpeningName, departmentLabel,
 *              employmentStatusLabel, location{city,state}, atsLocation{...}}]}
 *   Detail: GET https://{sub}.bamboohr.com/careers/{id}/detail
 *           → {result:{jobOpening:{description (HTML), datePosted (DATE-ONLY),
 *              jobOpeningShareUrl, location, atsLocation, ...}, formFields}}
 *
 * Dead-board signals, from live recon:
 *  - an unknown subdomain answers 302 → https://www.bamboohr.com/. Every one of
 *    ~30 random labels did. WebClient doesn't follow redirects here, and
 *    retrieve() treats a 3xx as success, so the status is checked explicitly
 *    rather than trusting an empty body to catch it.
 *  - dormant accounts (andela, zapier, toggl, asana) answer 200 with
 *    {"meta":{"totalCount":0},"result":[]}. That is a real, empty board as far
 *    as fetch() is concerned; validateBoard's zero-roles rule keeps it off a
 *    watchlist at add time, and the resolver's probe ignores it.
 *
 * N+1 mitigation: detail only for ids the FetchFilter doesn't know. The date
 * arrives with the detail, so the age gate runs after it — it stops a stale
 * posting being stored, not the call. A failed detail call skips the job.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BambooHrFetcher implements AtsFetcher {

    private static final String HOST_SUFFIX = ".bamboohr.com";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.BAMBOOHR;
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        String sub = BoardTokens.requireSubdomainLabel(company.getBoardToken());
        log.info("Fetching BambooHR board: {} ({})", company.getDisplayName(), sub);

        String responseBody = body(baseUrl(sub) + "/careers/list", sub);
        try {
            return parseList(responseBody, company, filter, true);
        } catch (AtsFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse BambooHR response for token " + sub, e);
        }
    }

    private static String baseUrl(String validatedSub) {
        return "https://" + validatedSub + HOST_SUFFIX;
    }

    /**
     * The one HTTP call every request goes through. Package-private so tests
     * can stand in a 302 without a server.
     */
    ResponseEntity<String> get(String url) {
        return webClientBuilder.build()
                .get()
                .uri(url)
                .retrieve()
                .toEntity(String.class)
                .block();
    }

    /** A 2xx body, or an AtsFetchException — a redirect means the board doesn't exist. */
    private String body(String url, String sub) {
        ResponseEntity<String> response;
        try {
            response = get(url);
        } catch (Exception e) {
            throw new AtsFetchException("BambooHR request failed for token " + sub, e);
        }
        if (response == null) {
            throw new AtsFetchException("Empty response from BambooHR for token " + sub);
        }
        if (response.getStatusCode().is3xxRedirection()) {
            throw new AtsFetchException("BambooHR board '" + sub + "' does not exist (redirected to "
                    + response.getHeaders().getLocation() + ")");
        }
        if (response.getBody() == null) {
            throw new AtsFetchException("Empty response from BambooHR for token " + sub);
        }
        return response.getBody();
    }

    /**
     * Package-private seam so fixture tests can exercise the mapping without HTTP.
     * fetchDetails=false lets tests cover the list mapping in isolation.
     */
    List<Job> parseList(String responseBody, Company company, FetchFilter filter,
                        boolean fetchDetails) throws Exception {
        JsonNode result = resultArray(responseBody, company.getBoardToken());
        String sub = company.getBoardToken().toLowerCase(Locale.ROOT);

        List<Job> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int detailCalls = 0;
        int skipped = 0;
        int tooOld = 0;

        for (JsonNode node : result) {
            String id = node.path("id").asText("");
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }

            // N+1 guard: a stored or tombstoned posting would be dropped by the
            // scheduler anyway, so its detail call would be pure waste.
            if (filter.isKnown(id)) {
                continue;
            }

            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.BAMBOOHR);
            job.setExternalId(id);
            job.setTitle(node.path("jobOpeningName").asText("").trim());
            job.setApplyUrl(baseUrl(sub) + "/careers/" + id);
            job.setLocation(location(node));
            job.setRawJson(node.toString());

            if (fetchDetails) {
                // Own try/catch per job: one bad detail call must not kill the batch.
                // On failure the job is SKIPPED, not stored list-only. The list has
                // no description, and the N+1 guard skips anything already stored,
                // so a job saved without one could never be repaired. Skipping
                // leaves it absent, so the next cycle retries it.
                String detailBody;
                try {
                    detailBody = fetchDetail(sub, id);
                    detailCalls++;
                } catch (Exception e) {
                    log.warn("BambooHR detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                            id, company.getDisplayName(), e.getMessage());
                    skipped++;
                    continue;
                }

                LocalDate posted = applyDetail(detailBody, job);

                // datePosted is DAY precision, so judge it by the END of that day:
                // midnight would make a posting look up to 24h older than it is.
                if (posted != null
                        && filter.isTooOld(posted.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
                    tooOld++;
                    continue;
                }
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (BambooHR, {} detail calls, {} skipped pending retry, "
                        + "{} past the TTL)",
                results.size(), company.getDisplayName(), detailCalls, skipped, tooOld);
        return results;
    }

    /** The "result" array, or an AtsFetchException when the payload isn't a board. */
    private JsonNode resultArray(String responseBody, String token) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse BambooHR response for token " + token, e);
        }
        JsonNode result = root == null ? null : root.get("result");
        // A board with no openings has an empty array, never a missing one.
        if (result == null || !result.isArray()) {
            throw new AtsFetchException("No result array in BambooHR response for token " + token);
        }
        return result;
    }

    /** The per-posting detail call. Package-private seam so tests can count calls. */
    String fetchDetail(String validatedSub, String id) {
        return body(baseUrl(validatedSub) + "/careers/" + id + "/detail", validatedSub);
    }

    /**
     * Package-private seam for fixture tests.
     *
     * @return the posting date, or null — the caller judges the TTL by it
     */
    LocalDate applyDetail(String detailBody, Job job) throws Exception {
        JsonNode opening = objectMapper.readTree(detailBody).path("result").path("jobOpening");
        if (!opening.isObject()) {
            throw new AtsFetchException("No jobOpening in BambooHR detail for job " + job.getExternalId());
        }

        String html = opening.path("description").asText("");
        if (!html.isBlank()) {
            String description = Jsoup.parse(html).text().trim();
            if (!description.isEmpty()) {
                job.setDescription(description);
                ExperienceParser.parse(description, job);
            }
        }

        String shareUrl = opening.path("jobOpeningShareUrl").asText("");
        if (shareUrl.startsWith("https://")) {
            job.setApplyUrl(shareUrl);
        }

        String location = location(opening);
        if (location != null) {
            job.setLocation(location);
        }

        // "2026-07-16" — date-only, stored as midnight UTC.
        LocalDate posted = null;
        String datePosted = opening.path("datePosted").asText("");
        if (!datePosted.isEmpty()) {
            try {
                posted = LocalDate.parse(datePosted);
                job.setPlatformPostedAt(posted.atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (Exception e) {
                log.debug("Could not parse datePosted '{}' for job {}", datePosted, job.getExternalId());
            }
        }

        // Only the posting itself; formFields is the application form.
        job.setRawJson(opening.toString());
        return posted;
    }

    /**
     * A posting fills either {@code location} (city, state) or {@code atsLocation}
     * (city, state, country) — live, the same board used both. Null if neither.
     */
    private static String location(JsonNode node) {
        String primary = join(node.path("location"), "city", "state");
        if (primary != null) {
            return primary;
        }
        String ats = join(node.path("atsLocation"), "city", "state", "country");
        if (ats != null) {
            return ats;
        }
        return node.path("isRemote").asBoolean(false) ? "Remote" : null;
    }

    private static String join(JsonNode node, String... fields) {
        Set<String> parts = new LinkedHashSet<>();
        for (String field : fields) {
            String value = node.path(field).asText("");
            // asText on a JSON null gives "null" for some node types; guard it.
            if (!value.isBlank() && !node.path(field).isNull()) {
                parts.add(value.trim());
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * One list call — never the detail calls fetch() makes. The list carries no
     * company name, so displayName is null.
     */
    @Override
    public BoardPreview previewBoard(Company company) {
        String sub = BoardTokens.requireSubdomainLabel(company.getBoardToken());
        return parsePreview(body(baseUrl(sub) + "/careers/list", sub), sub);
    }

    // Package-private seam so tests can exercise preview parsing without HTTP.
    BoardPreview parsePreview(String responseBody, String token) {
        JsonNode result = resultArray(responseBody, token);

        Set<String> ids = new HashSet<>();
        List<String> titles = new ArrayList<>();
        for (JsonNode node : result) {
            String id = node.path("id").asText("");
            if (id.isEmpty() || !ids.add(id)) {
                continue;
            }
            String title = node.path("jobOpeningName").asText("").trim();
            if (titles.size() < BoardPreview.SAMPLE_SIZE && !title.isEmpty()) {
                titles.add(title);
            }
        }
        return new BoardPreview(null, ids.size(), titles);
    }
}
