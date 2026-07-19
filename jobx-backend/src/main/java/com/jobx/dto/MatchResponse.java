package com.jobx.dto;

import com.jobx.entity.Job;
import com.jobx.entity.Match;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single alert-feed card: the scored Match plus enough of its Job/company
 * to render without the client making a second round trip.
 */
public record MatchResponse(
        UUID id,
        UUID jobId,
        String jobTitle,
        String companyName,
        String applyUrl,
        Integer score,
        List<String> matchedKeywords,
        Match.MatchStatus status,
        Instant createdAt
) {
    public static MatchResponse from(Match match) {
        Job job = match.getJob();
        return new MatchResponse(
                match.getId(),
                job.getId(),
                job.getTitle(),
                job.getCompany().getCompanyName(),
                job.getApplyUrl(),
                match.getScore(),
                match.getMatchedKeywords(),
                match.getStatus(),
                match.getCreatedAt()
        );
    }
}
