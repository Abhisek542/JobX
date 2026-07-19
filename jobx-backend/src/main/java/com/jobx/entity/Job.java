package com.jobx.entity;

import com.jobx.enums.AtsPlatform;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Normalized job entity — ATS-agnostic.
 *
 * The fetcher translation layer absorbs all ATS-specific field name
 * differences before anything reaches this entity. MatchScorer only
 * ever reads: title, description, expMin, expMax.
 *
 * Field mapping per ATS (handled in fetcher, NOT here):
 *   Greenhouse: title, content(HTML→strip), location.name, absolute_url, first_published
 *   Lever:      text→title, descriptionPlain, categories.location, hostedUrl, createdAt(epochMs)
 *   Ashby:      title, descriptionHtml(→strip), location, jobUrl, publishedAt
 *   Workable:   title, description, location, url, created_at
 */
@Entity
@Table(name = "jobs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "external_id"}))
@Getter @Setter @NoArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private WatchedCompany company;

    // The ATS's own job ID — used for dedup on re-fetch
    // Greenhouse: integer job id (stored as String for cross-ATS consistency)
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ats_platform", nullable = false)
    private AtsPlatform atsPlatform;

    // MatchScorer input fields
    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;  // plain text, HTML already stripped by fetcher

    @Column
    private String location;     // display only, not used in scoring v1

    // Soft experience range — nullable if parsing from JD text fails
    // MatchScorer treats null as distance=0 (full 30 pts), never hard-excludes
    @Column(name = "exp_min")
    private Integer expMin;

    @Column(name = "exp_max")
    private Integer expMax;

    @Column(name = "apply_url", nullable = false)
    private String applyUrl;

    // The ATS's own "posted" timestamp — for display only ("2h ago")
    // NOT used for feed sorting — first_seen_at is used for that
    // Greenhouse: first_published (ISO 8601)
    // Lever: createdAt (epoch ms, converted)
    // Ashby: publishedAt (ISO 8601)
    @Column(name = "platform_posted_at")
    private Instant platformPostedAt;

    // When Jobx first observed this job — used for sorting and new-job alerts
    // Set by the fetcher on first insert, never updated
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt = Instant.now();

    // Full original ATS response — escape hatch, never used in matching
    // Stores company-specific metadata (Razorpay's "Job Location",
    // PhonePe's "Requisition Type") without polluting the typed schema
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", columnDefinition = "jsonb")
    private String rawJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
