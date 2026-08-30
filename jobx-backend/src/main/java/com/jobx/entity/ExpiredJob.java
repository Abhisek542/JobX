package com.jobx.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A tombstone for a job Jobx deliberately dropped once it passed the retention
 * window (see {@code V5__job_ttl.sql}).
 *
 * This exists purely so the deletion sticks. FetchScheduler dedups on
 * (company, externalId) against the jobs table, so a posting that is still
 * live on the board would otherwise be re-inserted as brand new on the very
 * next poll — re-notifying every watcher about a role Jobx had just decided
 * was too old to be worth showing. The tombstone is the record of that
 * decision, and it outlives the job row.
 *
 * Deliberately tiny: no title, no description, no raw JSON. Reclaiming that
 * bulk is the entire point of expiring a job.
 */
@Entity
@Table(name = "expired_jobs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "external_id"}))
@Getter @Setter @NoArgsConstructor
public class ExpiredJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** The ATS's own job ID — the half of the dedup key that must survive. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    /** The effective posting date the sweep judged, kept for diagnosis. */
    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "expired_at", nullable = false, updatable = false)
    private Instant expiredAt = Instant.now();
}
