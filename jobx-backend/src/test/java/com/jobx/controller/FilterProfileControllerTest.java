package com.jobx.controller;

import com.jobx.dto.FilterProfileRequest;
import com.jobx.entity.FilterProfile;
import com.jobx.entity.User;
import com.jobx.repository.FilterProfileRepository;
import com.jobx.service.MatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PUT /profile/filter must rescore the caller's existing feed — the gap this
 * pins (found live 2026-08-23): a profile saved after a board's first fetch
 * left the feed at zero forever, because matching only ran on brand-new jobs
 * and on watch-add backfill.
 */
class FilterProfileControllerTest {

    private FilterProfileRepository filterProfileRepository;
    private MatchingService matchingService;
    private FilterProfileController controller;
    private User user;

    @BeforeEach
    void setUp() {
        filterProfileRepository = mock(FilterProfileRepository.class);
        matchingService = mock(MatchingService.class);
        controller = new FilterProfileController(filterProfileRepository, matchingService);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("rescore@jobx.dev");

        when(filterProfileRepository.save(any(FilterProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void upsertRescoresTheFeedAgainstTheSavedProfile() {
        when(filterProfileRepository.findByUser(user)).thenReturn(Optional.empty());

        controller.upsert(user, new FilterProfileRequest(List.of("java"), List.of(), 2, 6));

        verify(matchingService).rescoreForWatcher(eq(user), any(FilterProfile.class));
    }

    @Test
    void rescoreRunsAgainstTheNewRulesNotTheOldOnes() {
        FilterProfile existing = new FilterProfile();
        existing.setUser(user);
        existing.setKeywords(List.of("python"));
        when(filterProfileRepository.findByUser(user)).thenReturn(Optional.of(existing));

        controller.upsert(user, new FilterProfileRequest(List.of("java"), List.of("sales"), null, null));

        verify(matchingService).rescoreForWatcher(eq(user), argThat(saved ->
                saved.getKeywords().equals(List.of("java"))
                        && saved.getExcludeWords().equals(List.of("sales"))));
    }

    @Test
    void aRejectedUpsertNeverRescores() {
        assertThrows(ResponseStatusException.class, () ->
                controller.upsert(user, new FilterProfileRequest(List.of("  "), List.of(), null, null)));

        verifyNoInteractions(matchingService);
        verify(filterProfileRepository, never()).save(any());
    }
}
