package com.jobx.entity;

import com.jobx.enums.AtsPlatform;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "watched_companies",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "board_token", "ats_platform"}))
@Getter @Setter @NoArgsConstructor
public class WatchedCompany {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // multi-tenant from day one per CLAUDE.md
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "ats_platform", nullable = false)
    private AtsPlatform atsPlatform;

    // The token extracted from the careers page URL
    // e.g. "razorpaysoftwareprivatelimited", "phonepe"
    // NEVER guessed — always read from live URL per Phase 0 lesson
    @Column(name = "board_token", nullable = false)
    private String boardToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus status = CompanyStatus.ACTIVE;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    // Whether the LAST attempt succeeded — null until the first attempt.
    // Without this, "last checked 2 min ago, 0 new jobs" is what a board that
    // has been 404ing for a week looks like.
    @Enumerated(EnumType.STRING)
    @Column(name = "last_fetch_status")
    private FetchStatus lastFetchStatus;

    // Short sanitized summary for operators — never a stack trace, and not
    // returned by the API (see WatchedCompanyResponse).
    @Column(name = "last_fetch_error")
    private String lastFetchError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public enum CompanyStatus {
        ACTIVE,
        PAUSED,
        UNSUPPORTED   // shown as "portal unsupported" in dashboard
    }

    public enum FetchStatus {
        SUCCESS,
        FAILED   // dashboard shows the "Refresh issue" warning state
    }
}
