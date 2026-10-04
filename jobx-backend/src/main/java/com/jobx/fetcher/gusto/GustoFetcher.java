package com.jobx.fetcher.gusto;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetcher for Gusto Recruiting — VERIFIED live against Sage Veterinary Imaging
 * (2026-10-04). HTML TIER, CLOUDFLARE-FRONTED.
 *
 * TWO-CALL DESIGN:
 *   List:   GET https://jobs.gusto.com/boards/{slug}
 *           → server-rendered cards, a[href=/postings/{name}-{uuid}] holding an
 *             h3 title and a location line. No dates, no descriptions.
 *             {@code <title>Careers at {Company}}. An unknown board is a 404.
 *   Detail: GET https://jobs.gusto.com/postings/{name}-{uuid}
 *           → JSON-LD JobPosting: description (HTML), datePosted (full ISO with
 *             offset), jobLocation.
 *
 * The token is the board slug, a company name plus a UUID
 * ({@code sage-veterinary-imaging-07e81227-…}), so it can never be guessed and
 * Gusto is never probed. A posting's externalId is its trailing UUID.
 *
 * BOT PROTECTION. jobs.gusto.com sits behind Cloudflare, which challenges some
 * clients (curl's default user agent is always challenged) and lets others
 * through: 20 of 20 requests with this app's default client were answered
 * with 200 in the 2026-10-04 gate check. A challenge — 403 with "Just a
 * moment…" — is a FAILED fetch, full stop. This fetcher never sends a browser
 * user agent, never retries around a challenge and never tries to solve one
 * (CLAUDE.md: never bypass bot protection). A challenge during the detail
 * calls stops the whole fetch rather than hammering the site.
 *
 * POLITENESS. robots.txt asks for {@code Crawl-delay: 1}; every request after
 * the first in a fetch waits {@link #CRAWL_DELAY_MS}. Gusto boards belong to
 * small businesses (a handful of roles), so this costs seconds, not minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GustoFetcher implements AtsFetcher {

    static final String BASE_URL = "https://jobs.gusto.com";
    static final long CRAWL_DELAY_MS = 1_000;

    /** A board slug: it becomes a path segment, so only what real slugs use. */
    private static final Pattern BOARD_SLUG = Pattern.compile("[a-z0-9-]{1,150}", Pattern.CASE_INSENSITIVE);

    /** A posting link as the board renders it: relative, on Gusto's own host, ending in a UUID. */
    private static final Pattern POSTING_HREF = Pattern.compile(
            "^/postings/([a-z0-9-]{0,160}?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))$");

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    /** Stale postings already paid for once — see {@link PastTtlMemo}. */
    private final PastTtlMemo pastTtl = new PastTtlMemo();

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.GUSTO;
    }

    /** A Cloudflare challenge: never retried, and stops a fetch in progress. */
    static final class ChallengedException extends AtsFetchException {
        ChallengedException(String url) {
            super("Gusto answered " + url + " with a Cloudflare challenge — not retried");
        }
    }

    static String requireBoardSlug(String token) {
        if (token == null || !BOARD_SLUG.matcher(token).matches()) {
            throw new AtsFetchException("Gusto board token '" + token + "' is not a valid board slug");
        }
        return token;
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        String slug = requireBoardSlug(company.getBoardToken());
        log.info("Fetching Gusto board: {} ({})", company.getDisplayName(), slug);
        return parseList(readBoard(slug), company, slug, filter, true);
    }

    @Override
    public BoardPreview previewBoard(Company company) {
        String slug = requireBoardSlug(company.getBoardToken());
        return parsePreview(readBoard(slug), slug);
    }

    private String readBoard(String slug) {
        String url = BASE_URL + "/boards/" + slug;
        HtmlPage page = checked(fetchPage(url), url);
        if (page.status() == 404 || page.isRedirect()) {
            throw new AtsFetchException("Gusto board '" + slug + "' does not exist (HTTP " + page.status() + ")");
        }
        if (!page.isOk()) {
            throw new AtsFetchException("Gusto board '" + slug + "' answered HTTP " + page.status());
        }
        return page.body();
    }

    /** Throws {@link ChallengedException} when the page is a Cloudflare challenge. */
    private static HtmlPage checked(HtmlPage page, String url) {
        if ((page.status() == 403 || page.status() == 503)
                && (page.body().contains("Just a moment") || page.body().contains("challenge-platform"))) {
            throw new ChallengedException(url);
        }
        return page;
    }

    /** Package-private seam so tests can stand in for HTTP. */
    HtmlPage fetchPage(String url) {
        return HtmlPage.get(webClientBuilder, url);
    }

    /** Honours robots.txt's Crawl-delay. Package-private seam so tests don't sleep. */
    void pause() {
        try {
            Thread.sleep(CRAWL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtsFetchException("Interrupted while pacing Gusto requests", e);
        }
    }

    /** One card on the board. */
    record Card(String postingSlug, String uuid, String title, String location) {
    }

    List<Card> parseCards(Document document, String slug) {
        List<Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element link : document.select("a[href^=/postings/]")) {
            Matcher matcher = POSTING_HREF.matcher(link.attr("href"));
            if (!matcher.matches() || !seen.add(matcher.group(2))) {
                continue;
            }
            Element heading = link.selectFirst("h3");
            String title = heading != null ? heading.text().trim() : link.text().trim();
            // One location per line ("Round Rock, TX<br>Spring, TX"); text()
            // would run them together, so join the lines instead.
            Element firstLine = link.selectFirst("p");
            String location = firstLine == null ? "" : String.join(" / ", firstLine.textNodes().stream()
                    .map(node -> node.text().trim())
                    .filter(line -> !line.isEmpty())
                    .toList());
            cards.add(new Card(matcher.group(1), matcher.group(2), title,
                    location.isEmpty() ? null : location));
        }

        // Fail loudly on a page with nothing recognisable on it. The one shape
        // read as a genuinely empty board is Gusto's own board page — its
        // "Careers at …" title with the job list rendered and empty. Not yet
        // seen live (no empty board was found during recon), so anything else
        // is treated as a layout change rather than a quiet board.
        if (cards.isEmpty()) {
            boolean boardPage = document.title().startsWith("Careers at ");
            Element list = document.selectFirst("ul.divide-y");
            if (!boardPage || list == null || !list.children().isEmpty()) {
                throw new AtsFetchException("Gusto board '" + slug
                        + "' returned a page with no recognisable postings — layout changed?");
            }
        }
        return cards;
    }

    /** Package-private seam for fixture tests. fetchDetails=false covers the list mapping alone. */
    List<Job> parseList(String html, Company company, String slug, FetchFilter filter, boolean fetchDetails) {
        List<Job> results = new ArrayList<>();
        int detailCalls = 0;
        int skipped = 0;
        int tooOld = 0;

        for (Card card : parseCards(Jsoup.parse(html), slug)) {
            // N+1 guard: the list has no dates, so a known or already-stale id
            // is the only thing that can be skipped before paying for detail.
            if (filter.isKnown(card.uuid()) || pastTtl.contains(slug, card.uuid())) {
                continue;
            }

            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.GUSTO);
            job.setExternalId(card.uuid());
            job.setTitle(card.title());
            job.setApplyUrl(BASE_URL + "/postings/" + card.postingSlug());
            job.setLocation(card.location());

            if (fetchDetails) {
                String detailHtml;
                try {
                    pause();
                    detailHtml = fetchDetail(card.postingSlug());
                    detailCalls++;
                } catch (ChallengedException e) {
                    // Stop here: more requests would only meet the same wall.
                    throw e;
                } catch (Exception e) {
                    // Skipped, not emitted list-only — the Workable rule: a job
                    // stored without a description would never be revisited.
                    log.warn("Gusto detail fetch failed for {} ({}) — skipping, will retry next cycle: {}",
                            card.uuid(), company.getDisplayName(), e.getMessage());
                    skipped++;
                    continue;
                }

                if (!applyDetail(detailHtml, job)) {
                    log.warn("Gusto detail for {} ({}) had no JobPosting and no description — skipping, will retry next cycle",
                            card.uuid(), company.getDisplayName());
                    skipped++;
                    continue;
                }

                if (filter.isTooOld(job.getPlatformPostedAt())) {
                    pastTtl.remember(slug, card.uuid());
                    tooOld++;
                    continue;
                }
            }

            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (Gusto, {} detail calls, {} skipped pending retry, "
                        + "{} past the TTL)",
                results.size(), company.getDisplayName(), detailCalls, skipped, tooOld);
        return results;
    }

    /**
     * The per-posting detail call. Package-private seam so tests can count
     * calls. The URL is rebuilt from the fixed host and a slug matched by
     * {@link #POSTING_HREF}; the scraped href itself is never requested.
     */
    String fetchDetail(String postingSlug) {
        String url = BASE_URL + "/postings/" + postingSlug;
        HtmlPage page = checked(fetchPage(url), url);
        if (!page.isOk()) {
            throw new AtsFetchException("Gusto posting " + postingSlug + " answered HTTP " + page.status());
        }
        return page.body();
    }

    /**
     * Fills description, date and (when the card had none) location from the
     * posting's JSON-LD. Package-private seam for fixture tests.
     *
     * Not every posting has one: 3 of Sage Veterinary Imaging's 14 — all
     * contractor roles — carry no JSON-LD at all (verified live 2026-10-04).
     * Those fall back to the rendered body, the {@code div.mt-8} blocks after
     * the {@code h1}, with no date — the TTL then runs from first_seen_at.
     * Skipping them instead would lose them for good AND cost a detail call
     * every cycle, since a skipped posting is never stored.
     *
     * @return false only when the page has neither JSON-LD nor a description
     */
    boolean applyDetail(String detailHtml, Job job) {
        Document document = Jsoup.parse(detailHtml);
        JsonNode posting = JsonLd.find(document, objectMapper, "JobPosting").orElse(null);
        if (posting == null) {
            String description = String.join("\n", document.select("div.max-w-prose > div.mt-8").eachText()).trim();
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

        // Full ISO with offset: "2026-05-14T13:01:59.000-07:00"
        String datePosted = posting.path("datePosted").asText("");
        if (!datePosted.isEmpty()) {
            try {
                job.setPlatformPostedAt(OffsetDateTime.parse(datePosted).toInstant());
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

    /** One page read, never a detail call. */
    BoardPreview parsePreview(String html, String slug) {
        Document document = Jsoup.parse(html);
        List<Card> cards = parseCards(document, slug);

        List<String> titles = cards.stream()
                .map(Card::title)
                .filter(title -> !title.isBlank())
                .limit(BoardPreview.SAMPLE_SIZE)
                .toList();

        String title = document.title();
        String name = title.startsWith("Careers at ") ? title.substring("Careers at ".length()).trim() : null;
        return new BoardPreview(name == null || name.isEmpty() ? null : name, cards.size(), titles);
    }
}
