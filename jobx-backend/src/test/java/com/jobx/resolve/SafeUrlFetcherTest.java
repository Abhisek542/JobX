package com.jobx.resolve;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SSRF guard.
 *
 * Sniffing a careers page makes the backend an HTTP client aimed at an address
 * the user chooses, so these tests are about what Jobx REFUSES to request. They
 * deliberately assert on the guard and on {@link SafeUrlFetcher#fetch} rejecting
 * before any request leaves — never on reaching a real host, so the suite stays
 * offline and deterministic.
 */
class SafeUrlFetcherTest {

    private final SafeUrlFetcher fetcher = new SafeUrlFetcher(1000, 1000);

    private void refuses(String url) {
        assertThrows(SafeUrlFetcher.UnsafeUrlException.class, () -> fetcher.fetch(url), url);
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

    @Test
    void allowsOrdinaryPublicAddresses() throws Exception {
        assertFalse(PrivateAddressGuard.isBlocked(InetAddress.getByName("8.8.8.8")));
        assertFalse(PrivateAddressGuard.isBlocked(InetAddress.getByName("1.1.1.1")));
        // 100.64.0.0/10 is CGNAT; 100.128.x is ordinary public space
        assertFalse(PrivateAddressGuard.isBlocked(InetAddress.getByName("100.128.0.1")));
    }
}
