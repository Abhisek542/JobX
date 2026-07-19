package com.jobx.dto;

import com.jobx.enums.AtsPlatform;

/**
 * POST /watchlist body — adds a new company to watch.
 * status is always ACTIVE on creation; use PATCH /watchlist/{id} to pause it.
 */
public record WatchedCompanyRequest(
        String companyName,
        AtsPlatform atsPlatform,
        String boardToken
) {
}
