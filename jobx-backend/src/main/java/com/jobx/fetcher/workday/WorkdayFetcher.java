package com.jobx.fetcher.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.BoardTokens.WorkdayToken;
import com.jobx.fetcher.ExperienceParser;
import com.jobx.fetcher.FetchFilter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fetcher for Workday — VERIFIED live against Salesforce (2026-10-04).
 *
 * The board token is composite, {@code tenant/wdN/site}, and ends up in the
 * HOST, so it always goes through {@link WorkdayToken#parse} before any request
 * and every URL is built from its validated parts.
 *
 * TWO-CALL DESIGN — the list has no description and no real date:
 *   List:   POST https://{tenant}.{wdN}.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs
 *           body {"appliedFacets":{},"limit":20,"offset":N,"searchText":""}
 *           → { total, jobPostings: [{title, externalPath, locationsText, postedOn, bulletFields}] }
 *   Detail: GET  https://{host}/wday/cxs/{tenant}/{site}{externalPath}
 *           → jobPostingInfo { jobDescription (HTML), startDate (date-only),
 *             location, additionalLocations, jobReqId, … }
 *
 * Quirks from live recon, each of which shapes the code below:
 *  - limit above 20 is a 400, not a silent clamp. Pages of 20 it is.
 *  - total is only filled in at offset 0; every later page says 0.
 *  - the list is newest-first, so once whole pages are past the TTL the rest
 *    of the board is too. Salesforce lists ~1,500 postings but only ~300 are
 *    inside six days — paging stops there instead of walking all 76 pages.
 *  - Content-Type: application/json is required (without it: 500).
 *  - dead board: unknown site → 404, unknown tenant → 422. Both throw out of
 *    retrieve(), so a dead board is a FAILED fetch, never an empty one.
 *  - postedOn is relative text ("Posted 3 Days Ago") — see WorkdayPostedOn.
 */
@Component
@Slf4j
public class WorkdayFetcher implements AtsFetcher {

    /** The API's hard cap; asking for more is a 400. */
    static final int PAGE_SIZE = 20;

    /**
     * Pages in a row that are entirely past the TTL before we stop paging. Two,
     * not one: the newest-first order jitters at day boundaries ("Posted
     * Yesterday" after "Posted 2 Days Ago" on the live board).
     */
    private static final int STALE_PAGES_TO_STOP = 2;

    /** What an externalPath must look like before it goes into a URL on the board's host. */
    private static final Pattern EXTERNAL_PATH = Pattern.compile("/job/[^?#{}\\\\\\s]+");

    /** locationsText for a multi-location posting is a count, not a place. */
    private static final Pattern LOCATION_COUNT = Pattern.compile("\\d+\\s+Locations?",
            Pattern.CASE_INSENSITIVE);

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final int maxPages;

    public WorkdayFetcher(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                          @Value("${jobx.fetch.workday.max-pages:60}") int maxPages) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.maxPages = maxPages;
    }

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.WORKDAY;
    }

    /**
     * One list call at offset 0 — the only page that carries {@code total}, so
     * a 1,500-posting board reports honestly without paging. Workday's list has
     * no company name (the detail's hiringOrganization is a legal entity like
     * "621 Salesforce.com India Private Limited Hyderabad Branch"), so the
     * display name is left to the caller.
     */
    @Override
    public BoardPreview previewBoard(Company company) {
        WorkdayToken wd = WorkdayToken.parse(company.getBoardToken());
        return parsePreview(fetchPage(wd, 0, PAGE_SIZE), wd);
    }

    // Package-private seam so tests can exercise preview parsing without HTTP.
    BoardPreview parsePreview(String body, WorkdayToken wd) {
        JsonNode root = readList(body, wd);
        List<String> titles = new ArrayList<>();
        for (JsonNode node : root.get("jobPostings")) {
            String title = node.path("title").asText("");
            if (titles.size() < BoardPreview.SAMPLE_SIZE && !title.isBlank()) {
                titles.add(title.trim());
            }
        }
        return new BoardPreview(null, root.path("total").asInt(0), titles);
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        WorkdayToken wd = WorkdayToken.parse(company.getBoardToken());
        log.info("Fetching Workday board: {} ({})", company.getDisplayName(), wd);
        return translate(fetchAllPages(wd, filter, Instant.now()), company, filter);
    }

    /**
     * Walk the board newest-first until there is nothing left, or nothing left
     * worth having. Package-private seam so tests can drive the paging rules.
     */
    List<JsonNode> fetchAllPages(WorkdayToken wd, FetchFilter filter, Instant now) {
        List<JsonNode> all = new ArrayList<>();
        int total = -1;
        int stalePages = 0;

        for (int page = 0; page < maxPages; page++) {
            int offset = page * PAGE_SIZE;
            JsonNode root = readList(fetchPage(wd, offset, PAGE_SIZE), wd);
            if (page == 0) {
                total = root.path("total").asInt(0);
            }

            JsonNode postings = root.get("jobPostings");
            if (postings.isEmpty()) {
                return all;
            }
            boolean wholePageTooOld = true;
            for (JsonNode node : postings) {
                all.add(node);
                Optional<Instant> posted = WorkdayPostedOn.approxPostedAt(node.path("postedOn").asText(null), now);
                if (posted.isEmpty() || !filter.isTooOld(posted.get())) {
                    wholePageTooOld = false;
                }
            }

            if (offset + PAGE_SIZE >= total || postings.size() < PAGE_SIZE) {
                return all;
            }
            stalePages = wholePageTooOld ? stalePages + 1 : 0;
            if (stalePages >= STALE_PAGES_TO_STOP) {
                log.debug("Workday board {}: stopping after {} pages, the rest is past the TTL", wd, page + 1);
                return all;
            }
        }

        log.warn("Workday board {} hit the {}-page cap (jobx.fetch.workday.max-pages) — stopping at {} of {} postings",
                wd, maxPages, all.size(), total);
        return all;
    }

    /**
     * List rows → new jobs, paying a detail call only for postings that survive
     * the filter. Package-private seam so tests can count detail calls.
     */
    List<Job> translate(List<JsonNode> postings, Company company, FetchFilter filter) {
        WorkdayToken wd = WorkdayToken.parse(company.getBoardToken());
        Instant now = Instant.now();
        List<Job> results = new ArrayList<>();
        int detailCalls = 0;
        int skipped = 0;
        int tooOld = 0;
        Set<String> seen = new LinkedHashSet<>();

        for (JsonNode node : postings) {
            String externalPath = node.path("externalPath").asText("");
            // The path comes from the remote and is appended to a URL on the
            // board's host — anything that isn't a plain /job/... path is dropped.
            if (!EXTERNAL_PATH.matcher(externalPath).matches()
                    || externalPath.contains("..") || externalPath.contains("//")) {
                if (!externalPath.isEmpty()) {
                    log.warn("Workday board {}: ignoring posting with unexpected path '{}'", wd, externalPath);
                }
                continue;
            }
            // Postings can shift between pages while we walk them.
            if (!seen.add(externalPath)) {
                continue;
            }

            // N+1 guard: a stored or tombstoned posting would be dropped by the
            // scheduler anyway, so its detail call would be pure waste.
            if (filter.isKnown(externalPath)) {
                continue;
            }

            // Past the TTL by its relative "Posted N Days Ago" — skip it before
            // paying for the detail call that would give us the real date.
            Optional<Instant> approx = WorkdayPostedOn.approxPostedAt(node.path("postedOn").asText(null), now);
            if (approx.isPresent() && filter.isTooOld(approx.get())) {
                tooOld++;
                continue;
            }

            Job job = mapListItem(node, company, wd, externalPath);
            if (job.getTitle().isBlank()) {
                continue;
            }

            String detailBody;
            try {
                detailBody = fetchDetail(wd, externalPath);
                detailCalls++;
            } catch (Exception e) {
                // Skipped rather than emitted description-less, for the reason
                // Workable documents: the N+1 guard above keys off "does a row
                // exist", so a job persisted without its description would never
                // be revisited and would stay permanently unscoreable. Absent
                // means the next cycle retries it.
                log.warn("Workday detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                        externalPath, company.getDisplayName(), e.getMessage());
                skipped++;
                continue;
            }

            if (detailBody == null) {
                log.warn("Workday detail was empty for {} ({}) — skipping, will retry next cycle",
                        externalPath, company.getDisplayName());
                skipped++;
                continue;
            }

            try {
                applyDetail(detailBody, job);
            } catch (Exception e) {
                log.warn("Could not parse Workday detail for {} ({}) — skipping: {}",
                        externalPath, company.getDisplayName(), e.getMessage());
                skipped++;
                continue;
            }

            // The real date is date-only, read as midnight UTC — the same clock
            // the scheduler judges by, so drop it here rather than hand it back.
            // Rare: the list text is on that clock too, and only disagrees by
            // the board's own time zone (a US board's "today" is a UTC yesterday).
            if (filter.isTooOld(job.getPlatformPostedAt())) {
                tooOld++;
                continue;
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (Workday, {} listed, {} detail calls, {} skipped pending retry, "
                        + "{} past the TTL)",
                results.size(), company.getDisplayName(), postings.size(), detailCalls, skipped, tooOld);
        return results;
    }

    /** Package-private seam for fixture tests. */
    Job mapListItem(JsonNode node, Company company, WorkdayToken wd, String externalPath) {
        Job job = new Job();
        job.setCompany(company);
        job.setAtsPlatform(AtsPlatform.WORKDAY);
        job.setExternalId(externalPath);
        job.setTitle(node.path("title").asText("").trim());
        job.setApplyUrl("https://" + wd.host() + "/" + wd.site() + externalPath);

        String locations = node.path("locationsText").asText("").trim();
        if (!locations.isEmpty() && !LOCATION_COUNT.matcher(locations).matches()) {
            job.setLocation(locations);
        }
        job.setRawJson(node.toString());
        return job;
    }

    /** Package-private seam for fixture tests. */
    void applyDetail(String detailBody, Job job) throws Exception {
        JsonNode info = objectMapper.readTree(detailBody).path("jobPostingInfo");
        if (!info.isObject()) {
            throw new AtsFetchException("No jobPostingInfo in Workday detail for " + job.getExternalId());
        }

        String html = info.path("jobDescription").asText("");
        if (!html.isBlank()) {
            String description = Jsoup.parse(html).text().trim();
            if (!description.isEmpty()) {
                job.setDescription(description);
                ExperienceParser.parse(description, job);
            }
        }

        // startDate is the posting date, date-only ("2026-10-03").
        String startDate = info.path("startDate").asText("");
        if (!startDate.isEmpty()) {
            try {
                job.setPlatformPostedAt(LocalDate.parse(startDate).atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (Exception e) {
                log.debug("Could not parse startDate '{}' for job {}", startDate, job.getExternalId());
            }
        }

        // The detail names every location; the list only says "3 Locations".
        List<String> places = new ArrayList<>();
        String primary = info.path("location").asText("").trim();
        if (!primary.isEmpty()) {
            places.add(primary);
        }
        for (JsonNode extra : info.path("additionalLocations")) {
            String place = extra.asText("").trim();
            if (!place.isEmpty() && !places.contains(place)) {
                places.add(place);
            }
        }
        if (!places.isEmpty()) {
            job.setLocation(String.join("; ", places));
        }

        // jobPostingInfo only — the rest of the detail is "similarJobs" noise.
        job.setRawJson(info.toString());
    }

    /** One list page. Package-private seam so tests can drive paging without HTTP. */
    String fetchPage(WorkdayToken wd, int offset, int limit) {
        String url = "https://" + wd.host() + "/wday/cxs/" + wd.tenant() + "/" + wd.site() + "/jobs";
        String body = "{\"appliedFacets\":{},\"limit\":" + limit + ",\"offset\":" + offset + ",\"searchText\":\"\"}";
        String response;
        try {
            response = webClientBuilder.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            throw new AtsFetchException("Workday board request failed for token " + wd, e);
        }
        if (response == null) {
            throw new AtsFetchException("Empty response from Workday for token " + wd);
        }
        return response;
    }

    /** The per-posting detail call. Package-private seam so tests can count calls. */
    String fetchDetail(WorkdayToken wd, String externalPath) {
        return webClientBuilder.build()
                .get()
                .uri("https://" + wd.host() + "/wday/cxs/" + wd.tenant() + "/" + wd.site() + externalPath)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** A list page, or AtsFetchException — a missing jobPostings array means this isn't a board. */
    private JsonNode readList(String body, WorkdayToken wd) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AtsFetchException("Could not parse Workday response for token " + wd, e);
        }
        JsonNode postings = root.get("jobPostings");
        if (postings == null || !postings.isArray()) {
            throw new AtsFetchException("No jobPostings array in Workday response for token " + wd);
        }
        return root;
    }
}
