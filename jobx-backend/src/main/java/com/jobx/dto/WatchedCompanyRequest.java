package com.jobx.dto;

import com.jobx.enums.AtsPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /watchlist body — adds a new company to watch.
 * status is always ACTIVE on creation; use PATCH /watchlist/{id} to pause it.
 */
public record WatchedCompanyRequest(
        @NotBlank(message = "companyName is required") String companyName,
        @NotNull(message = "atsPlatform is required") AtsPlatform atsPlatform,
        @NotBlank(message = "boardToken is required — never guess it, read it from the live careers URL")
        String boardToken
) {
}
