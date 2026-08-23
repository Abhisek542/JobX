package com.jobx.repository;

import com.jobx.entity.Company;
import com.jobx.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {
    boolean existsByCompanyAndExternalId(Company company, String externalId);

    /** All stored jobs for a board — used to backfill a new watcher's feed. */
    List<Job> findByCompany(Company company);
}
