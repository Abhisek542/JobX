package com.jobx.resolve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Candidate generation is guesswork, so these tests pin down the two things that
 * decide whether the guess is worth making at all: that the domain label is
 * preferred over the typed name, and that both cases are always tried.
 *
 * Every expectation below is checked against a token verified live in
 * docs/ats-test-data.md, not an invented one.
 */
class SlugCandidatesTest {

    private static List<String> from(String query) {
        return SlugCandidates.from(query, 8);
    }

    /** atlan.com to "atlan" (Ashby), groww.in to "groww" (Greenhouse) — both live boards. */
    @Test
    void takesTheDomainLabelFromAWebsite() {
        assertEquals("atlan", from("https://atlan.com/careers/").get(0));
        assertEquals("groww", from("groww.in/careers").get(0));
        assertEquals("fampay", from("https://fampay.in").get(0));
    }

    @Test
    void stripsCareersStyleSubdomains() {
        assertEquals("atlan", from("https://careers.atlan.com").get(0));
        assertEquals("meesho", from("https://jobs.meesho.com/openings").get(0));
        assertEquals("porter", from("www.porter.in").get(0));
    }

    /** Two-part public suffixes are common for an India-first product. */
    @Test
    void handlesTwoPartSuffixes() {
        assertEquals("zeta", from("https://zeta.co.in/careers").get(0));
        assertEquals("netomi", from("https://netomi.co.uk").get(0));
    }

    /**
     * Lever and Ashby 404 on the wrong case, and two verified test boards are
     * capitalised. A lowercase-only candidate list misses Sprinto entirely.
     */
    @Test
    void alwaysTriesBothCases() {
        assertEquals(List.of("Sprinto", "sprinto"), from("Sprinto"));
        assertEquals(List.of("aspora", "Aspora"), from("aspora"));
    }

    @Test
    void lowercaseComesFirstBecauseItIsMuchTheCommonerForm() {
        assertEquals("fampay", from("FamPay").get(1));
    }

    @Test
    void dropsCorporateSuffixesFromATypedName() {
        assertTrue(from("Sprinto Technologies Pvt Ltd").contains("sprinto"));
        assertTrue(from("Netomi Inc").contains("netomi"));
    }

    @Test
    void normalisesPunctuationAndSpacing() {
        assertEquals("fampay", from("  Fam Pay!  ").get(0));
    }

    /** A name with a space is a name even if it also has a dot in it. */
    @Test
    void aNameIsNotAHost() {
        assertNull(SlugCandidates.hostOf("Acme Inc. Bangalore"));
        assertNull(SlugCandidates.hostOf("Razorpay"));
        assertEquals("razorpay.com", SlugCandidates.hostOf("https://razorpay.com/jobs/"));
    }

    @Test
    void respectsTheCandidateCap() {
        assertEquals(1, SlugCandidates.from("Sprinto", 1).size());
        assertTrue(SlugCandidates.from("Sprinto Technologies Pvt Ltd", 2).size() <= 2);
    }

    @Test
    void emptyInputYieldsNoCandidates() {
        assertTrue(from("").isEmpty());
        assertTrue(from("   ").isEmpty());
        assertTrue(SlugCandidates.from(null, 4).isEmpty());
    }

    /** No duplicates: an all-lowercase name has one variant, not two identical ones. */
    @Test
    void deduplicates() {
        List<String> candidates = from("apna");
        assertEquals(candidates.size(), candidates.stream().distinct().count());
    }

    /**
     * lowercase + Capitalised cannot produce a camelCase token, and those are
     * real — SmartRecruiters' verified Bosch board is "BoschGroup". Someone who
     * pastes the token itself must not be defeated by the normaliser.
     */
    @Test
    void keepsWhatTheUserTypedWhenItIsAlreadySlugShaped() {
        assertEquals(List.of("BoschGroup", "boschgroup", "Boschgroup"), from("BoschGroup"));
        assertEquals(List.of("FamPay", "fampay", "Fampay"), from("FamPay"));
        // "limited" is stripped too, so phonepe is tried alongside the full token.
        assertEquals(List.of("PHONEPELIMITED", "phonepelimited", "Phonepelimited", "phonepe", "Phonepe"),
                from("PHONEPELIMITED"));
    }

    /** A typed name with spaces is not a slug, so nothing extra is added. */
    @Test
    void doesNotAddMultiWordInputAsATokenOfItsOwn() {
        assertEquals(List.of("sprinto", "Sprinto"), from("Sprinto Technologies Pvt Ltd").stream()
                .filter(c -> c.equalsIgnoreCase("sprinto")).toList());
        assertFalse(from("Fam Pay").contains("Fam Pay"));
    }
}
