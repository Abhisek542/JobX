package com.jobx.repository;

import com.jobx.entity.Company;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findByAtsPlatformAndBoardToken(AtsPlatform atsPlatform, String boardToken);

    @Query("SELECT DISTINCT w.company FROM WatchedCompany w WHERE w.status = :status")
    List<Company> findDistinctCompaniesByWatchStatus(@Param("status") WatchedCompany.CompanyStatus status);

    /**
     * Companies the scheduler should poll: those with at least one ACTIVE
     * watch row. A board every watcher has paused or abandoned is skipped —
     * that's also why orphaned companies (all watchers gone) cost nothing.
     */
    default List<Company> findAllWithActiveWatchers() {
        return findDistinctCompaniesByWatchStatus(WatchedCompany.CompanyStatus.ACTIVE);
    }
}
