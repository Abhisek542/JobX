package com.jobx.scorer;

import com.jobx.entity.FilterProfile;
import com.jobx.entity.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the scoring engine. The rules under test are the VERIFIED ones from
 * CLAUDE.md — hard exclude, OR keywords with title weighted 2x, soft experience
 * distance — so these lock in existing behaviour rather than proposing new.
 *
 * Two regression classes get explicit coverage because both failed SILENTLY in
 * production (empty feed, no exception, no log):
 *  - substring bleed: "Java" matching inside "JavaScript", excludeWord "lead"
 *    matching inside "leading" boilerplate (fixed 2026-07-18)
 *  - symbol-edged keywords: "C++"/"C#"/".NET" never matching anything, because
 *    a trailing \b after '+' or '#' can never be satisfied
 */
class MatchScorerTest {

    private final MatchScorer scorer = new MatchScorer();

    // ---------- helpers ----------

    private FilterProfile profile(List<String> keywords, List<String> excludeWords,
                                  Integer expMin, Integer expMax) {
        FilterProfile profile = new FilterProfile();
        profile.setKeywords(keywords);
        profile.setExcludeWords(excludeWords);
        profile.setExpMin(expMin);
        profile.setExpMax(expMax);
        return profile;
    }

    private FilterProfile profile(List<String> keywords) {
        return profile(keywords, List.of(), null, null);
    }

    private Job job(String title, String description) {
        return job(title, description, null, null);
    }

    private Job job(String title, String description, Integer expMin, Integer expMax) {
        Job job = new Job();
        job.setTitle(title);
        job.setDescription(description);
        job.setExpMin(expMin);
        job.setExpMax(expMax);
        return job;
    }

    // ---------- keyword scoring ----------

