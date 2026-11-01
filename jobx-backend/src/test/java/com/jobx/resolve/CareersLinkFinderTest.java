package com.jobx.resolve;

import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which links on a careers landing page the one-hop follow may fetch. The
 * same-site rule is the security-relevant part: a page must not be able to
 * steer our fetches to some other domain by labelling a link "Careers".
 */
class CareersLinkFinderTest {

    private static List<String> find(String page, String html) {
        return CareersLinkFinder.find(URI.create(page), html, 3);
    }

    @Test
    void picksTheOpenRolesLinkOffARealisticLandingPage() {
        List<String> links = find("https://acme.com/careers",
                FixtureSupport.fixture("careers-landing-onehop.html"));

        assertEquals(List.of("https://acme.com/careers/open-roles"), links);
    }

    @Test
    void matchesOnAnchorTextOrOnThePath() {
        List<String> links = find("https://acme.com/", """
                <a href="/team">We're hiring!</a>
                <a href="/jobs">Browse</a>
                <a href="/join-us">Come aboard</a>
                <a href="/pricing">Pricing</a>
                """);

        assertEquals(List.of("https://acme.com/team", "https://acme.com/jobs",
                "https://acme.com/join-us"), links);
    }

    @Test
    void acceptsSubdomainsOfTheSameSite() {
        List<String> links = find("https://www.acme.co.in/", """
                <a href="https://careers.acme.co.in/">Careers</a>
                <a href="https://jobs.acme.co.in/list">Jobs</a>
                """);

        assertEquals(List.of("https://careers.acme.co.in", "https://jobs.acme.co.in/list"), links);
    }

    @Test
    void rejectsCrossDomainCareersLinks() {
        List<String> links = find("https://acme.com/", """
                <a href="https://otheracme.com/careers">Careers</a>
                <a href="https://acme.com.evil.io/careers">Careers</a>
                <a href="https://jobs.lever.co/acme">Jobs</a>
                <a href="https://co.in/careers">Careers</a>
                """);

        assertTrue(links.isEmpty(), () -> "followed " + links);
    }

    /** A short company label is the site, not a public suffix. */
    @Test
    void aShortLabelIsNotMistakenForASuffix() {
        List<String> links = find("https://careers.abc.com/", """
                <a href="https://abc.com/jobs">All jobs</a>
                """);

        assertEquals(List.of("https://abc.com/jobs"), links);
    }

    @Test
    void resolvesRelativeLinksAgainstTheServedUrl() {
        List<String> links = find("https://www.acme.com/en/careers/", """
                <a href="open-roles">Open roles</a>
                <a href="../jobs">Jobs</a>
                """);

        assertEquals(List.of("https://www.acme.com/en/careers/open-roles",
                "https://www.acme.com/en/jobs"), links);
    }

    @Test
    void capsTheNumberOfLinks() {
        List<String> links = find("https://acme.com/", """
                <a href="/jobs/a">Jobs A</a>
                <a href="/jobs/b">Jobs B</a>
                <a href="/jobs/c">Jobs C</a>
                <a href="/jobs/d">Jobs D</a>
                """);

        assertEquals(3, links.size());
        assertEquals("https://acme.com/jobs/a", links.get(0));
    }

    @Test
    void dropsTheSelfLinkFragmentsDuplicatesAndNonPages() {
        List<String> links = find("https://acme.com/careers", """
                <a href="#open">Open roles</a>
                <a href="/careers/">Careers</a>
                <a href="mailto:jobs@acme.com">Jobs inbox</a>
                <a href="tel:+911234">Call careers</a>
                <a href="javascript:void(0)">Jobs</a>
                <a href="/careers/brochure.pdf">Careers brochure</a>
                <a href="/jobs#top">Jobs</a>
                <a href="/jobs">Jobs again</a>
                """);

        assertEquals(List.of("https://acme.com/jobs"), links);
    }

    @Test
    void theHostAloneDoesNotMakeALinkACareersLink() {
        List<String> links = find("https://careers.acme.com/", """
                <a href="https://careers.acme.com/privacy">Privacy</a>
                """);

        assertTrue(links.isEmpty());
    }

    @Test
    void registrableDomainHandlesTwoPartSuffixes() {
        assertEquals("acme.com", SlugCandidates.registrableDomain("careers.acme.com"));
        assertEquals("acme.co.in", SlugCandidates.registrableDomain("jobs.acme.co.in"));
        assertEquals("acme.com.au", SlugCandidates.registrableDomain("www.acme.com.au"));
        assertEquals("abc.com", SlugCandidates.registrableDomain("careers.abc.com"));
        assertEquals("acme.io", SlugCandidates.registrableDomain("ACME.io"));
        assertNull(SlugCandidates.registrableDomain("localhost"));
    }
}
