package com.jobx.crawler;

import com.jobx.resolve.SafeUrlFetcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Re-captures one benchmark site's snapshot when its markup changes. A tool,
 * not a test: run it from the IDE or with
 * {@code mvn test-compile exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=com.jobx.crawler.CrawlerBenchCapture -Dexec.args="site url [--render]"}.
 *
 * Writes {@code src/test/resources/crawler-bench/{site}/page.html} and
 * {@code source.txt}. It does NOT touch {@code expected.json}: the true job list
 * is labelled by hand from the live page, on purpose, so the benchmark can never
 * be "fixed" by re-labelling whatever the extractor happens to output.
 *
 * Fetches go through the same SafeUrlFetcher (and, with --render, the same
 * guarded BrowserRenderer) the app uses, and robots.txt is honoured. Inline
 * scripts and styles are dropped from the snapshot to keep the corpus small;
 * JSON-LD blocks and {@code <script src>} tags are kept, since the extractor
 * and the page signals read those.
 */
public final class CrawlerBenchCapture {

    private CrawlerBenchCapture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: CrawlerBenchCapture <site> <url> [--render]");
            System.exit(2);
        }
        String site = args[0];
        String url = args[1];
        boolean render = args.length > 2 && args[2].equals("--render");

        SafeUrlFetcher fetcher = new SafeUrlFetcher(5000, 15000);
        RobotsTxt robots = new RobotsTxt(fetcher);
        if (!robots.isAllowed(URI.create(url))) {
            System.err.println("robots.txt disallows " + url + " - not capturing it");
            System.exit(1);
        }

        URI finalUri;
        String html;
        if (render) {
            BrowserRenderer renderer = new BrowserRenderer(true, 20000, 8000, 60000, 1, 1);
            try {
                Optional<BrowserRenderer.Rendered> rendered = renderer.render(url, BrowserRenderer.Lane.INTERACTIVE);
                if (rendered.isEmpty()) {
                    System.err.println("render failed for " + url);
                    System.exit(1);
                }
                finalUri = rendered.get().finalUri();
                html = rendered.get().html();
            } finally {
                renderer.shutdown();
            }
        } else {
            Optional<SafeUrlFetcher.FetchedPage> page = fetcher.fetchPage(url);
            if (page.isEmpty()) {
                System.err.println("fetch failed for " + url);
                System.exit(1);
            }
            finalUri = page.get().finalUri();
            html = page.get().body();
        }

        Path dir = Path.of("src/test/resources/crawler-bench", site);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("page.html"), slim(html, finalUri), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("source.txt"),
                "url: " + finalUri + "\n"
                        + "requested: " + url + "\n"
                        + "captured: " + LocalDate.now() + "\n"
                        + "mode: " + (render ? "RENDER" : "STATIC") + "\n",
                StandardCharsets.UTF_8);
        System.out.println("captured " + site + " from " + finalUri + " -> " + dir);
    }

    /** The page minus inline script/style bodies, with a base href so links resolve offline. */
    static String slim(String html, URI finalUri) {
        Document doc = Jsoup.parse(html, finalUri.toString());
        for (Element script : doc.select("script")) {
            boolean jsonLd = script.attr("type").equalsIgnoreCase("application/ld+json");
            if (!jsonLd) {
                script.empty();
            }
        }
        doc.select("style, link[rel=preload], link[rel=prefetch], link[rel=modulepreload]").remove();
        doc.select("svg").forEach(Element::empty);
        doc.outputSettings().prettyPrint(false);
        return doc.outerHtml();
    }
}
