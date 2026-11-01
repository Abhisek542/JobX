package com.jobx.crawler;

import com.jobx.enums.CrawlMode;
import com.jobx.resolve.SafeUrlFetcher;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Reads one CUSTOM careers page end to end: robots.txt, a guarded fetch (or a
 * render), bot-wall detection and extraction. Shared by the add-company
 * resolver, {@code CustomPageFetcher} and {@code BoardRedetector}, so all three
 * reach the same verdict on the same page.
 *
 * Every network request goes through {@link SafeUrlFetcher} or the guarded
 * {@link BrowserRenderer}; nothing here uses the shared ATS WebClient. The URL
 * was typed by a user and is fetched on a schedule forever, so that is the
 * rule this whole class exists to keep.
 */
@Component
@Slf4j
public class CustomCrawler {

    /** What reading a page came to. */
    public enum Status {
        /** At least one posting was recognised. */
        JOBS,
        /** No postings, and the page says it has none open. A healthy empty board. */
        NO_OPENINGS,
        /** No postings and no "no openings" text: unreadable, or redesigned. Fail loudly. */
        NOTHING_RECOGNISED,
        /** A Cloudflare/Akamai-style challenge. Never bypassed. */
        BOT_PROTECTED,
        /** The site's robots.txt asks us not to fetch this page. */
        ROBOTS_DISALLOWED,
        /** The page could not be fetched (404, timeout, refused redirect…). */
        UNREACHABLE,
        /** The URL is not one JobX will request at all (non-public address, bad scheme). */
        UNSAFE
    }

    /**
     * @param pageUri      where the page was finally served from (after redirects)
     * @param canonicalUrl {@link CanonicalUrl} of pageUri: the board token
     * @param html         the HTML the verdict was reached on (static or rendered),
     *                     so a caller can sniff it for ATS links without refetching
     */
    public record Outcome(Status status, URI pageUri, String canonicalUrl, List<ExtractedJob> jobs,
                          CrawlMode mode, String siteName, String html) {

        public boolean isWatchable() {
            return status == Status.JOBS || status == Status.NO_OPENINGS;
        }

        /** A short reason for last_fetch_error and AtsFetchException messages. */
        public String describe() {
            String where = canonicalUrl != null ? canonicalUrl : String.valueOf(pageUri);
            return switch (status) {
                case JOBS -> jobs.size() + " job(s) on " + where;
                case NO_OPENINGS -> "no open roles listed on " + where;
                case NOTHING_RECOGNISED -> "no job listings recognised on " + where;
                case BOT_PROTECTED -> "careers page is behind bot protection: " + where;
                case ROBOTS_DISALLOWED -> "robots.txt asks crawlers not to read " + where;
                case UNREACHABLE -> "could not fetch careers page " + where;
                case UNSAFE -> "not a public web address: " + where;
            };
        }

        static Outcome of(Status status, URI pageUri) {
            return new Outcome(status, pageUri, pageUri == null ? null : CanonicalUrl.of(pageUri).orElse(null),
                    List.of(), null, null, null);
        }
    }

    private final SafeUrlFetcher safeUrlFetcher;
    private final RobotsTxt robotsTxt;
    private final BrowserRenderer browserRenderer;

    public CustomCrawler(SafeUrlFetcher safeUrlFetcher, RobotsTxt robotsTxt, BrowserRenderer browserRenderer) {
        this.safeUrlFetcher = safeUrlFetcher;
        this.robotsTxt = robotsTxt;
        this.browserRenderer = browserRenderer;
    }

    public boolean canRender() {
        return browserRenderer.isEnabled();
    }

