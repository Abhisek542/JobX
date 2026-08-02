package com.jobx.dto;

import com.jobx.entity.WatchedCompany;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /watchlist/{id} body — pause/resume a watched company, or mark it
 * UNSUPPORTED (portal has no clean public API).
 */
public record UpdateWatchedCompanyStatusRequest(
        @NotNull(message = "status is required") WatchedCompany.CompanyStatus status
) {
}
