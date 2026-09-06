package com.jobx.resolve;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Decides whether an IP address is somewhere Jobx is allowed to send a
 * user-supplied URL.
 *
 * The add-company flow fetches a careers page the user pasted, which makes the
 * backend an HTTP client pointed at an attacker-chooseable address — the
 * classic SSRF shape. Everything that is not plainly on the public internet is
 * refused, rather than blocklisting known-sensitive targets one at a time.
 *
 * {@link InetAddress}'s own predicates cover most of it but leave real gaps,
 * which is why this class exists instead of a one-line check:
 *  - {@code isSiteLocalAddress} on IPv6 tests the deprecated {@code fec0::/10},
 *    not the {@code fc00::/7} unique-local range actually in use;
 *  - {@code isAnyLocalAddress} is only 0.0.0.0, not the rest of {@code 0.0.0.0/8};
 *  - carrier-grade NAT ({@code 100.64.0.0/10}) is neither loopback nor site-local
 *    but is not the public internet either.
 *
 * Cloud metadata endpoints (169.254.169.254 and friends) fall out of the
 * link-local rule rather than needing a name of their own.
 */
final class PrivateAddressGuard {

    private PrivateAddressGuard() {
    }

    static boolean isBlocked(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || address.isLinkLocalAddress()  // 169.254.0.0/16 (incl. cloud metadata), fe80::/10
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 0.0.0.0/8 "this network", and 100.64.0.0/10 carrier-grade NAT
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }

        if (address instanceof Inet6Address) {
            // fc00::/7 unique local — Java's isSiteLocalAddress misses it
            return (bytes[0] & 0xFE) == 0xFC;
        }

        return false;
    }
}
