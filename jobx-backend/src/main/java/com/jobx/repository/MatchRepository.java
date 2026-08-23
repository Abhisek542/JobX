package com.jobx.repository;

import com.jobx.entity.Company;
import com.jobx.entity.Match;
import com.jobx.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MatchRepository extends JpaRepository<Match, UUID> {
    List<Match> findByUserOrderByCreatedAtDesc(User user);
    boolean existsByUserAndJob_Id(User user, UUID jobId);

    /**
     * Unwatch cleanup (V4): jobs outlive any single watcher now, so removing a
     * watch must delete the user's own matches explicitly — the pre-V4 schema
     * did this via ON DELETE CASCADE, which also wiped OTHER users' matches.
     */
    @Modifying
    @Query("DELETE FROM Match m WHERE m.user = :user AND m.job.id IN (SELECT j.id FROM Job j WHERE j.company = :company)")
    void deleteByUserAndCompany(@Param("user") User user, @Param("company") Company company);
}
