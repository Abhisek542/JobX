package com.jobx.repository;

import com.jobx.entity.Company;
import com.jobx.entity.Job;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {
    boolean existsByCompanyAndExternalId(Company company, String externalId);

    /** All stored jobs for a board — used to backfill a new watcher's feed. */
    List<Job> findByCompany(Company company);

    /**
     * How many postings Jobx currently holds for a board.
     *
     * Evidence for the add-company confirmation card when the board came from
     * the catalog: it is already stored, so its roles can be shown without
     * calling the ATS at all.
     */
    long countByCompany(Company company);

    /**
     * A few stored titles for a board, freshest first — the catalog path's
     * equivalent of BoardPreview.sampleTitles.
     */
    @Query("SELECT j.title FROM Job j WHERE j.company = :company ORDER BY j.firstSeenAt DESC")
    List<String> findTitlesByCompany(@Param("company") Company company, Pageable pageable);

    /**
     * Jobs past the retention window, oldest first — the TTL sweep's input.
     *
     * The clock is the ATS's own posting date, which is what the six-day window
     * actually means. It is nullable though (not every board publishes one), so
     * firstSeenAt stands in: it is NOT NULL and is the honest worst case, since
     * a posting cannot be newer than the moment Jobx first saw it. Matches the
     * expression index created in V5.
     *
     * JOIN FETCH the company: the sweep runs on a scheduler thread with no
     * open-in-view session, and it needs company + externalId to write each
     * tombstone. Without this the lazy proxy blows up outside the transaction.
     */
    @Query("SELECT j FROM Job j JOIN FETCH j.company "
            + "WHERE COALESCE(j.platformPostedAt, j.firstSeenAt) < :cutoff "
            + "ORDER BY COALESCE(j.platformPostedAt, j.firstSeenAt) ASC")
    List<Job> findExpiredAsOf(@Param("cutoff") Instant cutoff);
}
