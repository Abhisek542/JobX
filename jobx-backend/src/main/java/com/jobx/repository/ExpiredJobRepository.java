package com.jobx.repository;

import com.jobx.entity.Company;
import com.jobx.entity.ExpiredJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

@Repository
public interface ExpiredJobRepository extends JpaRepository<ExpiredJob, UUID> {

    /**
     * The dedup half that survives deletion — FetchScheduler consults this
     * alongside JobRepository so an expired posting that is still live on the
     * board is not re-inserted as new on the next poll.
     */
    boolean existsByCompanyAndExternalId(Company company, String externalId);

    /**
     * Every tombstoned external id for one board, loaded once per fetch cycle.
     * A per-job existsBy would be an N+1 against a board the size of Bosch
     * (4.7k postings); one set lookup per company carries the same information.
     */
    @Query("SELECT e.externalId FROM ExpiredJob e WHERE e.company = :company")
    Set<String> findExternalIdsByCompany(@Param("company") Company company);
}
