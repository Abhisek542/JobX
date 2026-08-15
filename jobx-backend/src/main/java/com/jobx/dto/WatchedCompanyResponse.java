package com.jobx.dto;

import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;

import java.time.Instant;
import java.util.UUID;

/**
 * lastFetchStatus drives the watchlist's health line: null = "not checked yet",
 * SUCCESS = "last checked {lastFetchedAt}", FAILED = the "Refresh issue"
 * warning state. The stored last_fetch_error is intentionally NOT exposed —
 * V1_IMPROVEMENTS.md keeps the raw cause server-side.
 */
public record WatchedCompanyResponse(
        UUID id,
        String companyName,
        AtsPlatform atsPlatform,
        String boardToken,
        WatchedCompany.CompanyStatus status,
        Instant lastFetchedAt,
        WatchedCompany.FetchStatus lastFetchStatus,
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
                company.getLastFetchStatus(),
                company.getCreatedAt()
        );
    }
}
