package com.jobx.fetcher;

import com.jobx.fetcher.BoardTokens.WorkdayToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-built tokens are user input that ends up in a hostname. Every hostile
 * shape here would otherwise point a fetcher at a host of the attacker's choosing.
 */
class BoardTokensTest {

    @Nested
    @DisplayName("path segments")
    class PathSegments {

        @ParameterizedTest
        @ValueSource(strings = {"rippling", "Sprinto", "egnyte", "acme-inc", "acme_co", "acme.inc", "a"})
        void acceptsOneSafeSegmentUnchanged(String token) {
            // Unchanged on purpose: Rippling slugs are case-sensitive.
            assertEquals(token, BoardTokens.requirePathSegment(token));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"..", ".", "../admin", "a/b", "a?b=1", "x#y", "a%2Fb", "x@y",
                "-acme", ".hidden", "a b"})
        void rejectsAnythingThatCouldChangeThePath(String token) {
            assertThrows(AtsFetchException.class, () -> BoardTokens.requirePathSegment(token));
        }
    }

    @Nested
    @DisplayName("subdomain labels")
    class SubdomainLabels {

        @ParameterizedTest
        @ValueSource(strings = {"acme", "a", "careers-acme", "acme2", "0x1"})
        void acceptsASingleDnsLabel(String token) {
            assertEquals(token, BoardTokens.requireSubdomainLabel(token));
        }

        @Test
        void lowerCasesBecauseHostnamesAreCaseInsensitive() {
            assertEquals("acme", BoardTokens.requireSubdomainLabel("ACME"));
        }

        @Test
        void acceptsTheLongestLegalLabel() {
            String max = "a".repeat(63);
            assertEquals(max, BoardTokens.requireSubdomainLabel(max));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {
                "evil.com#", "a/../b", "x@y", "a.b", "-acme", "acme-", " acme", "acme ",
                "%2e", "acme:8080", "acme?x=1", "acme\\evil", "acme_corp", "127.0.0.1"})
        void rejectsAnythingThatCouldChangeTheHost(String token) {
            assertThrows(AtsFetchException.class, () -> BoardTokens.requireSubdomainLabel(token));
        }

        @Test
        void rejectsAnOverlongLabel() {
            assertThrows(AtsFetchException.class,
                    () -> BoardTokens.requireSubdomainLabel("a".repeat(64)));
        }
    }

    @Nested
    @DisplayName("Workday tenant/wdN/site tokens")
    class Workday {

        @Test
        void roundTripsARealToken() {
            WorkdayToken token = WorkdayToken.parse("salesforce/wd12/External_Career_Site");
            assertEquals("salesforce", token.tenant());
            assertEquals("wd12", token.shard());
            assertEquals("External_Career_Site", token.site());
            assertEquals("salesforce.wd12.myworkdayjobs.com", token.host());
            assertEquals("salesforce/wd12/External_Career_Site", token.token());
        }

        /** Host parts are case-insensitive; the site name is not. */
        @Test
        void normalisesTheHostPartsOnly() {
            WorkdayToken token = WorkdayToken.parse("SalesForce/WD12/External_Career_Site");
            assertEquals("salesforce/wd12/External_Career_Site", token.token());
        }

        @ParameterizedTest
        @ValueSource(strings = {"acme/wd/Site", "acme/xx5/Site", "acme/wd1234/Site", "acme/5/Site"})
        void rejectsABadShard(String token) {
            assertThrows(AtsFetchException.class, () -> WorkdayToken.parse(token));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"acme/wd5", "acme/wd5/Site/extra", "acme", "acme//Site", "/wd5/Site"})
        void rejectsTheWrongNumberOfParts(String token) {
            assertThrows(AtsFetchException.class, () -> WorkdayToken.parse(token));
        }

        @ParameterizedTest
        @ValueSource(strings = {"acme/wd5/Si.te", "acme/wd5/Site?x", "acme/wd5/Site#", "acme/wd5/Site name"})
        void rejectsABadSite(String token) {
            assertThrows(AtsFetchException.class, () -> WorkdayToken.parse(token));
        }

        @ParameterizedTest
        @ValueSource(strings = {"evil.com#/wd5/Site", "a.b/wd5/Site", "x@y/wd5/Site"})
        void rejectsAHostileTenant(String token) {
            assertThrows(AtsFetchException.class, () -> WorkdayToken.parse(token));
        }
    }
}
