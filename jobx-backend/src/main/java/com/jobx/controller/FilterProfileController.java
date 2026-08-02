package com.jobx.controller;

import com.jobx.dto.FilterProfileRequest;
import com.jobx.dto.FilterProfileResponse;
import com.jobx.entity.FilterProfile;
import com.jobx.entity.User;
import com.jobx.repository.FilterProfileRepository;
import com.jobx.util.TextLists;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * CRUD for the caller's single filter profile — the largest functional gap from
 * V1_IMPROVEMENTS.md P0: MatchScorer existed but users could only get a profile
 * via manual SQL. One profile per user (DB-enforced unique user_id); PUT upserts.
 *
 * Deleting the profile stops NEW matches from being created (the scheduler skips
 * users without a profile) but keeps existing Match rows.
 */
@RestController
@RequestMapping("/profile/filter")
@RequiredArgsConstructor
public class FilterProfileController {

    private final FilterProfileRepository filterProfileRepository;

    @GetMapping
    public FilterProfileResponse get(@AuthenticationPrincipal User user) {
        return FilterProfileResponse.from(requireProfile(user));
    }

    @PutMapping
    public FilterProfileResponse upsert(@AuthenticationPrincipal User user,
                                        @Valid @RequestBody FilterProfileRequest request) {
        List<String> keywords = TextLists.normalize(request.keywords());
        List<String> excludeWords = TextLists.normalize(request.excludeWords());

        // Matching is OR over keywords — an empty list would mean "match nothing",
        // which reads as a broken feed. Require at least one real keyword.
        if (keywords.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one keyword is required");
        }
        if (request.expMin() != null && request.expMax() != null
                && request.expMin() > request.expMax()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expMin must be <= expMax");
        }

        FilterProfile profile = filterProfileRepository.findByUser(user)
                .orElseGet(() -> {
                    FilterProfile created = new FilterProfile();
                    created.setUser(user);
                    return created;
                });

        profile.setKeywords(keywords);
        profile.setExcludeWords(excludeWords);
        profile.setExpMin(request.expMin());
        profile.setExpMax(request.expMax());
        profile.setUpdatedAt(Instant.now());

        return FilterProfileResponse.from(filterProfileRepository.save(profile));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User user) {
        filterProfileRepository.delete(requireProfile(user));
    }

    private FilterProfile requireProfile(User user) {
        return filterProfileRepository.findByUser(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no filter profile yet"));
    }
}
