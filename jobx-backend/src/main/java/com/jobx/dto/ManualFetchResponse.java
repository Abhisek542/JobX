package com.jobx.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * POST /watchlist/{id}/fetch response — everything the dashboard's "Check now"
 * button needs to render feedback: "Checked just now; 3 new matches" when
 * newMatches > 0, "No new roles" when newJobs == 0.
 */
public record ManualFetchResponse(
        UUID companyId,
        String companyName,
        Instant checkedAt,
        int newJobs,
        int newMatches
) {
}
