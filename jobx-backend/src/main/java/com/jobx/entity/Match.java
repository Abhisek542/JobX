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
 *
 * Since V5 a match can OUTLIVE its job. When the six-day TTL sweep drops a
 * posting it deletes the NEW/DISMISSED matches itself, but SEEN (saved) and
 * APPLIED rows are the user's own history and are kept — their {@link #job}
 * becomes null (ON DELETE SET NULL) and {@link #jobExpiredAt} is stamped.
 * That is why the handful of facts a card needs to render — title, apply URL,
 * company — are copied onto the match at creation instead of being read
 * through the job every time. Anything reading {@code match.getJob()} must
 * therefore null-check it.
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

    /** Null once the posting has been expired and swept away. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private Job job;

    /**
     * The board this match came from. A real FK, not a copied name: companies
     * outlive jobs (the TTL never deletes them), and unwatch cleanup has to be
     * able to find expired matches that no longer route through a job row.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** Copied from the job so an expired match can still be rendered. */
    @Column(name = "job_title", nullable = false)
    private String jobTitle;

    /** Copied from the job. The posting may well be gone — see jobExpiredAt. */
    @Column(name = "apply_url", nullable = false)
    private String applyUrl;

    /** When the TTL sweep dropped the underlying posting; null while it is live. */
    @Column(name = "job_expired_at")
    private Instant jobExpiredAt;

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
