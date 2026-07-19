package com.jobx.dto;

import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;

import java.time.Instant;
import java.util.UUID;

public record WatchedCompanyResponse(
        UUID id,
        String companyName,
        AtsPlatform atsPlatform,
        String boardToken,
        WatchedCompany.CompanyStatus status,
        Instant lastFetchedAt,
        Instant createdAt
) {
    public static WatchedCompanyResponse from(WatchedCompany company) {
        return new WatchedCompanyResponse(
                company.getId(),
                company.getCompanyName(),
                company.getAtsPlatform(),
                company.getBoardToken(),
                company.getStatus(),
                company.getLastFetchedAt(),
                company.getCreatedAt()
        );
    }
}
