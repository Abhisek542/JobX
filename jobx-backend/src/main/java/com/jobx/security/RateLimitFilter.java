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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window per-IP rate limit for the anonymous auth endpoints — the
 * brute-force control from V1_IMPROVEMENTS.md P0 (per OWASP Authentication
 * Cheat Sheet: generic failures + login throttling).
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

    private final int maxAttempts;
    private final long windowSeconds;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long id, AtomicInteger count) {
    }

    public RateLimitFilter(@Value("${jobx.auth.rate-limit.max-attempts:10}") int maxAttempts,
                           @Value("${jobx.auth.rate-limit.window-seconds:60}") long windowSeconds) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long windowId = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = request.getRemoteAddr() + "|" + request.getRequestURI();

        Window window = windows.compute(key, (k, existing) ->
                existing == null || existing.id() != windowId
                        ? new Window(windowId, new AtomicInteger())
                        : existing);

        if (window.count().incrementAndGet() > maxAttempts) {
            log.warn("Rate limit hit for {} {}", request.getRemoteAddr(), request.getRequestURI());
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(windowSeconds));
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
}
