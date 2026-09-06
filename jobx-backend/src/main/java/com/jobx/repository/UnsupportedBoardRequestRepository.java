package com.jobx.repository;

import com.jobx.entity.UnsupportedBoardRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UnsupportedBoardRequestRepository
        extends JpaRepository<UnsupportedBoardRequest, UUID> {
}
