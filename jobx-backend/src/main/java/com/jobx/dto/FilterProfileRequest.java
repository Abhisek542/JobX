package com.jobx.dto;

import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * PUT /profile/filter body — creates or replaces the caller's filter profile.
 * Lists are normalized server-side (trim, drop blanks, case-insensitive dedupe);
 * at least one keyword must survive normalization. excludeWords may be empty.
 */
public record FilterProfileRequest(
        List<String> keywords,
        List<String> excludeWords,
        @PositiveOrZero(message = "expMin must be >= 0") Integer expMin,
        @PositiveOrZero(message = "expMax must be >= 0") Integer expMax
) {
}
