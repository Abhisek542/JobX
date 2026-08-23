package com.jobx.dto;

import com.jobx.entity.Company;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;

import java.time.Instant;
import java.util.UUID;

/**
 * lastFetchStatus drives the watchlist's health line: null = "not checked yet",
 * SUCCESS = "last checked {lastFetchedAt}", FAILED = the "Refresh issue"
 * warning state. The stored last_fetch_error is intentionally NOT exposed —
 * V1_IMPROVEMENTS.md keeps the raw cause server-side.
 *
 * JSON shape is unchanged by V4 — the Angular app needs no changes — but the
 * fields now resolve through the shared Company: companyName is the canonical
 * displayName, and the fetch-health trio is board-wide, not per-watch-row.
 */
public record WatchedCompanyResponse(
        UUID id,
        String companyName,
        AtsPlatform atsPlatform,
        String boardToken,
        WatchedCompany.CompanyStatus status,
        Instant lastFetchedAt,
        Company.FetchStatus lastFetchStatus,
        Instant createdAt
) {
    public static WatchedCompanyResponse from(WatchedCompany watch) {
        Company company = watch.getCompany();
        return new WatchedCompanyResponse(
                watch.getId(),
                company.getDisplayName(),
                company.getAtsPlatform(),
                company.getBoardToken(),
                watch.getStatus(),
                company.getLastFetchedAt(),
                company.getLastFetchStatus(),
                watch.getCreatedAt()
        );
    }
}
