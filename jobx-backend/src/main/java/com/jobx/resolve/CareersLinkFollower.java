package com.jobx.resolve;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The one-hop follow behind URL-mode resolution: when the pasted careers page
 * names no board, fetch the few same-site careers links on it and let the
 * resolver sniff those instead.
 *
 * Bounded three ways, because every fetch here is to a URL a third-party page
 * chose:
 *  - ONE HOP. Pages fetched here are never searched for further links.
 *  - AT MOST {@code jobx.resolve.max-followed-links} (3) links per resolve,
 *    picked by {@link CareersLinkFinder}, same site only.
 *  - A WALL-CLOCK BUDGET ({@code jobx.resolve.follow-budget-ms}). Each fetch can
 *    take up to SafeUrlFetcher's response timeout, so three in a row could add
 *    half a minute to a request a person is waiting on. They run in parallel,
 *    and whatever hasn't answered by the deadline is dropped.
 *
 * Every fetch still goes through {@link SafeUrlFetcher}: http/https only, every
 * resolved address public, redirects re-checked per hop, the 2 MB cap. A link
 * the guard refuses is a dead end, never a 400: the page chose that link, not
 * the user, so blaming their input for it would be wrong (the same reasoning as
 * a refused redirect inside SafeUrlFetcher).
 */
@Component
@Slf4j
public class CareersLinkFollower {

    private final SafeUrlFetcher safeUrlFetcher;
    private final ExecutorService pool;
    private final int maxLinks;
    private final Duration budget;

    public CareersLinkFollower(SafeUrlFetcher safeUrlFetcher,
                               @Value("${jobx.resolve.max-followed-links:3}") int maxLinks,
                               @Value("${jobx.resolve.follow-budget-ms:8000}") long budgetMs,
                               @Value("${jobx.resolve.follow-threads:6}") int threads) {
        this.safeUrlFetcher = safeUrlFetcher;
        this.maxLinks = maxLinks;
        this.budget = Duration.ofMillis(budgetMs);
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "careers-follow");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * The pages one hop away from {@code landing} that could be fetched within
     * the budget, IN LINK ORDER (document order on the landing page), so which
     * board wins never depends on which server answered first. Never throws.
     */
    public List<SafeUrlFetcher.FetchedPage> follow(SafeUrlFetcher.FetchedPage landing) {
        List<String> links = CareersLinkFinder.find(landing.finalUri(), landing.body(), maxLinks);
        if (links.isEmpty()) {
            return List.of();
        }
        log.debug("Following {} careers link(s) from {}: {}", links.size(), landing.finalUri(), links);

        Instant deadline = Instant.now().plus(budget);
        List<CompletableFuture<Optional<SafeUrlFetcher.FetchedPage>>> futures = links.stream()
                .map(link -> CompletableFuture.supplyAsync(() -> fetchOne(link), pool))
                .toList();

        List<SafeUrlFetcher.FetchedPage> pages = new ArrayList<>();
        for (CompletableFuture<Optional<SafeUrlFetcher.FetchedPage>> future : futures) {
            long remaining = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            try {
                future.get(remaining, TimeUnit.MILLISECONDS).ifPresent(pages::add);
            } catch (Exception e) {
                // Timed out or blew up: simply not a page we can read.
                future.cancel(true);
            }
        }
        return pages;
    }

    private Optional<SafeUrlFetcher.FetchedPage> fetchOne(String link) {
        try {
            return safeUrlFetcher.fetchPage(link);
        } catch (SafeUrlFetcher.UnsafeUrlException e) {
            log.debug("Not following careers link {}: {}", link, e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Careers link fetch failed for {}: {}", link, e.toString());
            return Optional.empty();
        }
    }
}
