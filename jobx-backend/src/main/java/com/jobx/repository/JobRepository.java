package com.jobx.repository;

import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {
    Optional<Job> findByCompanyAndExternalId(WatchedCompany company, String externalId);
    boolean existsByCompanyAndExternalId(WatchedCompany company, String externalId);
}
