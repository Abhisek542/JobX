package com.jobx.repository;

import com.jobx.entity.Company;
import com.jobx.entity.Match;
import com.jobx.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MatchRepository extends JpaRepository<Match, UUID> {
    List<Match> findByUserOrderByCreatedAtDesc(User user);
    boolean existsByUserAndJob_Id(User user, UUID jobId);

    /**
     * One user's matches on one board — loaded up front by the profile-save
     * rescore. Routed through the denormalized company since V5: a match whose
     * job has been expired away still belongs to the board, and joining through
     * jobs would silently drop those rows.
     */
    List<Match> findByUserAndCompany(User user, Company company);

    /**
     * Unwatch cleanup (V4): jobs outlive any single watcher now, so removing a
     * watch must delete the user's own matches explicitly — the pre-V4 schema
     * did this via ON DELETE CASCADE, which also wiped OTHER users' matches.
     *
     * V5 routes this through match.company rather than match.job.company, so
     * that expired matches (job_id null) are cleaned up too. Unwatching means
     * "remove this board from my account" — leaving behind orphaned APPLIED
     * rows the user can no longer see the board for would be worse than losing
     * them.
     */
    @Modifying
    @Query("DELETE FROM Match m WHERE m.user = :user AND m.company = :company")
    void deleteByUserAndCompany(@Param("user") User user, @Param("company") Company company);

    /**
     * The TTL sweep's first step: drop the matches nobody has engaged with for
     * the jobs about to be deleted. Whatever survives this (SEEN/APPLIED) is
     * kept on purpose and simply loses its job pointer via ON DELETE SET NULL.
     */
    @Modifying
    @Query("DELETE FROM Match m WHERE m.job.id IN :jobIds AND m.status IN :statuses")
    int deleteByJobIdsAndStatuses(@Param("jobIds") Collection<UUID> jobIds,
                                  @Param("statuses") Collection<Match.MatchStatus> statuses);

    /**
     * Stamp the surviving matches so the feed can label them "no longer
     * listed". Runs BEFORE the job delete, while job_id still points at them.
     */
    @Modifying
    @Query("UPDATE Match m SET m.jobExpiredAt = :expiredAt WHERE m.job.id IN :jobIds")
    int markExpiredByJobIds(@Param("jobIds") Collection<UUID> jobIds,
                            @Param("expiredAt") Instant expiredAt);
}
