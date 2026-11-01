package com.jobx.crawler;

import com.jobx.resolve.SafeUrlFetcher;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RobotsTxtTest {

    private static boolean allows(String robots, String path) {
        return RobotsTxt.Rules.parse(robots, "jobx").allows(path);
    }

    @Test
    void wildcardGroupApplies() {
        String robots = "User-agent: *\nDisallow: /private\n";
        assertFalse(allows(robots, "/private/jobs"));
        assertTrue(allows(robots, "/careers"));
    }

    @Test
    void ourOwnGroupReplacesTheWildcardGroup() {
        String robots = "User-agent: *\nDisallow: /\n\nUser-agent: JobX\nDisallow: /admin\n";
        assertTrue(allows(robots, "/careers"));
        assertFalse(allows(robots, "/admin"));
    }

    @Test
    void longestMatchWinsAndAllowWinsATie() {
        String robots = "User-agent: *\nDisallow: /careers\nAllow: /careers/jobs\n";
        assertTrue(allows(robots, "/careers/jobs/1"));
        assertFalse(allows(robots, "/careers/team"));
        assertTrue(allows("User-agent: *\nDisallow: /a\nAllow: /a\n", "/a"));
    }

    @Test
    void wildcardsAndEndAnchors() {
        String robots = "User-agent: *\nDisallow: /*.pdf$\nDisallow: /*?session=\n";
        assertFalse(allows(robots, "/files/role.pdf"));
        assertTrue(allows(robots, "/files/role.pdf.html"));
        assertFalse(allows(robots, "/jobs?session=abc"));
    }

    @Test
    void anEmptyDisallowAllowsEverythingAndCommentsAreIgnored() {
        assertTrue(allows("User-agent: * # all\nDisallow:\n", "/anything"));
    }

    @Test
    void otherBotsGroupsDoNotApply() {
        assertTrue(allows("User-agent: Googlebot\nDisallow: /\n", "/careers"));
    }

    @Test
    void aMissingOrUnreadableRobotsTxtAllowsAndIsCachedPerOrigin() {
        SafeUrlFetcher fetcher = mock(SafeUrlFetcher.class);
        when(fetcher.fetch("https://acme.example/robots.txt")).thenReturn(Optional.empty());
        when(fetcher.fetch("https://broken.example/robots.txt")).thenThrow(new RuntimeException("boom"));
        RobotsTxt robots = new RobotsTxt(fetcher);

        assertTrue(robots.isAllowed(URI.create("https://acme.example/careers")));
        assertTrue(robots.isAllowed(URI.create("https://acme.example/jobs")));
        assertTrue(robots.isAllowed(URI.create("https://broken.example/careers")));
        verify(fetcher, times(1)).fetch("https://acme.example/robots.txt");
    }

    @Test
    void theCacheExpiresAfterADay() {
        SafeUrlFetcher fetcher = mock(SafeUrlFetcher.class);
        when(fetcher.fetch("https://acme.example/robots.txt"))
                .thenReturn(Optional.of("User-agent: *\nDisallow: /careers\n"))
                .thenReturn(Optional.empty());
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        RobotsTxt robots = new RobotsTxt(fetcher, clock);
        URI careers = URI.create("https://acme.example/careers");

        assertFalse(robots.isAllowed(careers));
        now.set(now.get().plus(Duration.ofHours(25)));
        assertTrue(robots.isAllowed(careers));
    }

    @Test
    void aUrlWithNoHostIsRefused() {
        assertFalse(new RobotsTxt(mock(SafeUrlFetcher.class)).isAllowed(URI.create("mailto:a@b.c")));
    }
}
