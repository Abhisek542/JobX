package com.jobx.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-user filter profile — the input to MatchScorer.
 *
 * Rules (from verified MatchScorer.java):
 *  - keywords: OR logic, title match = 2x weight, desc-only = 1x
 *  - excludeWords: hard exclude, any match → job dropped entirely
 *  - expMin/expMax: SOFT filter — out-of-range lowers score, never excludes
 */
@Entity
@Table(name = "filter_profiles")
@Getter @Setter @NoArgsConstructor
public class FilterProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Stored as Postgres text array
    @Column(name = "keywords", columnDefinition = "text[]")
    private List<String> keywords;

    @Column(name = "exclude_words", columnDefinition = "text[]")
    private List<String> excludeWords;

    // Soft experience range — MatchScorer uses these for distance penalty
    @Column(name = "exp_min")
    private Integer expMin;

    @Column(name = "exp_max")
    private Integer expMax;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
