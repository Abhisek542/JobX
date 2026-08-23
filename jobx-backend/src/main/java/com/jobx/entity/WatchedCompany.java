package com.jobx.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One user's subscription to a Company board — since V4 this is a pure join
 * row (user, company, status). The board identity (platform/token/name) and
 * fetch health moved to {@link Company}; what remains per-user is only
 * whether THIS user is watching and whether they've paused.
 */
@Entity
@Table(name = "watched_companies",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "company_id"}))
@Getter @Setter @NoArgsConstructor
public class WatchedCompany {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // multi-tenant from day one per CLAUDE.md
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus status = CompanyStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public enum CompanyStatus {
        ACTIVE,
        PAUSED,
        UNSUPPORTED   // shown as "portal unsupported" in dashboard
    }
}
