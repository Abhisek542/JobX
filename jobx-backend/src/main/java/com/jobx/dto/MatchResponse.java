package com.jobx.dto;

import com.jobx.entity.Match;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single alert-feed card: the scored Match plus enough of its job/company
 * to render without the client making a second round trip.
 *
 * Every job field is read off the Match itself, not through match.getJob().
 * Since V5 a match can outlive its posting: the six-day TTL sweep deletes the
 * job but keeps SEEN/APPLIED rows as the user's own history, leaving job null.
 * Those cards must still render, which is what the denormalized columns are
 * for — and {@code expiredAt} is how the UI says so honestly rather than
 * presenting a dead posting as if it were live.
 */
public record MatchResponse(
        UUID id,
        /** Null once the posting has been expired and swept away. */
        UUID jobId,
        String jobTitle,
        String companyName,
        String applyUrl,
        Integer score,
        List<String> matchedKeywords,
        Match.MatchStatus status,
        Instant createdAt,
        /** When Jobx dropped the posting; null while it is still live. */
        Instant expiredAt
) {
    public static MatchResponse from(Match match) {
        return new MatchResponse(
                match.getId(),
                match.getJob() != null ? match.getJob().getId() : null,
                match.getJobTitle(),
                match.getCompany().getDisplayName(),
                match.getApplyUrl(),
                match.getScore(),
                match.getMatchedKeywords(),
                match.getStatus(),
                match.getCreatedAt(),
                match.getJobExpiredAt()
        );
    }
}
