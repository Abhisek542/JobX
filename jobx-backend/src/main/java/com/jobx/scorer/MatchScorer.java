package com.jobx.scorer;

import com.jobx.entity.FilterProfile;
import com.jobx.entity.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Per-user job matching engine — ported from verified MatchScorer.java reference.
 *
 * DO NOT redesign this logic. It was verified against 5 test profiles.
 * See MatchScorer.java in project root for the reference implementation.
 *
 * Scoring rules:
 *  1. Hard exclude: any excludeWord found in title/description → score=0, excluded=true
 *  2. Keyword match (OR): at least one keyword must match to appear
 *     - title match = 2x weight, description-only = 1x weight
 *     - keywordScore = round(70 * min(1, actualWeight / (keywords.size * 2)))
 *  3. Experience: SOFT filter — never hard-excludes
 *     - Full 30 pts if job exp range overlaps user range
 *     - experienceScore = max(0, 30 - distanceInYears * 10)
 *  4. Total = keywordScore + experienceScore, range 0–100
 */
@Service
@Slf4j
public class MatchScorer {

    public record ScoredJob(
            Job job,
            int score,
            boolean excluded,
            String reason,
            List<String> matchedKeywords
    ) {}

    public ScoredJob score(FilterProfile profile, Job job) {
        String titleLc = job.getTitle() != null ? job.getTitle().toLowerCase() : "";
        String descLc  = job.getDescription() != null ? job.getDescription().toLowerCase() : "";

        List<String> keywords     = profile.getKeywords()     != null ? profile.getKeywords()     : List.of();
        List<String> excludeWords = profile.getExcludeWords() != null ? profile.getExcludeWords() : List.of();

        // 1. Hard exclude — any exclude-word anywhere → drop immediately
        // Word-boundary match, not substring — otherwise "Lead" hits "leading"/
        // "leadership" boilerplate and "Intern" hits "interns"/"international",
        // hard-excluding nearly every job regardless of actual seniority.
        for (String ex : excludeWords) {
            if (containsWord(titleLc, ex) || containsWord(descLc, ex)) {
                return new ScoredJob(job, 0, true,
                        "excluded: contains \"" + ex + "\"", List.of());
            }
        }

        // 2. Keyword match (OR logic)
        int titleHits = 0, descOnlyHits = 0;
        List<String> matched = new ArrayList<>();

        for (String kw : keywords) {
            if (containsWord(titleLc, kw)) {
                titleHits++;
                matched.add(kw);
            } else if (containsWord(descLc, kw)) {
                descOnlyHits++;
                matched.add(kw);
            }
        }

        if (matched.isEmpty()) {
            return new ScoredJob(job, 0, true,
                    "excluded: matched none of your keywords", List.of());
        }

        double maxPossibleWeight = keywords.size() * 2.0;
        double actualWeight      = (titleHits * 2.0) + (descOnlyHits * 1.0);
        int keywordScore = (int) Math.round(70 * Math.min(1.0, actualWeight / maxPossibleWeight));

        // 3. Soft experience scoring — null exp range = distance 0 (full 30 pts)
        int distance = 0;
        if (job.getExpMin() != null && job.getExpMax() != null
                && profile.getExpMin() != null && profile.getExpMax() != null) {
            if (job.getExpMax() < profile.getExpMin()) {
                distance = profile.getExpMin() - job.getExpMax();
            } else if (job.getExpMin() > profile.getExpMax()) {
                distance = job.getExpMin() - profile.getExpMax();
            }
        }
        int experienceScore = Math.max(0, 30 - distance * 10);

        int total = keywordScore + experienceScore;
        String reason = "matched [" + String.join(", ", matched) + "]"
                + (distance > 0 ? "; experience " + distance + "yr outside your range (soft penalty)" : "; experience fits");

        return new ScoredJob(job, total, false, reason, matched);
    }

    /** Whole-word (or whole-phrase) match, case-insensitive, no substring bleed. */
    private boolean containsWord(String textLc, String word) {
        if (word == null || word.isBlank()) return false;
        String pattern = "\\b" + Pattern.quote(word.trim().toLowerCase()) + "\\b";
        return Pattern.compile(pattern).matcher(textLc).find();
    }

    /** Rank all non-excluded jobs for a user, descending by score. */
    public List<ScoredJob> rank(FilterProfile profile, List<Job> jobs) {
        return jobs.stream()
                .map(j -> score(profile, j))
                .filter(sj -> !sj.excluded())
                .sorted((a, b) -> b.score() - a.score())
                .toList();
    }
}