    @Test
    void titleMatchScoresFullKeywordWeight() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java")),
                job("Java Developer", "Spring Boot backend work"));

        // weight 2 / max 2 → 70, plus full 30 for unspecified experience
        assertFalse(result.excluded());
        assertEquals(100, result.score());
        assertEquals(List.of("java"), result.matchedKeywords());
    }

    @Test
    void descriptionOnlyMatchScoresHalfWeight() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java")),
                job("Backend Developer", "We use Java and Postgres"));

        // weight 1 / max 2 → 35, plus 30
        assertEquals(65, result.score());
    }

    @Test
    void titleHitsCountDoubleAgainstDescriptionHits() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java", "kotlin")),
                job("Java Developer", "Kotlin is used on the mobile side too"));

        // (1 title * 2 + 1 desc * 1) / (2 keywords * 2) = 0.75 → round(52.5) = 53, plus 30
        assertEquals(83, result.score());
        assertEquals(List.of("java", "kotlin"), result.matchedKeywords());
    }

    @Test
    void keywordMatchingIsOrNotAnd() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java", "rust", "erlang")),
                job("Java Developer", "backend systems"));

        // one of three is enough to appear; weight 2 / max 6 → round(23.33) = 23, plus 30
        assertFalse(result.excluded());
        assertEquals(53, result.score());
        assertEquals(List.of("java"), result.matchedKeywords());
    }

    @Test
    void everyKeywordInTitleCapsAt100() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java", "spring")),
                job("Java Spring Developer", "platform team"));

        assertEquals(100, result.score());
    }

    @Test
    void noKeywordMatchIsExcluded() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("rust")),
                job("Java Developer", "Spring Boot"));

        assertTrue(result.excluded());
        assertEquals(0, result.score());
        assertTrue(result.reason().contains("none of your keywords"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("Java")),
                job("JAVA Developer", "backend"));

        assertEquals(100, result.score());
    }

    @Test
    void multiWordPhraseMatchesAsAWholePhrase() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("backend engineer")),
                job("Backend Engineer, Platform", "distributed systems"));

        assertEquals(100, result.score());
    }

    @Test
    void multiWordPhraseDoesNotBleedIntoLongerWords() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("backend engineer")),
                job("Backend Engineering Manager", "leading the platform group"));

        assertTrue(result.excluded());
    }

    // ---------- substring-bleed regressions (fixed 2026-07-18) ----------

    @Test
    void keywordDoesNotMatchInsideLongerWord() {
        // "Java" must not match "JavaScript" — the original substring bug
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java")),
                job("JavaScript Developer", "We write javascript all day"));

        assertTrue(result.excluded());
    }

    @Test
    void shortKeywordDoesNotMatchInsideLongerWord() {
        assertTrue(scorer.score(
                profile(List.of("go")),
                job("Backend Developer", "we use golang here")).excluded());
    }

    @Test
    void shortKeywordStillMatchesAsAWholeWord() {
        assertFalse(scorer.score(
                profile(List.of("go")),
                job("Backend Developer", "we use Go and Rust")).excluded());
    }

    @Test
    void excludeWordDoesNotMatchInsideBoilerplate() {
        // The exact failure that silently zeroed the matches table: every
        // Razorpay/PhonePe JD shares "About us" text containing "leading" and
        // "interns", which substring-matched the exclude words "lead"/"intern".
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java"), List.of("lead", "intern"), null, null),
                job("Java Developer",
                        "one of india's leading full-stack platforms, where interns question CXOs"));

        assertFalse(result.excluded());
        assertEquals(100, result.score());
    }

    // ---------- symbol-edged keywords ----------

    @Test
    void plusSuffixedKeywordMatches() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("C++")),
                job("Senior C++ Engineer", "low-latency systems work"));

        assertFalse(result.excluded());
        assertEquals(100, result.score());
        assertEquals(List.of("C++"), result.matchedKeywords());
    }

    @Test
    void hashSuffixedKeywordMatches() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("C#")),
                job("Backend Developer", "strong C# skills required"));

        assertFalse(result.excluded());
        assertEquals(65, result.score());
    }

    @Test
    void dotPrefixedKeywordMatches() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of(".NET")),
                job("Backend Developer", "experience with .NET Core"));

        assertFalse(result.excluded());
        assertEquals(65, result.score());
    }

    @Test
    void symbolEdgeMatchesWhenAdjacentToOtherWordCharacters() {
        // A symbol edge is its own boundary: "C++" is genuinely present in
        // "C/C++", and ".NET" in "ASP.NET" — both are what the user meant.
        assertFalse(scorer.score(
                profile(List.of("C++")),
                job("Systems Engineer", "strong C/C++ background")).excluded());

        assertFalse(scorer.score(
                profile(List.of(".NET")),
                job("Backend Developer", "ASP.NET MVC experience")).excluded());
    }

    @Test
    void symbolEdgedExcludeWordStillExcludes() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("backend"), List.of("C++"), null, null),
                job("Backend Engineer", "heavy C++ workload"));

        assertTrue(result.excluded());
        assertTrue(result.reason().contains("C++"));
    }

    // ---------- hard exclude ----------

    @Test
    void excludeWordInTitleDropsJob() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java"), List.of("intern"), null, null),
                job("Java Intern", "summer programme"));

        assertTrue(result.excluded());
        assertEquals(0, result.score());
        assertTrue(result.reason().contains("intern"));
        assertEquals(List.of(), result.matchedKeywords());
    }

    @Test
    void excludeWordInDescriptionDropsJob() {
        assertTrue(scorer.score(
                profile(List.of("java"), List.of("contract"), null, null),
                job("Java Developer", "This is a contract role, 6 months")).excluded());
    }

    // ---------- experience: SOFT filter, never excludes ----------

    @Test
    void overlappingExperienceRangeScoresFull() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java"), List.of(), 3, 8),
                job("Java Developer", "backend", 2, 5));

        assertEquals(100, result.score());
    }

    @Test
    void experienceBelowUserRangeAppliesSoftPenalty() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java"), List.of(), 6, 9),
                job("Java Developer", "backend", 2, 4));

        // distance 2 → 30 - 20 = 10, plus 70
        assertEquals(80, result.score());
        assertFalse(result.excluded());
    }

    @Test
    void experienceAboveUserRangeAppliesSoftPenalty() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java"), List.of(), 1, 3),
                job("Java Developer", "backend", 5, 7));

        assertEquals(80, result.score());
    }

    @Test
    void farExperienceMismatchScoresZeroButNeverExcludes() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java"), List.of(), 1, 2),
                job("Java Developer", "backend", 15, 20));

        // experience floors at 0 — the keyword score alone survives
        assertFalse(result.excluded());
        assertEquals(70, result.score());
    }

    @Test
    void nullJobExperienceScoresFull() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java"), List.of(), 3, 5),
                job("Java Developer", "backend", null, null));

        assertEquals(100, result.score());
    }

    @Test
    void nullProfileExperienceScoresFull() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java"), List.of(), null, null),
                job("Java Developer", "backend", 10, 12));

        assertEquals(100, result.score());
    }

    // ---------- null tolerance ----------

    @Test
    void nullTitleAndDescriptionAreExcludedNotCrashed() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(List.of("java")),
                job(null, null));

        assertTrue(result.excluded());
        assertEquals(0, result.score());
    }

    @Test
    void nullKeywordAndExcludeListsAreExcludedNotCrashed() {
        MatchScorer.ScoredJob result = scorer.score(
                profile(null, null, null, null),
                job("Java Developer", "backend"));

        assertTrue(result.excluded());
    }

    // ---------- rank ----------

    @Test
    void rankSortsDescendingAndDropsExcluded() {
        FilterProfile profile = profile(List.of("java"), List.of("intern"), null, null);

        List<MatchScorer.ScoredJob> ranked = scorer.rank(profile, List.of(
                job("Backend Developer", "We use Java here"),   // 65
                job("Java Intern", "summer programme"),          // excluded by "intern"
                job("Java Developer", "platform team"),          // 100
                job("Rust Developer", "systems")                 // excluded, no keyword
        ));

        assertEquals(2, ranked.size());
        assertEquals(100, ranked.get(0).score());
        assertEquals(65, ranked.get(1).score());
        assertEquals("Java Developer", ranked.get(0).job().getTitle());
    }
}
