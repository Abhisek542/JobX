package com.jobx.fetcher.jobvite;

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
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetcher for Jobvite — VERIFIED live against Egnyte and Nutanix (2026-10-04).
 *
 * THE FIRST HTML-TIER FETCHER. Jobvite has no public JSON API; the career site
 * is server-rendered HTML, parsed with jsoup. HTML breaks silently when a site
 * changes its layout, so this fetcher FAILS LOUDLY: a 200 page with zero job
 * cards throws AtsFetchException unless it shows Jobvite's own "No results
 * found" message. A changed layout must show up as FAILED health, never as a
 * quietly empty feed. JazzHR (PR 5) follows the same rule.
 *
 *   List:   GET https://jobs.jobvite.com/{co}/search?p={N}
 *           → 50 cards a page: td.jv-job-list-name a[href=/{co}/job/{id}] and
 *             td.jv-job-list-location; "1-50 of 266" in .jv-pagination-text and
 *             an a.jv-pagination-next link on every page but the last.
 *           NOT /{co}/jobs: that page groups jobs by category and cuts long
 *           categories off behind "Show More" (117 of Nutanix's 266 jobs).
 *   Detail: GET https://jobs.jobvite.com/{co}/job/{id}
 *           → .jv-job-detail-description (HTML), and a JSON-LD JobPosting with
 *             datePosted (DATE-ONLY) and jobLocation[].
 *
 * Dead-board signals: an unknown company answers 302 → search.jobvite.com/?invalid=1,
 * and a removed job 303 → /careers/{co}/jobs?error=404. WebClient doesn't follow
 * redirects here, so any 3xx is checked for explicitly.
 *
 * N+1 mitigation: detail only for ids the FetchFilter doesn't know. The date is
 * on the detail page only, so the age gate runs after that request.
 */
@Component
@Slf4j
public class JobviteFetcher implements AtsFetcher {

    private static final String BASE_URL = "https://jobs.jobvite.com";
    /** Jobvite's own empty-state text, captured from a search that matched nothing. */
    static final String NO_OPENINGS_MARKER = "No results found";
    private static final Pattern TOTAL = Pattern.compile("of\\s+(\\d+)");
    private static final Pattern CAREERS_TITLE = Pattern.compile("(.+?)\\s+Careers", Pattern.CASE_INSENSITIVE);
    private static final int MAX_LOCATIONS = 3;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final int maxPages;

    public JobviteFetcher(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                          @Value("${jobx.fetch.jobvite.max-pages:20}") int maxPages) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.maxPages = maxPages;
    }

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.JOBVITE;
    }

    /** One job card from a list page. */
    record Card(String id, String title, String location, String url) {
    }

    /** What one list page holds. {@code total} is null when the page doesn't say. */
    record Page(List<Card> cards, boolean hasNext, Integer total, boolean saysNoOpenings,
                String displayName) {
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        String token = BoardTokens.requirePathSegment(company.getBoardToken());
        log.info("Fetching Jobvite board: {} ({})", company.getDisplayName(), token);

        // Cards in board order, deduped by id across pages.
        Map<String, Card> cards = new LinkedHashMap<>();
        for (int p = 0; p < maxPages; p++) {
            Page page = parsePage(body(listUrl(token, p), token), token);
            if (page.cards().isEmpty()) {
                if (p == 0) {
                    requireEmptyBoard(page, token);
                }
                break;
            }
            page.cards().forEach(card -> cards.putIfAbsent(card.id(), card));
            if (!page.hasNext()) {
                break;
            }
            if (p == maxPages - 1) {
                log.warn("Jobvite board {} has more than {} pages — only the first {} jobs were read",
                        token, maxPages, cards.size());
            }
        }

        return toJobs(new ArrayList<>(cards.values()), company, filter, true);
    }

    private static String listUrl(String token, int page) {
        return BASE_URL + "/" + token + "/search?p=" + page;
    }

    /**
     * The one HTTP call every request goes through. Package-private so tests
     * can serve fixture pages and redirects without a server.
     */
    ResponseEntity<String> get(String url) {
        return webClientBuilder.build()
                .get()
                .uri(url)
                .retrieve()
                .toEntity(String.class)
                .block();
    }

    /** A 2xx body, or an AtsFetchException. A redirect means the board (or job) is gone. */
    private String body(String url, String token) {
        ResponseEntity<String> response;
        try {
            response = get(url);
        } catch (Exception e) {
            throw new AtsFetchException("Jobvite request failed for token " + token, e);
        }
        if (response == null) {
            throw new AtsFetchException("Empty response from Jobvite for token " + token);
        }
        if (response.getStatusCode().is3xxRedirection()) {
            throw new AtsFetchException("Jobvite board '" + token + "' does not exist (redirected to "
                    + response.getHeaders().getLocation() + ")");
        }
        if (response.getBody() == null) {
            throw new AtsFetchException("Empty response from Jobvite for token " + token);
        }
        return response.getBody();
    }

    /**
     * Zero cards on the first page is only an empty board if Jobvite says so.
     * Otherwise the selectors no longer match the page, and that must fail.
     */
    private static void requireEmptyBoard(Page page, String token) {
        if (!page.saysNoOpenings()) {
            throw new AtsFetchException("Jobvite page for token " + token
                    + " has no job cards and no 'no openings' message — the page layout may have changed");
        }
    }

    // Package-private seam so fixture tests can exercise page parsing without HTTP.
    Page parsePage(String html, String token) {
        Document doc = Jsoup.parse(html, BASE_URL + "/");
        // Job links look like /{co}/job/{id}. The company part is compared
        // case-insensitively, since the page may not echo the token's casing.
        Pattern jobPath = Pattern.compile("^/" + Pattern.quote(token) + "/job/([A-Za-z0-9]+)$",
                Pattern.CASE_INSENSITIVE);

        List<Card> cards = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("td.jv-job-list-name a[href]")) {
            Matcher m = jobPath.matcher(link.attr("href"));
            if (!m.matches() || !seen.add(m.group(1))) {
                continue;
            }
            Element row = link.closest("tr");
            Element locationCell = row == null ? null : row.selectFirst("td.jv-job-list-location");
            String location = locationCell == null ? "" : locationCell.text().trim();
            cards.add(new Card(m.group(1), link.text().trim(),
                    location.isEmpty() ? null : location, link.absUrl("href")));
        }

        Integer total = null;
        Element totalText = doc.selectFirst(".jv-pagination-text");
        if (totalText != null) {
            Matcher m = TOTAL.matcher(totalText.text());
            if (m.find()) {
                total = Integer.parseInt(m.group(1));
            }
        }

        Element body = doc.selectFirst(".jv-page-body");
        boolean saysNoOpenings = body != null && body.text().contains(NO_OPENINGS_MARKER);

        // "<title>Egnyte Careers</title>" — the board's own name for the company.
        // The <title>, not the header: Nutanix replaces the standard header with
        // its own, but every board seen keeps the title. Anything not in the
        // "{Name} Careers" form is ignored rather than guessed at.
        String displayName = null;
        Matcher title = CAREERS_TITLE.matcher(doc.title().trim());
        if (title.matches()) {
            displayName = title.group(1).trim();
        }

        return new Page(cards, doc.selectFirst("a.jv-pagination-next") != null, total,
                saysNoOpenings, displayName);
    }

    /**
     * Package-private seam: cards to jobs, paying a detail page per unknown card.
     * fetchDetails=false lets tests cover the list mapping in isolation.
     */
    List<Job> toJobs(List<Card> cards, Company company, FetchFilter filter, boolean fetchDetails) {
        List<Job> results = new ArrayList<>();
        int detailCalls = 0;
        int skipped = 0;
        int tooOld = 0;

        for (Card card : cards) {
            // N+1 guard: a stored or tombstoned posting would be dropped by the
            // scheduler anyway, so its detail page would be pure waste.
            if (filter.isKnown(card.id())) {
                continue;
            }

            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.JOBVITE);
            job.setExternalId(card.id());
            job.setTitle(card.title());
            job.setApplyUrl(card.url());
            job.setLocation(card.location());

            if (fetchDetails) {
                // Own try/catch per job: one bad detail page must not kill the batch.
                // On failure the job is SKIPPED, not stored list-only. The list has
                // no description, and the N+1 guard skips anything already stored,
                // so a job saved without one could never be repaired. Skipping
                // leaves it absent, so the next cycle retries it.
                LocalDate posted;
                try {
                    String html = fetchDetail(company.getBoardToken(), card.url());
                    detailCalls++;
                    posted = applyDetail(html, job);
                } catch (Exception e) {
                    log.warn("Jobvite detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                            card.id(), company.getDisplayName(), e.getMessage());
                    skipped++;
                    continue;
                }

                // datePosted is DAY precision, so judge it by the END of that day.
                if (posted != null
                        && filter.isTooOld(posted.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
                    tooOld++;
                    continue;
                }
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (Jobvite, {} detail calls, {} skipped pending retry, "
                        + "{} past the TTL)",
                results.size(), company.getDisplayName(), detailCalls, skipped, tooOld);
        return results;
    }

    /**
     * The per-posting detail page. Package-private seam so tests can count calls.
     * The URL came from a card on a page we fetched, but it is rebuilt from the
     * validated token and the id rather than followed as given.
     */
    String fetchDetail(String token, String cardUrl) {
        String id = cardUrl.substring(cardUrl.lastIndexOf('/') + 1);
        if (!id.matches("[A-Za-z0-9]+")) {
            throw new AtsFetchException("Unexpected Jobvite job URL " + cardUrl);
        }
        return body(BASE_URL + "/" + token + "/job/" + id, token);
    }

    /**
     * Package-private seam for fixture tests.
     *
     * @return the posting date from the JSON-LD block, or null
     * @throws AtsFetchException when the page has no description at all — the
     *         caller skips the job rather than storing it blank
     */
    LocalDate applyDetail(String html, Job job) {
        Document doc = Jsoup.parse(html, BASE_URL + "/");
        JsonNode posting = jsonLdPosting(doc);

        String description = null;
        Element descriptionElement = doc.selectFirst(".jv-job-detail-description");
        if (descriptionElement != null) {
            // Drop the "Description" heading Jobvite puts above the text.
            descriptionElement.select("> h3").remove();
            description = descriptionElement.text().trim();
        }
        if ((description == null || description.isEmpty()) && posting != null) {
            String ldHtml = posting.path("description").asText("");
            description = ldHtml.isBlank() ? null : Jsoup.parse(ldHtml).text().trim();
        }
        if (description == null || description.isEmpty()) {
            throw new AtsFetchException("No description on Jobvite job page " + job.getExternalId());
        }
        job.setDescription(description);
        ExperienceParser.parse(description, job);

        LocalDate posted = null;
        if (posting != null) {
            String location = jsonLdLocation(posting.path("jobLocation"));
            if (location != null) {
                // Better than the list's "2 Locations" placeholder for multi-site jobs.
                job.setLocation(location);
            }

            // "2026-07-14" — date-only, stored as midnight UTC. Tolerate a full
            // timestamp by reading only the date part.
            String datePosted = posting.path("datePosted").asText("");
            if (datePosted.length() >= 10) {
                try {
                    posted = LocalDate.parse(datePosted.substring(0, 10));
                    job.setPlatformPostedAt(posted.atStartOfDay(ZoneOffset.UTC).toInstant());
                } catch (Exception e) {
                    log.debug("Could not parse datePosted '{}' for job {}", datePosted, job.getExternalId());
                }
            }
            job.setRawJson(posting.toString());
        }
        return posted;
    }

    /** The page's JSON-LD JobPosting, or null. A malformed block is ignored, not fatal. */
    private JsonNode jsonLdPosting(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = objectMapper.readTree(script.data());
                if (node != null && "JobPosting".equals(node.path("@type").asText())) {
                    return node;
                }
            } catch (Exception e) {
                log.debug("Ignoring unparseable JSON-LD block: {}", e.getMessage());
            }
        }
        return null;
    }

    /** "Mountain View, California", up to MAX_LOCATIONS places, then "+N more". */
    private static String jsonLdLocation(JsonNode jobLocation) {
        List<JsonNode> places = new ArrayList<>();
        if (jobLocation.isArray()) {
            jobLocation.forEach(places::add);
        } else if (jobLocation.isObject()) {
            places.add(jobLocation);
        }

        Set<String> labels = new LinkedHashSet<>();
        for (JsonNode place : places) {
            JsonNode address = place.path("address");
            List<String> parts = new ArrayList<>();
            for (String field : List.of("addressLocality", "addressRegion")) {
                String value = address.path(field).asText("").trim();
                if (!value.isEmpty()) {
                    parts.add(value);
                }
            }
            if (parts.isEmpty()) {
                String country = address.path("addressCountry").asText("").trim();
                if (!country.isEmpty()) {
                    parts.add(country);
                }
            }
            if (!parts.isEmpty()) {
                labels.add(String.join(", ", parts));
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        List<String> shown = labels.stream().limit(MAX_LOCATIONS).toList();
        int more = labels.size() - shown.size();
        return String.join("; ", shown) + (more > 0 ? " +" + more + " more" : "");
    }

    /**
     * One list page — never the detail pages fetch() reads. The count comes from
     * the page's "1-50 of 266" text, so it covers the whole board even though
     * only the first page's titles are read.
     */
    @Override
    public BoardPreview previewBoard(Company company) {
        String token = BoardTokens.requirePathSegment(company.getBoardToken());
        return parsePreview(body(listUrl(token, 0), token), token);
    }

    // Package-private seam so tests can exercise preview parsing without HTTP.
    BoardPreview parsePreview(String html, String token) {
        Page page = parsePage(html, token);
        if (page.cards().isEmpty()) {
            requireEmptyBoard(page, token);
            return new BoardPreview(page.displayName(), 0, List.of());
        }
        List<String> titles = page.cards().stream()
                .map(Card::title)
                .filter(title -> !title.isEmpty())
                .limit(BoardPreview.SAMPLE_SIZE)
                .toList();
        int count = page.total() != null ? page.total() : page.cards().size();
        return new BoardPreview(page.displayName(), count, titles);
    }
}
