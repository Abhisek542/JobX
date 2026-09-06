package com.jobx.resolve;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Fetches a careers page the user pasted, and nothing else.
 *
 * Deliberately does NOT use {@code WebClientConfig}'s shared builder. That one
 * is tuned for talking to five known ATS APIs on a scheduler thread — 60s
 * response timeout, 10 MB buffers, redirects wherever they lead. Here the
 * destination is chosen by whoever is typing, and a person is waiting on the
 * response, so the settings invert: short timeouts, a small buffer, and every
 * hop checked.
 *
 * The rules, in the order they matter:
 *
 *  1. http/https only. Anything else (file:, gopher:, jar:) is refused outright.
 *  2. Every address the host resolves to must be public — see
 *     {@link PrivateAddressGuard}. A host with one public and one private
 *     address is refused, not partially allowed.
 *  3. REDIRECTS ARE FOLLOWED BY HAND, and rule 2 runs again on each hop. This is
 *     the part that is easy to get wrong: leaving Netty's own redirect handling
 *     on would let a perfectly public URL bounce the request into 127.0.0.1 or a
 *     cloud metadata endpoint with no further check. Capped at three hops.
 *  4. Nothing is ever sent but a GET and a browser-ish User-Agent — no auth
 *     headers, no cookies, no request body.
 *  5. Only the exact URL given is fetched. There is no crawling.
 *
 * A failure is never an exception here: a careers page that 404s, times out or
 * refuses a server-side client is an ordinary outcome of resolution, and the
 * caller simply moves on to slug probing. Returns empty rather than throwing.
 *
 * Residual risk, accepted for v1: DNS rebinding. The name is resolved for the
 * check and resolved again by the connection, so a record with a one-second TTL
 * could in principle return a public address to the first lookup and a private
 * one to the second. Closing that needs connecting to a pinned IP with the
 * Host header set by hand, which is more machinery than this feature warrants
 * today.
 */
@Component
@Slf4j
public class SafeUrlFetcher {

    /** Enough hops for the usual apex → www → /careers chain, no more. */
    private static final int MAX_REDIRECTS = 3;

    /**
     * Careers pages are HTML. A cap well under the shared client's 10 MB keeps a
     * hostile or merely enormous response from being pulled into memory, and is
     * still several times larger than the biggest page seen in testing (Atlan,
     * 179 KB).
     */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /**
     * Some careers sites serve a bot-flavoured page — or a 403 — to a client
     * with no User-Agent. Identifying as a browser is about getting the same
     * HTML a user would see, not about hiding: the fetch happens once, on that
     * user's own request, for a URL they typed.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; Jobx/1.0; +https://github.com/jobx)";

    private final WebClient webClient;
    private final Duration responseTimeout;

    public SafeUrlFetcher(
            @Value("${jobx.resolve.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${jobx.resolve.response-timeout-ms:10000}") int responseTimeoutMs) {

        this.responseTimeout = Duration.ofMillis(responseTimeoutMs);

        HttpClient httpClient = HttpClient.create()
                // Rule 3: off on purpose. Redirects are followed in fetch(), so
                // that every hop goes through the address check.
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(this.responseTimeout)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(responseTimeoutMs, TimeUnit.MILLISECONDS)));

        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES))
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8")
                .build();
    }

    /**
     * The page body at {@code url}, or empty if it could not be fetched safely.
     *
     * @throws UnsafeUrlException only when the URL is one Jobx refuses to
     *                            request at all — a non-HTTP scheme or a
     *                            non-public address. That is worth telling the
     *                            user apart from "we looked and found nothing".
     */
    public Optional<String> fetch(String url) {
        URI current = parse(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requirePublicHttpTarget(current);

            Response response;
            try {
                response = exchange(current);
            } catch (Exception e) {
                log.debug("Careers page fetch failed for {}: {}", current, e.toString());
                return Optional.empty();
            }
            if (response == null) {
                return Optional.empty();
            }

            if (response.location() != null) {
                // Resolve relative Location headers against the URL we just asked.
                current = current.resolve(response.location());
                continue;
            }
            return Optional.ofNullable(response.body());
        }

        log.debug("Gave up on {} after {} redirects", url, MAX_REDIRECTS);
        return Optional.empty();
    }

    private Response exchange(URI uri) {
        return webClient.get()
                .uri(uri)
                .exchangeToMono(response -> {
                    // 3xx is handled here rather than by the client, so the next
                    // hop can be re-checked before it is requested.
                    if (response.statusCode().is3xxRedirection()) {
                        String location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                        return response.releaseBody()
                                .then(Mono.just(new Response(null, location)));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.empty());
                    }
                    return response.bodyToMono(String.class).map(body -> new Response(body, null));
                })
                .block(responseTimeout.plusSeconds(2));
    }

    private URI parse(String url) {
        String candidate = url == null ? "" : url.trim();
        if (candidate.isEmpty()) {
            throw new UnsafeUrlException("no URL given");
        }
        // Users paste "razorpay.com/jobs" as often as a full URL.
        if (!candidate.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            candidate = "https://" + candidate;
        }
        try {
            return URI.create(candidate);
        } catch (IllegalArgumentException e) {
            throw new UnsafeUrlException("that doesn't look like a web address");
        }
    }

    private void requirePublicHttpTarget(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new UnsafeUrlException("only http and https links can be checked");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeUrlException("that doesn't look like a web address");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("we couldn't find the site at " + host);
        }

        // Every address, not just the first: a host that resolves to one public
        // and one private address is not a host we will fetch.
        for (InetAddress address : addresses) {
            if (PrivateAddressGuard.isBlocked(address)) {
                log.info("Refused resolve fetch of {} — {} is not a public address", host, address);
                throw new UnsafeUrlException("that address is not on the public internet");
            }
        }
    }

    /** Body or redirect target — exactly one is set. */
    private record Response(String body, String location) {
    }

    /** The URL is one Jobx will not request at all. Surfaces to the user as a 400. */
    public static class UnsafeUrlException extends RuntimeException {
        public UnsafeUrlException(String message) {
            super(message);
        }
    }
}
