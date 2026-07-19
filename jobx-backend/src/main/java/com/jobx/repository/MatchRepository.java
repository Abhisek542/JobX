package com.jobx.repository;

import com.jobx.entity.Match;
import com.jobx.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MatchRepository extends JpaRepository<Match, UUID> {
    List<Match> findByUserOrderByCreatedAtDesc(User user);
    boolean existsByUserAndJob_Id(User user, UUID jobId);
}
