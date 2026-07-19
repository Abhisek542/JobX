package com.jobx.repository;

import com.jobx.entity.FilterProfile;
import com.jobx.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FilterProfileRepository extends JpaRepository<FilterProfile, UUID> {
    Optional<FilterProfile> findByUser(User user);
}
