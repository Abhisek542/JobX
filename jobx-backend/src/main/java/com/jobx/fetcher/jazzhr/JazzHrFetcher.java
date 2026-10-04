package com.jobx.fetcher.jazzhr;

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
import com.jobx.fetcher.HtmlPage;
import com.jobx.fetcher.JsonLd;
import com.jobx.fetcher.PastTtlMemo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetcher for JazzHR — VERIFIED live against Brennan Center (2026-10-04).
 * HTML TIER: there is no public JSON API, so the board page is parsed with jsoup.
 *
 * TWO-CALL DESIGN, like Workable:
 *   List:   GET https://{sub}.applytojob.com/apply
 *           → server-rendered li.list-group-item cards: title, link
 *             /apply/{code}/{slug}, location. The whole board is one page
 *             (122 cards on mra, no pagination). No dates, no descriptions.
 *   Detail: GET https://{sub}.applytojob.com/apply/{code}
 *           → JSON-LD JobPosting: description (HTML), datePosted (date-only),
 *             jobLocation. The slug after the code is cosmetic.
 *
 * Every way a board can be "not there" is a failure, never an empty list:
 *  - an unknown subdomain answers 302 → info.jazzhr.com/job-seekers.html. Any
 *    3xx counts; the target has moved before (it used to be jazzhr.com/job-seekers);
 *  - a cancelled account answers 200 with {@code <title>JazzHR - Inactive Career
 *    Page} — verified live on the jazzhr subdomain itself;
 *  - a 200 page with zero cards and no "no open positions" marker means the
 *    layout changed under us. Real empty boards say "There are no open
 *    positions at this time." (verified on getinflow).
 *
 * The subdomain token is validated through {@link BoardTokens} before any
 * request, and a detail URL is always rebuilt from that host plus a code
 * matched by {@link #CODE} — a link scraped off the page is never requested.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JazzHrFetcher implements AtsFetcher {

    /** The posting code in a card link on the board's own host: /apply/{code}/... */
    private static final Pattern CODE = Pattern.compile("^https://([a-z0-9-]+)\\.applytojob\\.com/apply/([A-Za-z0-9]{6,20})(?:/|$)",
            Pattern.CASE_INSENSITIVE);

    static final String EMPTY_MARKER = "There are no open positions at this time";
    static final String INACTIVE_MARKER = "Inactive Career Page";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    /** Stale postings already paid for once — see {@link PastTtlMemo}. */
    private final PastTtlMemo pastTtl = new PastTtlMemo();

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.JAZZHR;
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        String sub = BoardTokens.requireSubdomainLabel(company.getBoardToken());
        log.info("Fetching JazzHR board: {} ({})", company.getDisplayName(), sub);
        String html = readBoard(sub);
        return parseList(html, company, sub, filter, true);
    }

    @Override
    public BoardPreview previewBoard(Company company) {
        String sub = BoardTokens.requireSubdomainLabel(company.getBoardToken());
        return parsePreview(readBoard(sub), sub);
    }

    /** The board page body, or a throw for every shape of "this board isn't there". */
    private String readBoard(String sub) {
        HtmlPage page = fetchPage(host(sub) + "/apply");
        if (page.isRedirect()) {
            throw new AtsFetchException("JazzHR board '" + sub + "' does not exist (redirected to "
                    + page.location() + ")");
        }
        if (!page.isOk()) {
            throw new AtsFetchException("JazzHR board '" + sub + "' answered HTTP " + page.status());
        }
        return page.body();
    }

    /** Package-private seam so tests can stand in for HTTP. */
    HtmlPage fetchPage(String url) {
        return HtmlPage.get(webClientBuilder, url);
    }

    private static String host(String sub) {
        return "https://" + sub + ".applytojob.com";
    }

    /** One card on the board page. */
    record Card(String code, String title, String location) {
    }

    /**
     * The board's cards, after the dead-board checks. Empty only when the page
     * carries the platform's own "no open positions" marker.
     */
    List<Card> parseCards(Document document, String sub) {
        if (document.title().contains(INACTIVE_MARKER)) {
            throw new AtsFetchException("JazzHR career page '" + sub + "' is inactive");
        }

        List<Card> cards = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element item : document.select("li.list-group-item")) {
            Element link = item.selectFirst(".list-group-item-heading a[href]");
            if (link == null) {
                continue;
            }
            Matcher matcher = CODE.matcher(link.attr("href"));
            // A link off the board's own host is not one of its postings.
            if (!matcher.find() || !matcher.group(1).equalsIgnoreCase(sub)) {
                continue;
            }
            String code = matcher.group(2);
            if (!seen.add(code)) {
                continue;
            }
            // The icon's own <li>; "li:has(...)" would match the card itself.
            Element icon = item.selectFirst("i.fa-map-marker");
            String location = icon == null || icon.parent() == null ? "" : icon.parent().text().trim();
            cards.add(new Card(code, link.text().trim(), location.isEmpty() ? null : location));
        }

        if (cards.isEmpty() && !document.text().contains(EMPTY_MARKER)) {
            throw new AtsFetchException("JazzHR board '" + sub
                    + "' returned a page with no job cards and no empty-board marker — layout changed?");
        }
        return cards;
    }

    /**
     * Package-private seam for fixture tests. fetchDetails=false covers the
     * list mapping alone.
     */
    List<Job> parseList(String html, Company company, String sub, FetchFilter filter, boolean fetchDetails) {
        List<Job> results = new ArrayList<>();
        int detailCalls = 0;
        int skipped = 0;
        int tooOld = 0;

        for (Card card : parseCards(Jsoup.parse(html), sub)) {
            // N+1 guard: the list has no dates, so a known id is the only thing
            // that can be skipped before paying for the detail page.
            if (filter.isKnown(card.code()) || pastTtl.contains(sub, card.code())) {
                continue;
            }

            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.JAZZHR);
            job.setExternalId(card.code());
            job.setTitle(card.title());
            job.setApplyUrl(host(sub) + "/apply/" + card.code());
            job.setLocation(card.location());

            if (fetchDetails) {
                // Own try/catch per job — one bad detail call must not kill the batch.
                //
                // On failure the job is SKIPPED, not emitted list-only. The list
                // carries no description at all, and because the N+1 guard above
                // skips anything already stored, a job persisted with a null
                // description would never be revisited. Skipping leaves it
                // absent, so the next cycle retries it (the Workable rule).
                String detailHtml;
                try {
                    detailHtml = fetchDetail(sub, card.code());
                    detailCalls++;
                } catch (Exception e) {
                    log.warn("JazzHR detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                            card.code(), company.getDisplayName(), e.getMessage());
                    skipped++;
                    continue;
                }

                if (!applyDetail(detailHtml, job)) {
                    log.warn("JazzHR detail for {} ({}) had no JobPosting and no description — skipping, will retry next cycle",
                            card.code(), company.getDisplayName());
                    skipped++;
                    continue;
                }

                // datePosted is DAY precision, so judge by the END of that day
                // (the Workable rule): midnight would make a posting look up to
                // 24h older than it is. The list has no date, so a too-old
                // posting costs this one detail call the first time it is seen,
                // and the memo makes sure it is only the first time.
                if (job.getPlatformPostedAt() != null
                        && filter.isTooOld(job.getPlatformPostedAt().plusSeconds(86_400))) {
                    pastTtl.remember(sub, card.code());
                    tooOld++;
                    continue;
                }
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (JazzHR, {} detail calls, {} skipped pending retry, "
                        + "{} past the TTL)",
                results.size(), company.getDisplayName(), detailCalls, skipped, tooOld);
        return results;
    }

    /**
     * The per-posting detail call. Package-private seam so tests can count calls.
     * A non-2xx (a posting closed between list and detail) throws, which the
     * caller turns into skip-and-retry.
     */
    String fetchDetail(String sub, String code) {
        HtmlPage page = fetchPage(host(sub) + "/apply/" + code);
        if (!page.isOk()) {
            throw new AtsFetchException("JazzHR posting " + code + " answered HTTP " + page.status());
        }
        return page.body();
    }

    /**
     * Fills description, date and (when the card had none) location from the
     * page's JSON-LD JobPosting. Package-private seam for fixture tests.
     *
     * Not every posting has one: 2 of Brennan Center's 21 carry only the
     * Organization block (verified live 2026-10-04). Those fall back to the
     * rendered {@code #job-description} with no date — the TTL then runs from
     * first_seen_at. Skipping them instead would lose them for good AND cost a
     * detail call every cycle, since a skipped posting is never stored.
     *
     * @return false only when the page has neither JSON-LD nor a description
     */
    boolean applyDetail(String detailHtml, Job job) {
        Document document = Jsoup.parse(detailHtml);
        JsonNode posting = JsonLd.find(document, objectMapper, "JobPosting").orElse(null);
        if (posting == null) {
            Element body = document.selectFirst("#job-description");
            String description = body == null ? "" : body.text().trim();
            if (description.isEmpty()) {
                return false;
            }
            job.setDescription(description);
            ExperienceParser.parse(description, job);
            return true;
        }

        String html = posting.path("description").asText("");
        if (!html.isBlank()) {
            String description = Jsoup.parse(html).text().trim();
            if (!description.isEmpty()) {
                job.setDescription(description);
                ExperienceParser.parse(description, job);
            }
        }

        // date-only ("2026-10-01") → midnight UTC, the same as Workable's published_on
        String datePosted = posting.path("datePosted").asText("");
        if (!datePosted.isEmpty()) {
            try {
                job.setPlatformPostedAt(LocalDate.parse(datePosted.substring(0, Math.min(10, datePosted.length())))
                        .atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (Exception e) {
                log.debug("Could not parse datePosted '{}' for job {}", datePosted, job.getExternalId());
            }
        }

        if (job.getLocation() == null) {
            job.setLocation(JsonLd.location(posting));
        }

        job.setRawJson(posting.toString());
        return true;
    }

    /**
     * One page read, never a detail call. JazzHR names the company in an
     * Organization JSON-LD block on the board page; a real board with no
     * openings previews as 0 and the inherited validateBoard rejects it at
     * add time, the same accepted trade-off as every other platform.
     */
    BoardPreview parsePreview(String html, String sub) {
        Document document = Jsoup.parse(html);
        List<Card> cards = parseCards(document, sub);

        List<String> titles = cards.stream()
                .map(Card::title)
                .filter(title -> !title.isBlank())
                .limit(BoardPreview.SAMPLE_SIZE)
                .toList();

        String name = JsonLd.find(document, objectMapper, "Organization")
                .map(org -> org.path("name").asText("").trim())
                .filter(n -> !n.isEmpty())
                .orElseGet(() -> {
                    // "Brennan Center for Justice - Career Page"
                    String title = document.title();
                    int dash = title.lastIndexOf(" - Career Page");
                    return dash > 0 ? title.substring(0, dash).trim() : null;
                });

        return new BoardPreview(name, cards.size(), titles);
    }
}
