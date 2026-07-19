package com.jobx.dto;

import com.jobx.entity.WatchedCompany;

/**
 * PATCH /watchlist/{id} body — pause/resume a watched company, or mark it
 * UNSUPPORTED (portal has no clean public API).
 */
public record UpdateWatchedCompanyStatusRequest(
        WatchedCompany.CompanyStatus status
) {
}
