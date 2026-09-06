package com.jobx.entity;

import com.jobx.enums.AtsPlatform;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A company someone tried to add and Jobx could not resolve — a portal with no
 * public API (Workday, Rippling, BambooHR, Recruitee), or a board none of the
 * four resolution strategies could find.
 *
 * Recorded so that "which ATS should Jobx support next" is answered by demand
 * rather than by guesswork. The alternative is a dead end that teaches nobody
 * anything: the user gets told no, and the one useful signal in the interaction
 * — that a real person wanted this company badly enough to look it up — is
 * thrown away.
 *
 * Deliberately keeps the raw query, since it is usually a careers URL and the
 * host is the whole point of the record. Not user-visible anywhere; read with
 * SQL when deciding what to build.
 */
@Entity
@Table(name = "unsupported_board_requests")
@Getter @Setter @NoArgsConstructor
public class UnsupportedBoardRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nullable: the row outlives the account, and the demand signal is still valid. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Exactly what the user typed — name, website or careers link. */
    @Column(name = "query", nullable = false, length = 2000)
    private String query;

    /**
     * The ATS the careers page named without naming the board, where the sniffer
     * managed to work that out. Usually null. A cluster of these on one platform
     * is the strongest possible argument for building that fetcher next.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform_hint")
    private AtsPlatform platformHint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
