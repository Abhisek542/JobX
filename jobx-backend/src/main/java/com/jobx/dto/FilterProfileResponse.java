package com.jobx.dto;

import com.jobx.entity.FilterProfile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FilterProfileResponse(
        UUID id,
        List<String> keywords,
        List<String> excludeWords,
        Integer expMin,
        Integer expMax,
        Instant updatedAt
) {
    public static FilterProfileResponse from(FilterProfile profile) {
        return new FilterProfileResponse(
                profile.getId(),
                profile.getKeywords(),
                profile.getExcludeWords(),
                profile.getExpMin(),
                profile.getExpMax(),
                profile.getUpdatedAt()
        );
    }
}
