package com.jobx.resolve;

import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetcherRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Asks several ATS boards "does this token exist, and does it have live roles?"
 * at once, and reports the ones that do.
 *
 * THE RULE THIS CLASS EXISTS TO ENFORCE: a board counts only if it has at least
 * one live posting. Two of the five platforms answer a token that was never
 * theirs with a cheerful 200 —
 * {@code apply.workable.com/api/v1/widget/accounts/razorpay} returns
 * {@code {"name":"Razorpay","description":null,"jobs":[]}}, and SmartRecruiters
 * returns {@code {"totalFound":0,"content":[]}} for anything. Verified live
 * against razorpay, groww, atlan, meesho and sprinto, none of which are Workable
 * customers. Because Workable echoes a plausible company NAME back, a resolver
 * that trusted names would confidently offer a user a board that will never
 * produce a single job. Job count is the only trustworthy signal, and
 * {@code previewBoard} is asked for it directly.
 *
 * It is still only a filter, not proof: it cannot tell that a "porter" board
 * belongs to a different Porter. That is what the user-facing confirmation card
 * is for.
 *
 * Runs on its own small pool because every fetcher blocks. Probing five
 * platforms across a few candidate spellings is a dozen-odd requests, and doing
 * them one after another on the request thread would make adding a company take
 * the better part of a minute.
 */
@Component
@Slf4j
public class BoardProbe {

    /** platform + token identified a board with live roles. */
    public record Hit(BoardRef ref, BoardPreview preview) {
    }

    private final FetcherRegistry fetcherRegistry;
    private final ExecutorService pool;
    private final Duration budget;
    private final Duration missTtl;

    /**
     * Tokens recently proven not to be boards. A user refining a search retypes
     * the same near-miss repeatedly, and there is no reason to spend an ATS
     * vendor's rate limit on a question already answered a minute ago. Only
     * misses are cached — a hit is cheap to re-verify and must always be shown
     * with a current job count.
     */
    private final Map<BoardRef, Instant> recentMisses = new ConcurrentHashMap<>();

    public BoardProbe(FetcherRegistry fetcherRegistry,
                      @Value("${jobx.resolve.probe-threads:8}") int threads,
                      @Value("${jobx.resolve.probe-budget-ms:9000}") long budgetMs,
                      @Value("${jobx.resolve.probe-miss-ttl-ms:120000}") long missTtlMs) {
        this.fetcherRegistry = fetcherRegistry;
        this.budget = Duration.ofMillis(budgetMs);
        this.missTtl = Duration.ofMillis(missTtlMs);
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "board-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * Probes every (platform, token) pair and returns those with live roles,
     * busiest board first.
     *
     * Ranking by job count is a tie-break, not a claim about correctness — when
     * two real boards match the same slug, both are returned and the user picks.
     */
    public List<Hit> probe(List<AtsPlatform> platforms, List<String> tokens) {
        List<BoardRef> refs = new ArrayList<>();
        for (String token : tokens) {
            for (AtsPlatform platform : platforms) {
                BoardRef ref = new BoardRef(platform, token);
                if (!isRecentMiss(ref)) {
                    refs.add(ref);
                }
            }
        }
        if (refs.isEmpty()) {
            return List.of();
        }

        log.debug("Probing {} board candidates", refs.size());
        Instant deadline = Instant.now().plus(budget);

        List<CompletableFuture<Hit>> futures = refs.stream()
                .map(ref -> CompletableFuture.supplyAsync(() -> probeOne(ref), pool))
                .toList();

        List<Hit> hits = new ArrayList<>();
        for (CompletableFuture<Hit> future : futures) {
            long remaining = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            try {
                Hit hit = future.get(remaining, TimeUnit.MILLISECONDS);
                if (hit != null) {
                    hits.add(hit);
                }
            } catch (Exception e) {
                // A probe that timed out or blew up is simply not a candidate.
                future.cancel(true);
            }
        }

        hits.sort(Comparator.comparingInt((Hit h) -> h.preview().jobCount()).reversed());

        // One board, one candidate. Some boards answer to more than one spelling
        // of their token — Ashby serves Atlan at both "atlan" and "Atlan" — and
        // offering the same company twice asks the user to choose between two
        // things that are not different. Keep the busiest spelling, which after
        // the sort above is the first one seen.
        Map<String, Hit> byBoard = new LinkedHashMap<>();
        for (Hit hit : hits) {
            byBoard.putIfAbsent(
                    hit.ref().platform() + "|" + hit.ref().token().toLowerCase(Locale.ROOT), hit);
        }
        return List.copyOf(byBoard.values());

    }

    /** One board, or null. Never throws: a failed probe is an answer, not an error. */
    private Hit probeOne(BoardRef ref) {
        Optional<AtsFetcher> fetcher = fetcherRegistry.getFetcher(ref.platform());
        if (fetcher.isEmpty()) {
            return null;
        }

        // A throwaway Company: previewBoard reads nothing but the token, and
        // nothing here is ever persisted.
        Company probe = new Company();
        probe.setAtsPlatform(ref.platform());
        probe.setBoardToken(ref.token());
        probe.setDisplayName(ref.token());

        try {
            BoardPreview preview = fetcher.get().previewBoard(probe);
            if (preview.jobCount() <= 0) {
                // The ghost-account case. Real board or not, a board with nothing
                // on it is indistinguishable from a wrong guess, so it is neither
                // offered nor remembered as a hit.
                recentMisses.put(ref, Instant.now());
                return null;
            }
            return new Hit(ref, preview);
        } catch (Exception e) {
            // The ordinary outcome — four of five platforms 404 an unknown token.
            recentMisses.put(ref, Instant.now());
            log.trace("No {} board at '{}': {}", ref.platform(), ref.token(), e.toString());
            return null;
        }
    }

    private boolean isRecentMiss(BoardRef ref) {
        Instant at = recentMisses.get(ref);
        if (at == null) {
            return false;
        }
        if (Duration.between(at, Instant.now()).compareTo(missTtl) > 0) {
            recentMisses.remove(ref);
            return false;
        }
        return true;
    }
}
