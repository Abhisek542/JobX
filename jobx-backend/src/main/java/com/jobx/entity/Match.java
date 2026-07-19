package com.jobx.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-user scored view of a job.
 *
 * Job rows are global (fetched once per company regardless of how many
 * users watch it). Match rows are per-user — computed by running every
 * user's FilterProfile through MatchScorer against new Job rows after
 * each poll cycle.
 */
@Entity
@Table(name = "matches",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "job_id"}))
@Getter @Setter @NoArgsConstructor
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    // 0–100 from MatchScorer: keywordScore (0-70) + experienceScore (0-30)
    @Column(nullable = false)
    private Integer score;

    // Which of the user's keywords actually matched — stored for feed display
    @Column(name = "matched_keywords", columnDefinition = "text[]")
    private List<String> matchedKeywords;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchStatus status = MatchStatus.NEW;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public enum MatchStatus {
        NEW,       // not yet seen by user
        SEEN,      // user opened the card
        APPLIED,   // user clicked "Apply direct"
        DISMISSED  // user swiped away
    }
}