    /**
     * Find out what a page is, trying the cheap static read first and a render
     * only when that recognised nothing. Used when a board is being added or
     * proposed. Never throws.
     */
    public Outcome discover(String url, BrowserRenderer.Lane lane) {
        Optional<URI> uri = parse(url);
        if (uri.isEmpty()) {
            return Outcome.of(Status.UNSAFE, null);
        }
        if (!robotsTxt.isAllowed(uri.get())) {
            return Outcome.of(Status.ROBOTS_DISALLOWED, uri.get());
        }
        Optional<SafeUrlFetcher.FetchedPage> page;
        try {
            page = safeUrlFetcher.fetchPage(url);
        } catch (SafeUrlFetcher.UnsafeUrlException e) {
            return Outcome.of(Status.UNSAFE, uri.get());
        }
        Outcome staticOutcome = page
                .map(p -> analyse(p.finalUri(), p.body(), CrawlMode.STATIC))
                .orElse(Outcome.of(Status.UNREACHABLE, uri.get()));
        return withRenderFallback(staticOutcome, url, lane);
    }

    /**
     * Same as {@link #discover} but starting from a page the caller already
     * fetched (the resolver's SNIFF step has the HTML in hand).
     */
    public Outcome discover(SafeUrlFetcher.FetchedPage page, BrowserRenderer.Lane lane) {
        return discover(page, null, lane);
    }

    /**
     * As above, reusing a render the caller already paid for (the resolver's
     * rendered SNIFF), so one resolve never renders the same page twice.
     * {@code rendered} may be null.
     */
    public Outcome discover(SafeUrlFetcher.FetchedPage page, BrowserRenderer.Rendered rendered,
                            BrowserRenderer.Lane lane) {
        if (!robotsTxt.isAllowed(page.finalUri())) {
            return Outcome.of(Status.ROBOTS_DISALLOWED, page.finalUri());
        }
        Outcome staticOutcome = analyse(page.finalUri(), page.body(), CrawlMode.STATIC);
        if (rendered == null) {
            return withRenderFallback(staticOutcome, page.finalUri().toString(), lane);
        }
        if (staticOutcome.status() != Status.NOTHING_RECOGNISED) {
            return staticOutcome;
        }
        return fromRender(staticOutcome, rendered);
    }

    /** robots.txt + the verdict on an already-fetched page, never rendered. */
    public Outcome discoverStatic(SafeUrlFetcher.FetchedPage page) {
        if (!robotsTxt.isAllowed(page.finalUri())) {
            return Outcome.of(Status.ROBOTS_DISALLOWED, page.finalUri());
        }
        return analyse(page.finalUri(), page.body(), CrawlMode.STATIC);
    }

    /**
     * Render a page for the resolver's rendered SNIFF. Empty when rendering is
     * off, refused, or fails. Never throws.
     */
    public Optional<BrowserRenderer.Rendered> render(URI pageUri, BrowserRenderer.Lane lane) {
        if (!browserRenderer.isEnabled() || !robotsTxt.isAllowed(pageUri)) {
            return Optional.empty();
        }
        return browserRenderer.render(pageUri.toString(), lane);
    }

    /**
     * Read a board the way it was added: a plain fetch for STATIC, a render for
     * RENDER. No fallback either way, so a page that stops yielding jobs shows
     * up as FAILED instead of quietly switching modes. Never throws.
     */
    public Outcome read(String url, CrawlMode mode, BrowserRenderer.Lane lane) {
        Optional<URI> uri = parse(url);
        if (uri.isEmpty()) {
            return Outcome.of(Status.UNSAFE, null);
        }
        if (!robotsTxt.isAllowed(uri.get())) {
            return Outcome.of(Status.ROBOTS_DISALLOWED, uri.get());
        }
        if (mode == CrawlMode.RENDER) {
            if (!browserRenderer.isEnabled()) {
                return Outcome.of(Status.UNREACHABLE, uri.get());
            }
            if (!BrowserRenderer.isAllowedTarget(uri.get().toString())) {
                return Outcome.of(Status.UNSAFE, uri.get());
            }
            return browserRenderer.render(url, lane)
                    .map(r -> analyse(r.finalUri(), r.html(), CrawlMode.RENDER))
                    .orElse(Outcome.of(Status.UNREACHABLE, uri.get()));
        }
        try {
            return safeUrlFetcher.fetchPage(url)
                    .map(p -> analyse(p.finalUri(), p.body(), CrawlMode.STATIC))
                    .orElse(Outcome.of(Status.UNREACHABLE, uri.get()));
        } catch (SafeUrlFetcher.UnsafeUrlException e) {
            return Outcome.of(Status.UNSAFE, uri.get());
        }
    }

