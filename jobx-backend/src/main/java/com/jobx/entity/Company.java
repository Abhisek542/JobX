package com.jobx.entity;

import com.jobx.enums.AtsPlatform;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A real company board, keyed by (ats_platform, board_token) — introduced by
 * V4 (fix B for the per-watch-row jobs defect). Jobs hang off this table, so a
 * posting is stored once no matter how many users watch the board, and one
 * user deleting their watch can no longer cascade away another user's matches.
 *
 * displayName is canonical: set by whoever adds the board first, shown to every
 * watcher. Per-user nicknames were deliberately rejected — one shared name also
 * closes the leak where a feed card rendered whatever label another user typed.
 *
 * Fetch health lives here (moved from WatchedCompany) because fetching is a
 * property of the board, not of any one user's subscription.
 */
@Entity
@Table(name = "companies",
       uniqueConstraints = @UniqueConstraint(columnNames = {"ats_platform", "board_token"}))
@Getter @Setter @NoArgsConstructor
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "ats_platform", nullable = false)
    private AtsPlatform atsPlatform;

    // The token extracted from the careers page URL
    // e.g. "razorpaysoftwareprivatelimited", "phonepe"
    // NEVER guessed — always read from live URL per Phase 0 lesson
    @Column(name = "board_token", nullable = false)
    private String boardToken;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    // Also the manual-fetch cooldown anchor — shared across all watchers now,
    // which is the point: the board really was just checked, for everyone.
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

    public enum FetchStatus {
        SUCCESS,
        FAILED   // dashboard shows the "Refresh issue" warning state
    }
}
