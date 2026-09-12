package com.jobx.fetcher.workable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Job;
import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.ExperienceParser;
import com.jobx.fetcher.FetchFilter;
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
 * N+1 mitigation: detail is fetched ONLY for shortcodes the FetchFilter does not
 * already know — neither stored nor tombstoned — and whose list date is inside
 * the retention window. First fetch of a board pays for its fresh postings only;
 * steady state is ~0–2 per cycle. If a detail call fails, the job is skipped
 * and retried next cycle (see the comment at the detail call).
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

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.WORKABLE;
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        String token = company.getBoardToken();
        String url = BASE_URL + "/api/v1/widget/accounts/" + token;
        log.info("Fetching Workable board: {} ({})", company.getDisplayName(), token);

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
            return parseList(responseBody, company, filter, true);
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
    List<Job> parseList(String responseBody, Company company, FetchFilter filter,
                        boolean fetchDetails) throws Exception {
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
        int tooOld = 0;
        // The list repeats a job once per posting location, same shortcode —
        // observed live on Apna (128 rows, 96 unique). Dedupe within the batch.
        java.util.Set<String> seenShortcodes = new java.util.HashSet<>();

        for (JsonNode node : jobs) {
            String shortcode = node.path("shortcode").asText("");
            if (shortcode.isEmpty() || !seenShortcodes.add(shortcode)) {
                continue;
            }

            // N+1 guard: a stored OR tombstoned posting would be dropped by the
            // scheduler anyway, so its detail call would be pure waste. This
            // used to check only the jobs table, which cost one detail call per
            // TTL-expired posting still listed on the board, every cycle.
            if (filter.isKnown(shortcode)) {
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
            LocalDate publishedDate = null;
            if (!publishedOn.isEmpty()) {
                try {
                    publishedDate = LocalDate.parse(publishedOn);
                    job.setPlatformPostedAt(publishedDate.atStartOfDay(ZoneOffset.UTC).toInstant());
                } catch (Exception e) {
                    log.debug("Could not parse published_on '{}' for job {}", publishedOn, shortcode);
                }
            }

            // Past the TTL already — the sweep would delete it within a day, so
            // don't pay a detail call for it. The date is DAY precision, so judge
            // by the END of that day: midnight would make every posting look up
            // to 24h older than it is and drop roles still inside the window.
            if (publishedDate != null
                    && filter.isTooOld(publishedDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
                tooOld++;
                continue;
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
                    detailBody = fetchDetail(company.getBoardToken(), shortcode);
                    detailCalls++;
                } catch (Exception e) {
                    log.warn("Workable detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                            shortcode, company.getDisplayName(), e.getMessage());
                    skipped++;
                    continue;
                }

                if (detailBody == null) {
                    log.warn("Workable detail was empty for {} ({}) — skipping, will retry next cycle",
                            shortcode, company.getDisplayName());
                    skipped++;
                    continue;
                }

                applyDetail(detailBody, job);
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (Workable, {} detail calls, {} skipped pending retry, "
                        + "{} past the TTL)",
                results.size(), company.getDisplayName(), detailCalls, skipped, tooOld);
        return results;
    }

    /** The per-posting detail call. Package-private seam so tests can count calls. */
    String fetchDetail(String token, String shortcode) {
        return webClientBuilder.build()
                .get()
                .uri(BASE_URL + "/api/v2/accounts/" + token + "/jobs/" + shortcode)
                .retrieve()
                .bodyToMono(String.class)
                .block();
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

    /**
     * One list call — never the detail calls fetch() makes, so previewing a
     * board costs one request whether it has 4 openings or 158.
     *
     * Workable names the company in the list root, which is what lets the
     * add-company flow fill the name in for the user — and is also the trap
     * here. An abandoned or never-used Workable account answers 200 with a
     * perfectly plausible name and an empty jobs array: verified live,
     * {@code apply.workable.com/api/v1/widget/accounts/razorpay} returns
     * {@code {"name":"Razorpay","description":null,"jobs":[]}}, and the same is
     * true for groww, atlan, meesho and sprinto — none of which are Workable
     * customers. A name is therefore NOT evidence that a board is real; only a
     * live posting is. The inherited validateBoard rejects a zero count for
     * exactly this reason, and the add-company resolver applies the same rule
     * before it will ever propose a Workable board to a user.
     */
    @Override
    public BoardPreview previewBoard(Company company) {
        String token = company.getBoardToken();
        String url = BASE_URL + "/api/v1/widget/accounts/" + token;

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

        return parsePreview(responseBody, token);
    }

    // Package-private seam so tests can exercise preview parsing without HTTP.
    BoardPreview parsePreview(String responseBody, String token) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse Workable response for token " + token, e);
        }

        JsonNode jobs = root.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            throw new AtsFetchException("No jobs array in Workable response for token " + token);
        }

        List<String> titles = new ArrayList<>();
        // The list repeats a job once per posting location under the same
        // shortcode (Apna: 158 rows, 126 jobs). Count what fetch() would store,
        // not what the board happens to have said.
        java.util.Set<String> seenShortcodes = new java.util.HashSet<>();
        for (JsonNode node : jobs) {
            String shortcode = node.path("shortcode").asText("");
            if (shortcode.isEmpty() || !seenShortcodes.add(shortcode)) {
                continue;
            }
            String title = node.path("title").asText("");
            if (titles.size() < BoardPreview.SAMPLE_SIZE && !title.isBlank()) {
                titles.add(title.trim());
            }
        }

        String name = root.path("name").asText("");
        return new BoardPreview(name.isBlank() ? null : name.trim(),
                seenShortcodes.size(), titles);
    }
}
