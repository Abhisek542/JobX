package com.jobx.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.enums.CrawlMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How good is the crawler on real careers pages? (add-company PR E, "E-bench")
 *
 * Fixture tests prove the extractor does what we wrote; this measures whether
 * that is any good. Each directory under {@code src/test/resources/crawler-bench}
 * is a snapshot of a real page ({@code page.html}, captured with
 * {@link CrawlerBenchCapture}; for a JavaScript page it is the rendered DOM), its
 * URL ({@code source.txt}) and the true job list labelled BY HAND from the live
 * page ({@code expected.json}). Offline and fast, so it runs in every
 * {@code ./mvnw test}.
 *
 * Extracted items are matched to the truth by canonical URL, falling back to
 * the normalised title. Precision is the share of extracted items that are real
 * jobs; recall the share of real jobs found. Noise pages (a careers landing
 * page with no listing) have an empty truth, so anything extracted from them
 * counts against precision, which is the point: a false job in a feed is worse
 * than a missed one.
 *
 * The floors in {@code crawler-bench/floors.properties} were set from the
 * first measured baseline. They may be raised; lowering one needs an
 * explanation in the PR. Any change to the extractor heuristics must keep this
 * green, and a real site that breaks gets added to the corpus like a regression
 * test.
 */
class CrawlerBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record SiteResult(String site, int expected, int extracted, int matched,
                              String expectedStatus, CustomCrawler.Status status) {
        double precision() {
            return extracted == 0 ? 1.0 : (double) matched / extracted;
        }

        double recall() {
            return expected == 0 ? 1.0 : (double) matched / expected;
        }
    }

    @Test
    void crawlerMeetsThePrecisionAndRecallFloors() throws Exception {
        Path root = Path.of(CrawlerBenchmarkTest.class.getResource("/crawler-bench").toURI());
        List<Path> sites;
        try (Stream<Path> dirs = Files.list(root)) {
            sites = dirs.filter(Files::isDirectory).sorted().toList();
        }
        assertThat(sites).as("benchmark corpus").hasSizeGreaterThanOrEqualTo(15);

        List<SiteResult> results = new ArrayList<>();
        for (Path site : sites) {
            results.add(measure(site));
        }

        int expected = results.stream().mapToInt(SiteResult::expected).sum();
        int extracted = results.stream().mapToInt(SiteResult::extracted).sum();
        int matched = results.stream().mapToInt(SiteResult::matched).sum();
        double precision = extracted == 0 ? 1.0 : (double) matched / extracted;
        double recall = expected == 0 ? 1.0 : (double) matched / expected;

        StringBuilder table = new StringBuilder("\nCrawler benchmark\n");
        table.append(String.format("%-22s %8s %9s %7s %9s %7s  %s%n",
                "site", "expected", "extracted", "matched", "precision", "recall", "verdict"));
        for (SiteResult r : results) {
            table.append(String.format("%-22s %8d %9d %7d %9.2f %7.2f  %s%n",
                    r.site(), r.expected(), r.extracted(), r.matched(), r.precision(), r.recall(), r.status()));
        }
        table.append(String.format("%-22s %8d %9d %7d %9.3f %7.3f%n", "OVERALL", expected, extracted, matched,
                precision, recall));
        List<String> weak = results.stream().filter(r -> r.precision() < 0.8)
                .map(r -> r.site() + String.format(" (%.2f)", r.precision())).toList();
        if (!weak.isEmpty()) {
            table.append("Sites with precision < 0.8: ").append(String.join(", ", weak)).append('\n');
        }
        System.out.println(table);

        Properties floors = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("floors.properties"))) {
            floors.load(in);
        }
        assertThat(precision).as("overall precision" + table)
                .isGreaterThanOrEqualTo(Double.parseDouble(floors.getProperty("bench.min-precision")));
        assertThat(recall).as("overall recall" + table)
                .isGreaterThanOrEqualTo(Double.parseDouble(floors.getProperty("bench.min-recall")));

        // A page that says "no openings" must read as a healthy empty board,
        // never as FAILED: that is the line between "not hiring" and "redesigned".
        for (SiteResult r : results) {
            if (r.expectedStatus() != null) {
                assertThat(r.status().name()).as(r.site() + " verdict").isEqualTo(r.expectedStatus());
            }
        }
    }

    private SiteResult measure(Path site) throws IOException {
        String html = Files.readString(site.resolve("page.html"), StandardCharsets.UTF_8);
        URI pageUri = URI.create(Files.readAllLines(site.resolve("source.txt")).stream()
                .filter(line -> line.startsWith("url:"))
                .findFirst().orElseThrow().substring(4).trim());
        JsonNode truth = MAPPER.readTree(site.resolve("expected.json").toFile());

        Set<String> expectedUrls = new HashSet<>();
        Set<String> expectedTitles = new HashSet<>();
        for (JsonNode job : truth.path("jobs")) {
            CanonicalUrl.of(job.path("url").asText()).ifPresent(expectedUrls::add);
            expectedTitles.add(normalise(job.path("title").asText()));
        }

        CustomCrawler.Outcome outcome = CustomCrawler.analyse(pageUri, html, CrawlMode.STATIC);
        Set<String> unmatchedUrls = new HashSet<>(expectedUrls);
        Set<String> unmatchedTitles = new HashSet<>(expectedTitles);
        int matched = 0;
        for (ExtractedJob job : outcome.jobs()) {
            if (job.url() != null && unmatchedUrls.remove(job.url())) {
                matched++;
            } else if (unmatchedTitles.remove(normalise(job.title()))) {
                matched++;
            }
        }
        String expectedStatus = truth.hasNonNull("status") ? truth.get("status").asText() : null;
        return new SiteResult(site.getFileName().toString(), truth.path("jobs").size(),
                outcome.jobs().size(), matched, expectedStatus, outcome.status());
    }

    private static String normalise(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
