package com.jobx.repository;

import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WatchedCompanyRepository extends JpaRepository<WatchedCompany, UUID> {
    List<WatchedCompany> findByUser(User user);

    @Query("SELECT w.user FROM WatchedCompany w WHERE w.company = :company AND w.status = :status")
    List<User> findUsersByCompanyAndStatus(@Param("company") Company company,
                                           @Param("status") WatchedCompany.CompanyStatus status);

    /**
     * The users a new job on this board should be scored for. ACTIVE-only is
     * load-bearing: it is what makes PAUSED actually pause — under the pre-V4
     * fan-out a paused watcher kept receiving matches whenever anyone else's
     * active watch fetched the same board.
     */
    default List<User> findActiveUsersByCompany(Company company) {
        return findUsersByCompanyAndStatus(company, WatchedCompany.CompanyStatus.ACTIVE);
    }
}
