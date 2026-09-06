package com.jobx.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window per-IP rate limit for the endpoints that are worth abusing.
 *
 * Two of them, for different reasons:
 *
 *  - {@code /auth/*} — the brute-force control from V1_IMPROVEMENTS.md P0 (per
 *    OWASP Authentication Cheat Sheet: generic failures + login throttling).
 *  - {@code /watchlist/resolve} — add-company resolution fetches a careers page
 *    and probes ATS APIs on the caller's behalf, so an unlimited caller could
 *    turn Jobx into a scanner pointed at somebody else's infrastructure, and
 *    spend the ATS vendors' goodwill doing it. The budget is looser than auth's
 *    because a person genuinely retypes a company name a few times.
 *
 * In-memory on purpose: single-instance v1, no Redis. The window map is keyed
 * by ip|path and pruned opportunistically, so memory is bounded by distinct
 * client IPs per window.
 *
 * NOTE: uses the socket address. If v1 ever deploys behind a reverse proxy,
 * derive the client IP from X-Forwarded-For (trusting only the proxy) or all
 * users share the proxy's budget.
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /** A path prefix and what it costs to be over budget on it. */
    private record Budget(String prefix, int maxAttempts, long windowSeconds) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final List<Budget> budgets;

    private record Window(long id, AtomicInteger count) {
    }

    public RateLimitFilter(@Value("${jobx.auth.rate-limit.max-attempts:10}") int authMaxAttempts,
                           @Value("${jobx.auth.rate-limit.window-seconds:60}") long authWindowSeconds,
                           @Value("${jobx.resolve.rate-limit.max-attempts:20}") int resolveMaxAttempts,
                           @Value("${jobx.resolve.rate-limit.window-seconds:60}") long resolveWindowSeconds) {
        // Most specific prefix first — matching stops at the first hit.
        this.budgets = List.of(
                new Budget("/watchlist/resolve", resolveMaxAttempts, resolveWindowSeconds),
                new Budget("/auth/", authMaxAttempts, authWindowSeconds));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflights aren't login attempts — never spend a user's budget on
        // one, or the browser's own probe can lock them out of the real request.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return budgetFor(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Budget budget = budgetFor(request.getRequestURI());
        if (budget == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long windowId = System.currentTimeMillis() / 1000 / budget.windowSeconds();
        String key = request.getRemoteAddr() + "|" + request.getRequestURI();

        Window window = windows.compute(key, (k, existing) ->
                existing == null || existing.id() != windowId
                        ? new Window(windowId, new AtomicInteger())
                        : existing);

        if (window.count().incrementAndGet() > budget.maxAttempts()) {
            log.warn("Rate limit hit for {} {}", request.getRemoteAddr(), request.getRequestURI());
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(budget.windowSeconds()));
            // Same shape as ApiError — keep in sync with GlobalExceptionHandler
            response.getWriter().write(
                    "{\"status\":429,\"code\":\"rate_limited\",\"detail\":\"too many attempts, try again shortly\"}");
            return;
        }

        // Opportunistic pruning of expired windows so the map can't grow forever
        if (windows.size() > 10_000) {
            windows.values().removeIf(w -> w.id() != windowId);
        }

        filterChain.doFilter(request, response);
    }

    private Budget budgetFor(String uri) {
        if (uri == null) {
            return null;
        }
        for (Budget budget : budgets) {
            if (uri.startsWith(budget.prefix())) {
                return budget;
            }
        }
        return null;
    }
}
