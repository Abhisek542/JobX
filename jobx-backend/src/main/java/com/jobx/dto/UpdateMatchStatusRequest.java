package com.jobx.dto;

import com.jobx.entity.Match;

/**
 * PATCH /matches/{id} body — dashboard sends this on card open (SEEN),
 * "Apply direct" click (APPLIED), or swipe-away (DISMISSED).
 */
public record UpdateMatchStatusRequest(
        Match.MatchStatus status
) {
}
