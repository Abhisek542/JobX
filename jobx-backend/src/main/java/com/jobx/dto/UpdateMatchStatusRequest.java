package com.jobx.dto;

import com.jobx.entity.Match;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /matches/{id} body — dashboard sends this on card open (SEEN),
 * "Apply direct" click (APPLIED), or swipe-away (DISMISSED).
 */
public record UpdateMatchStatusRequest(
        @NotNull(message = "status is required") Match.MatchStatus status
) {
}
