package com.jobx.resolve;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SSRF guard, and the redirect handling that guard depends on.
 *
 * Sniffing a careers page makes the backend an HTTP client aimed at an address
 * the user chooses, so these tests are about what Jobx REFUSES to request. They
 * deliberately assert on the guard and on {@link SafeUrlFetcher#fetch} rejecting
 * before any request leaves — never on reaching a real host, so the suite stays
 * offline and deterministic. {@link SafeUrlFetcher#nextHop} is package-private
 * for the same reason: the redirect step is the one piece of the hop loop worth
 * testing directly, and testing it through a socket would need a server.
 */
class SafeUrlFetcherTest {

    private final SafeUrlFetcher fetcher = new SafeUrlFetcher(1000, 1000);

    /** The page we just asked for, i.e. what a Location header resolves against. */
    private static final URI ASKED = URI.create("https://careers.example.com/jobs/eng");

    private void refuses(String url) {
        assertThrows(SafeUrlFetcher.UnsafeUrlException.class, () -> fetcher.fetch(url), url);
    }

    private void deadEnd(String location) {
        assertEquals(Optional.empty(), SafeUrlFetcher.nextHop(ASKED, location),
                "Location: [" + location + "]");
    }

    private void resolvesTo(String location, String expected) {
        assertEquals(Optional.of(URI.create(expected)), SafeUrlFetcher.nextHop(ASKED, location),
                "Location: [" + location + "]");
    }

    @Test
    void refusesLoopback() {
        refuses("http://127.0.0.1:8080/actuator");
        refuses("http://localhost:5432/");
        refuses("http://[::1]/");
    }

    /**
     * The one every SSRF write-up names: the cloud instance-metadata endpoint,
     * refused here as an ordinary link-local address rather than by name.
     */
    @Test
    void refusesCloudMetadata() throws Exception {
        refuses("http://169.254.169.254/latest/meta-data/");
        assertTrue(PrivateAddressGuard.isBlocked(InetAddress.getByName("169.254.169.254")));
    }

    @Test
    void refusesPrivateRanges() {
        refuses("http://10.0.0.5/");
        refuses("http://192.168.1.1/admin");
        refuses("http://172.16.4.4/");
    }

    @Test
    void refusesNonHttpSchemes() {
        refuses("file:///etc/passwd");
        refuses("ftp://example.com/x");
        refuses("gopher://example.com/");
    }

    @Test
    void refusesEmptyAndUnparseableInput() {
        refuses("");
        refuses("   ");
        refuses("http://");
    }

    @Test
    void refusesAHostThatDoesNotResolve() {
        refuses("https://this-host-does-not-exist.jobx-invalid/");
    }

    /**
     * Java's own predicates leave real gaps, which is the reason
     * PrivateAddressGuard exists rather than a one-line check.
     */
    @Test
    void guardCoversWhatJavaMisses() throws Exception {
        // isSiteLocalAddress on IPv6 tests the deprecated fec0::/10, not fc00::/7
        assertTrue(PrivateAddressGuard.isBlocked(InetAddress.getByName("fd00::1")));
        // isAnyLocalAddress is only 0.0.0.0, not the rest of 0.0.0.0/8
        assertTrue(PrivateAddressGuard.isBlocked(InetAddress.getByName("0.1.2.3")));
        // carrier-grade NAT is neither loopback nor site-local
        assertTrue(PrivateAddressGuard.isBlocked(InetAddress.getByName("100.64.0.1")));
        assertTrue(PrivateAddressGuard.isBlocked(null));
    }

    /**
     * BUG_REPORT #8. Every value here made {@code URI.resolve} throw
     * {@link IllegalArgumentException}, which escaped fetch() and reached the
     * catch-all handler as a 500 — for a careers site's bad header, on a URL the
     * user typed correctly. A site we can't follow is an ordinary dead end.
     */
    @Test
    void unusableLocationHeaderIsADeadEnd() {
        deadEnd("/a b/c");                    // raw space in the path
        deadEnd("http://exa mple.com/x");     // raw space in the authority
        deadEnd("h ttp://example.com/x");     // raw space in the scheme
        deadEnd("://nope");                   // no scheme name
        deadEnd("%%");                        // malformed escape pair
        deadEnd("https://[bad/x");            // unclosed IPv6 bracket
        // Absent or blank: resolving these lands back on the page we are already
        // on, so following one costs MAX_REDIRECTS repeats of the same request.
        deadEnd(null);
        deadEnd("");
        deadEnd("   ");
    }

    /**
     * The other half of the fix: a redirect chain Jobx is supposed to follow
     * must keep working. Apex → www → /careers is the case MAX_REDIRECTS exists
     * for, and over-rejecting here would lose boards rather than 500 on them.
     */
    @Test
    void resolvesOrdinaryRedirectTargets() {
        resolvesTo("/careers", "https://careers.example.com/careers");
        resolvesTo("openings", "https://careers.example.com/jobs/openings");
        resolvesTo("https://boards.greenhouse.io/acme", "https://boards.greenhouse.io/acme");
        // Protocol-relative: inherits https from the page we asked, not http.
        resolvesTo("//jobs.example.com/x", "https://jobs.example.com/x");
        resolvesTo("/careers?src=x#open", "https://careers.example.com/careers?src=x#open");
        // Percent-encoded space is legal, unlike the raw one above.
        resolvesTo("/a%20b/c", "https://careers.example.com/a%20b/c");
        // Header values arrive with optional surrounding whitespace.
        resolvesTo("  /careers  ", "https://careers.example.com/careers");
    }

    @Test
    void allowsOrdinaryPublicAddresses() throws Exception {
        assertFalse(PrivateAddressGuard.isBlocked(InetAddress.getByName("8.8.8.8")));
        assertFalse(PrivateAddressGuard.isBlocked(InetAddress.getByName("1.1.1.1")));
        // 100.64.0.0/10 is CGNAT; 100.128.x is ordinary public space
        assertFalse(PrivateAddressGuard.isBlocked(InetAddress.getByName("100.128.0.1")));
    }
}
