package com.jobx.repository;

import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WatchedCompanyRepository extends JpaRepository<WatchedCompany, UUID> {
    List<WatchedCompany> findByStatus(WatchedCompany.CompanyStatus status);
    List<WatchedCompany> findByUser(User user);
}
