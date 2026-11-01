package com.jobx.dto;

import com.jobx.enums.AtsPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /watchlist/{id}/move body: the proposed board the user saw on the
 * "seems to have moved" banner and is confirming. It must still match the
 * company's current proposal, so a click can never switch to a board the user
 * wasn't shown.
 */
public record MoveWatchRequest(
        @NotNull(message = "atsPlatform is required") AtsPlatform atsPlatform,
        @NotBlank(message = "boardToken is required") String boardToken
) {
}
