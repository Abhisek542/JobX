package com.jobx.crawler;

import com.jobx.fetcher.custom.CustomPageFetcher;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalUrlTest {

    @Test
    void normalisesHostFragmentTrackingAndTrailingSlash() {
        assertEquals(Optional.of("https://acme.example/careers"),
                CanonicalUrl.of("https://ACME.example/careers/?utm_source=x&gclid=1#open"));
        assertEquals(Optional.of("https://acme.example/careers?team=eng"),
                CanonicalUrl.of("https://acme.example/careers/?team=eng&utm_medium=y"));
    }

    @Test
    void keepsTheSchemeAsServed() {
        assertEquals(Optional.of("http://acme.example/jobs"), CanonicalUrl.of("http://acme.example/jobs"));
    }

    @Test
    void refusesNonWebUrls() {
        assertTrue(CanonicalUrl.of("file:///etc/passwd").isEmpty());
        assertTrue(CanonicalUrl.of("javascript:alert(1)").isEmpty());
        assertTrue(CanonicalUrl.of("acme.example/careers").isEmpty());
        assertTrue(CanonicalUrl.of("   ").isEmpty());
        assertTrue(CanonicalUrl.of((String) null).isEmpty());
    }

    @Test
    void externalIdIsStableAndKeyedOnUrlElseTitleAndLocation() {
        ExtractedJob a = new ExtractedJob("Engineer", "https://acme.example/jobs/1", "Pune", null, null, null, null);
        ExtractedJob sameUrl = new ExtractedJob("Engineer (renamed)", "https://acme.example/jobs/1", "Remote",
                null, null, null, "desc");
        ExtractedJob other = new ExtractedJob("Engineer", "https://acme.example/jobs/2", "Pune", null, null, null, null);

        String id = CustomPageFetcher.externalId(a);
        assertEquals(32, id.length());
        assertEquals(id, CustomPageFetcher.externalId(sameUrl));
        assertNotEquals(id, CustomPageFetcher.externalId(other));

        ExtractedJob noUrl = new ExtractedJob("Engineer", null, "Pune", null, null, null, null);
        ExtractedJob noUrlElsewhere = new ExtractedJob("Engineer", null, "Delhi", null, null, null, null);
        assertNotEquals(CustomPageFetcher.externalId(noUrl), CustomPageFetcher.externalId(noUrlElsewhere));
        assertEquals(CustomPageFetcher.externalId(noUrl),
                CustomPageFetcher.externalId(new ExtractedJob("Engineer", null, "Pune", null, null, null, null)));
    }
}
