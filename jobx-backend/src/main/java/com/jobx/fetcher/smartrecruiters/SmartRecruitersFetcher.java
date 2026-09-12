package com.jobx.fetcher.smartrecruiters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetcher for SmartRecruiters — VERIFIED live against PhonePe (2026-08-29),
 * which migrated here off Greenhouse; their old board token now 404s.
 *
 * TWO-CALL DESIGN, for the same reason as Workable — the list carries neither
 * a description nor an apply URL:
 *   List:   GET https://api.smartrecruiters.com/v1/companies/{token}/postings?limit=100&offset=N
 *           → { offset, limit, totalFound, content: [] }
 *           items carry id / name / releasedDate / location / experienceLevel
 *   Detail: GET https://api.smartrecruiters.com/v1/companies/{token}/postings/{id}
 *           → postingUrl, applyUrl, jobAd.sections.{companyDescription,
 *             jobDescription, qualifications, additionalInformation}
 *
 * Quirks found during live recon, each of which shapes the code below:
 *
 *  - **PAGINATION IS MANDATORY.** limit is silently capped at 100 — asking for
 *    500 returns 100 with `"limit":100` echoed back. Bosch's board is 4,774
 *    postings, so a single call would quietly return the first 2% of a board
 *    and look perfectly successful. We page on offset until a page comes back
 *    empty.
 *
 *  - **A BOGUS TOKEN RETURNS 200 WITH AN EMPTY LIST, NOT 404.** Verified
 *    against a nonsense company id: `{"offset":0,"limit":1,"totalFound":0,
 *    "content":[]}`. Every other platform Jobx supports 404s an unknown token,
 *    which is what lets `fetch` treat "empty" as "genuinely empty". Here that
 *    inference is unavailable, so a typo would be indistinguishable from a
 *    board with nothing open — the exact silent-empty-feed shape that has bitten
 *    this project three times. {@link #validateBoard} is the answer: the token
 *    is checked once, when the user adds it, and a real board that later has
 *    zero openings still fetches normally.
 *
 *  - **description is split across jobAd.sections**, each an HTML blob, so they
 *    are stripped and concatenated the way Lever's multi-field body is.
 *
 *  - experienceLevel is a seniority label ("director", "mid_senior_level"), not
 *    years — ignored, exactly as Workable's is. Years come from description text
 *    via ExperienceParser like every other platform.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SmartRecruitersFetcher implements AtsFetcher {

    private static final String BASE_URL = "https://api.smartrecruiters.com";

    /** The API's own hard cap. Asking for more is silently clamped to this. */
    private static final int PAGE_SIZE = 100;

    /** Stops a runaway pager if the API ever returns a non-empty page forever. */
    private static final int MAX_PAGES = 200;

    /** The jobAd sections that make up a posting body, in reading order. */
    private static final List<String> SECTIONS = List.of(
            "companyDescription", "jobDescription", "qualifications", "additionalInformation");

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.SMARTRECRUITERS;
    }

    /**
     * One cheap list call, asking for only a sample page. jobCount is the API's
     * own {@code totalFound} rather than the rows returned, so a 4,774-posting
     * board like Bosch reports honestly without paging through it.
     *
     * Unusually among the five platforms, this one CAN name the company:
     * {@code content[].company.name} ("PHONEPE LIMITED"), which is what lets the
     * add-company flow fill the company name in for the user.
     *
     * The inherited validateBoard rejects a zero count, which is the whole
     * reason this platform needed a validation hook first: a bogus company id
     * answers 200 with an empty list rather than 404, so a typo would otherwise
     * look like a perfectly healthy board that simply never posts a job.
     */
    @Override
    public BoardPreview previewBoard(Company company) {
        String token = company.getBoardToken();
        String body = get(listUrl(token, BoardPreview.SAMPLE_SIZE, 0),
                "SmartRecruiters board request failed for token " + token);
        return parsePreview(body, token);
    }

    // Package-private seam so tests can exercise preview parsing without HTTP.
    BoardPreview parsePreview(String responseBody, String token) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse SmartRecruiters response for token " + token, e);
        }

        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) {
            throw new AtsFetchException("Not a SmartRecruiters board response for token " + token);
        }

        List<String> titles = new ArrayList<>();
        String displayName = null;
        for (JsonNode node : content) {
            if (displayName == null) {
                String name = node.path("company").path("name").asText("");
                if (!name.isBlank()) {
                    displayName = name.trim();
                }
            }
            // SmartRecruiters puts the job title in "name", not "title".
            String title = node.path("name").asText("");
            if (titles.size() < BoardPreview.SAMPLE_SIZE && !title.isBlank()) {
                titles.add(title.trim());
            }
        }

        return new BoardPreview(displayName, root.path("totalFound").asInt(0), titles);
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        String token = company.getBoardToken();
        log.info("Fetching SmartRecruiters board: {} ({})", company.getDisplayName(), token);

        return translate(fetchAllPages(token), company, filter);
    }

    /**
     * List rows → new jobs, paying a detail call only for postings that survive
     * the filter. Package-private seam so tests can count detail calls.
     */
    List<Job> translate(List<JsonNode> postings, Company company, FetchFilter filter) {
        String token = company.getBoardToken();
        List<Job> results = new ArrayList<>();
        int detailCalls = 0;
        int skipped = 0;
        int tooOld = 0;

        // The same posting can appear on more than one page if the board is
        // edited mid-pagination; dedupe within the batch as Workable does.
        Set<String> seen = new LinkedHashSet<>();

        for (JsonNode node : postings) {
            String externalId = node.path("id").asText("");
            if (externalId.isEmpty() || !seen.add(externalId)) {
                continue;
            }

            // N+1 guard: a stored OR tombstoned posting would be dropped by the
            // scheduler anyway, so its detail call would be pure waste. Bosch-
            // sized boards make this the difference between 2 calls and 4,774 —
            // and checking only the jobs table used to lose exactly that once
            // the TTL sweep had tombstoned the board.
            if (filter.isKnown(externalId)) {
                continue;
            }

            Job job = mapListItem(node, company, externalId);

            // Past the TTL already — the sweep would delete it within a day.
            // releasedDate is a full instant, so it compares directly.
            if (filter.isTooOld(job.getPlatformPostedAt())) {
                tooOld++;
                continue;
            }

            String detailBody;
            try {
                detailBody = fetchDetail(token, externalId);
                detailCalls++;
            } catch (Exception e) {
                // Skipped rather than emitted description-less, for exactly the
                // reason Workable documents: the N+1 guard above keys off "does
                // a row exist", so a job persisted without its description would
                // never be revisited and would stay permanently unscoreable.
                // Absent means the next cycle retries it.
                log.warn("SmartRecruiters detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                        externalId, company.getDisplayName(), e.getMessage());
                skipped++;
                continue;
            }

            if (detailBody == null) {
                log.warn("SmartRecruiters detail was empty for {} ({}) — skipping, will retry next cycle",
                        externalId, company.getDisplayName());
                skipped++;
                continue;
            }

            try {
                applyDetail(detailBody, job);
            } catch (Exception e) {
                log.warn("Could not parse SmartRecruiters detail for {} ({}) — skipping: {}",
                        externalId, company.getDisplayName(), e.getMessage());
                skipped++;
                continue;
            }

            // apply_url is NOT NULL — a posting we cannot send the user to is
            // worse than no card at all.
            if (job.getApplyUrl() == null || job.getApplyUrl().isBlank()) {
                log.warn("SmartRecruiters posting {} ({}) has no apply URL — skipping",
                        externalId, company.getDisplayName());
                skipped++;
                continue;
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (SmartRecruiters, {} detail calls, {} skipped pending retry, "
                        + "{} past the TTL)",
                results.size(), company.getDisplayName(), detailCalls, skipped, tooOld);
        return results;
    }

    /** The per-posting detail call. Package-private seam so tests can count calls. */
    String fetchDetail(String token, String externalId) {
        return get(BASE_URL + "/v1/companies/" + token + "/postings/" + externalId, null);
    }

    /**
     * Walk every page of the board. Stops on the first empty page rather than
     * trusting totalFound, which can shift under us while we page.
     */
    private List<JsonNode> fetchAllPages(String token) {
        List<JsonNode> all = new ArrayList<>();

        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * PAGE_SIZE;
            String body = get(listUrl(token, PAGE_SIZE, offset),
                    "SmartRecruiters board request failed for token " + token);

            List<JsonNode> pageItems;
            try {
                pageItems = parsePage(body, token);
            } catch (AtsFetchException e) {
                throw e;
            } catch (Exception e) {
                throw new AtsFetchException("Could not parse SmartRecruiters response for token " + token, e);
            }

            if (pageItems.isEmpty()) {
                return all;
            }
            all.addAll(pageItems);

            // A short page is the last page.
            if (pageItems.size() < PAGE_SIZE) {
                return all;
            }
        }

        log.warn("SmartRecruiters board {} exceeded {} pages — stopping at {} postings",
                token, MAX_PAGES, all.size());
        return all;
    }

    /** Package-private seam so fixture tests can exercise page parsing without HTTP. */
    List<JsonNode> parsePage(String body, String token) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode content = root.get("content");

        // An empty board returns content: [] — a MISSING or non-array content
        // means this isn't a board response at all, which is a failure.
        if (content == null || !content.isArray()) {
            throw new AtsFetchException("No content array in SmartRecruiters response for token " + token);
        }

        List<JsonNode> items = new ArrayList<>();
        content.forEach(items::add);
        return items;
    }

    /** Package-private seam for fixture tests. */
    Job mapListItem(JsonNode node, Company company, String externalId) {
        Job job = new Job();
        job.setCompany(company);
        job.setAtsPlatform(AtsPlatform.SMARTRECRUITERS);
        job.setExternalId(externalId);
        job.setTitle(node.path("name").asText(""));

        // fullLocation is the display string ("Bengaluru, Karnataka, India");
        // city is the fallback when a posting omits it.
        JsonNode location = node.path("location");
        String full = location.path("fullLocation").asText("");
        String city = location.path("city").asText("");
        String resolved = !full.isBlank() ? full : city;
        if (!resolved.isBlank()) {
            job.setLocation(resolved);
        }

        // releasedDate is a full ISO instant with millis, e.g.
        // "2026-08-28T11:20:45.290Z" — the honest posting date, and now also
        // the clock the six-day retention TTL runs on.
        String released = node.path("releasedDate").asText("");
        if (!released.isEmpty()) {
            try {
                job.setPlatformPostedAt(OffsetDateTime.parse(released).toInstant());
            } catch (Exception e) {
                log.debug("Could not parse releasedDate '{}' for job {}", released, externalId);
            }
        }

        job.setRawJson(node.toString());
        return job;
    }

    /** Package-private seam for fixture tests. */
    void applyDetail(String detailBody, Job job) throws Exception {
        JsonNode detail = objectMapper.readTree(detailBody);

        // postingUrl is the clean public link; applyUrl is the same page with
        // an ?oga=true tracking param, so prefer the former.
        String postingUrl = detail.path("postingUrl").asText("");
        String applyUrl = detail.path("applyUrl").asText("");
        job.setApplyUrl(!postingUrl.isBlank() ? postingUrl : applyUrl);

        // The body is split across jobAd.sections, each an HTML blob.
        JsonNode sections = detail.path("jobAd").path("sections");
        StringBuilder sb = new StringBuilder();
        for (String name : SECTIONS) {
            String html = sections.path(name).path("text").asText("");
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

        // The detail response is a superset of the list item — keep it instead.
        job.setRawJson(detail.toString());
    }

    /**
     * One GET, with the project's standard "transport failure and empty body
     * are both AtsFetchException" contract.
     *
     * @param failureMessage message for a transport failure; null lets the raw
     *                       exception propagate, which per-job detail calls
     *                       catch themselves.
     */
    private String get(String url, String failureMessage) {
        String body;
        try {
            body = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            if (failureMessage == null) {
                throw e;
            }
            throw new AtsFetchException(failureMessage, e);
        }

        if (body == null && failureMessage != null) {
            throw new AtsFetchException("Empty response from SmartRecruiters: " + url);
        }
        return body;
    }

    private String listUrl(String token, int limit, int offset) {
        return BASE_URL + "/v1/companies/" + token + "/postings?limit=" + limit + "&offset=" + offset;
    }
}
