package com.jobx.repository;

import com.jobx.entity.Company;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import org.springframework.data.domain.Pageable;
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

    /**
     * Boards whose display name contains the typed text, for the add-company
     * typeahead. Case-insensitive because users type "razorpay", not the
     * canonical "Razorpay".
     *
     * Ranking is left to the caller: it needs to demote boards whose last fetch
     * FAILED, and a dead board is a real risk here rather than a hypothetical —
     * PhonePe's Greenhouse token went from 68 live jobs to a hard 404 in six
     * days. Offering one as a confident suggestion would be worse than offering
     * nothing.
     */
    @Query("SELECT c FROM Company c WHERE LOWER(c.displayName) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(c.boardToken) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Company> searchByNameOrToken(@Param("query") String query, Pageable pageable);

    /**
     * Case-insensitive token lookup, used before creating a company so that
     * "Sprinto" and "sprinto" cannot become two rows for one board.
     */
    @Query("SELECT c FROM Company c WHERE c.atsPlatform = :platform "
            + "AND LOWER(c.boardToken) = LOWER(:token)")
    Optional<Company> findByPlatformAndTokenIgnoreCase(@Param("platform") AtsPlatform platform,
                                                       @Param("token") String token);

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