    /**
     * A posting's detail page, fetched plainly (never rendered: that would cost
     * a browser per posting). Empty when robots.txt refuses it or it can't be
     * fetched.
     */
    public Optional<Document> fetchDetail(String url) {
        Optional<URI> uri = parse(url);
        if (uri.isEmpty() || !robotsTxt.isAllowed(uri.get())) {
            return Optional.empty();
        }
        try {
            return safeUrlFetcher.fetchPage(url).map(p -> Jsoup.parse(p.body(), p.finalUri().toString()));
        } catch (SafeUrlFetcher.UnsafeUrlException e) {
            return Optional.empty();
        }
    }

    /** The verdict on one page's HTML. Pure apart from logging; public for the benchmark and tests. */
    public static Outcome analyse(URI pageUri, String html, CrawlMode mode) {
        Document doc = Jsoup.parse(html == null ? "" : html, pageUri.toString());
        String canonical = CanonicalUrl.of(pageUri).orElse(null);
        String siteName = PageSignals.siteName(doc, pageUri);
        if (PageSignals.isBotChallenge(html, doc)) {
            return new Outcome(Status.BOT_PROTECTED, pageUri, canonical, List.of(), mode, siteName, html);
        }
        List<ExtractedJob> jobs = CareerPageExtractor.extract(pageUri, doc);
        if (!jobs.isEmpty()) {
            return new Outcome(Status.JOBS, pageUri, canonical, jobs, mode, siteName, html);
        }
        Status status = PageSignals.saysNoOpenings(doc) ? Status.NO_OPENINGS : Status.NOTHING_RECOGNISED;
        return new Outcome(status, pageUri, canonical, List.of(), mode, siteName, html);
    }

    /**
     * Render only when the static read recognised nothing; a page that already
     * showed jobs, said it has none, or put up a bot wall is not re-read in a
     * browser. The render's verdict wins only when it found something.
     */
    private Outcome withRenderFallback(Outcome staticOutcome, String url, BrowserRenderer.Lane lane) {
        if (staticOutcome.status() != Status.NOTHING_RECOGNISED && staticOutcome.status() != Status.UNREACHABLE) {
            return staticOutcome;
        }
        if (!browserRenderer.isEnabled()) {
            return staticOutcome;
        }
        String target = staticOutcome.pageUri() != null && staticOutcome.status() == Status.NOTHING_RECOGNISED
                ? staticOutcome.pageUri().toString()
                : url;
        Optional<BrowserRenderer.Rendered> rendered = browserRenderer.render(target, lane);
        if (rendered.isEmpty()) {
            return staticOutcome;
        }
        return fromRender(staticOutcome, rendered.get());
    }

    /** The rendered verdict where it found something, else the static one. */
    private Outcome fromRender(Outcome staticOutcome, BrowserRenderer.Rendered rendered) {
        URI finalUri = rendered.finalUri();
        if (!robotsTxt.isAllowed(finalUri)) {
            return Outcome.of(Status.ROBOTS_DISALLOWED, finalUri);
        }
        Outcome renderedOutcome = analyse(finalUri, rendered.html(), CrawlMode.RENDER);
        if (renderedOutcome.isWatchable() || renderedOutcome.status() == Status.BOT_PROTECTED) {
            return renderedOutcome;
        }
        return staticOutcome.status() == Status.UNREACHABLE ? renderedOutcome : staticOutcome;
    }

    private static Optional<URI> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String candidate = url.trim();
        if (!candidate.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            candidate = "https://" + candidate;
        }
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
