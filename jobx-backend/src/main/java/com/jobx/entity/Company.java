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

    // ---- ATS-move re-detection (V10, add-company-improvements PR D) ----

    /**
     * The careers page this board was found on, when it came from URL mode.
     * Only a hint for BoardRedetector's later sniffs, always fetched through
     * SafeUrlFetcher. Filled once: a later adder never overwrites it.
     */
    @Column(name = "careers_url")
    private String careersUrl;

    /** First FAILED fetch of the current failing streak; null while healthy. */
    @Column(name = "failing_since")
    private Instant failingSince;

    /** When BoardRedetector last looked for a new board, found one or not. */
    @Column(name = "last_redetect_at")
    private Instant lastRedetectAt;

    /**
     * A board BoardRedetector thinks this company moved to. A proposal only:
     * nothing is switched until a watcher confirms it via POST /watchlist/{id}/move.
     * All three are set together or all null.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "moved_to_platform")
    private AtsPlatform movedToPlatform;

    @Column(name = "moved_to_token")
    private String movedToToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "moved_to_reason")
    private MoveReason movedToReason;

    public boolean hasMoveProposal() {
        return movedToPlatform != null && movedToToken != null;
    }

    public void proposeMove(AtsPlatform platform, String token, MoveReason reason) {
        this.movedToPlatform = platform;
        this.movedToToken = token;
        this.movedToReason = reason;
    }

    public void clearMoveProposal() {
        this.movedToPlatform = null;
        this.movedToToken = null;
        this.movedToReason = null;
    }

    public enum FetchStatus {
        SUCCESS,
        FAILED   // dashboard shows the "Refresh issue" warning state
    }

    /** Why a move was proposed. Drives the banner copy on the watchlist. */
    public enum MoveReason {
        /** The board has been failing for a while and another board turned up. */
        FAILING,
        /**
         * A healthy crawled (CUSTOM) board whose careers page now links a real
         * ATS. Not produced until the PR E crawler ships; a successful fetch
         * does not clear it, because the old board still works.
         */
        NATIVE_AVAILABLE
    }
}
