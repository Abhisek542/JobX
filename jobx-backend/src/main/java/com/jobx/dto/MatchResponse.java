package com.jobx.dto;

import com.jobx.entity.Job;
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
        /**
         * The board this role came from. companyName is a display string and
         * two distinct boards can carry the same one, so anything grouping or
         * joining by company keys on this id, never on the name. Safe on an
         * expired match: matches.company_id is a real FK that outlives the job
         * row, which is exactly why it is denormalized onto the match.
         */
        UUID companyId,
        String companyName,
        String applyUrl,
        Integer score,
        List<String> matchedKeywords,
        Match.MatchStatus status,
        Instant createdAt,
        /** When Jobx dropped the posting; null while it is still live. */
        Instant expiredAt,
        /*
         * Display-only labels from the posting (since V8). Unlike the fields
         * above these are read through match.getJob(), so all three are null
         * once the posting has expired, as well as whenever the board doesn't
         * publish them. An expired card already shows less; that's accepted.
         */
        String department,
        String team,
        String employmentType
) {
    public static MatchResponse from(Match match) {
        Job job = match.getJob();
        return new MatchResponse(
                match.getId(),
                job != null ? job.getId() : null,
                match.getJobTitle(),
                match.getCompany().getId(),
                match.getCompany().getDisplayName(),
                match.getApplyUrl(),
                match.getScore(),
                match.getMatchedKeywords(),
                match.getStatus(),
                match.getCreatedAt(),
                match.getJobExpiredAt(),
                job != null ? job.getDepartment() : null,
                job != null ? job.getTeam() : null,
                job != null ? job.getEmploymentType() : null
        );
    }
}
