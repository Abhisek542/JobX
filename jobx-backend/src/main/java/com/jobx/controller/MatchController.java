package com.jobx.controller;

import com.jobx.dto.MatchResponse;
import com.jobx.dto.UpdateMatchStatusRequest;
import com.jobx.entity.Match;
import com.jobx.entity.User;
import com.jobx.repository.MatchRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Real CRUD for the match feed (Phase 2's last open item, per CLAUDE.md /
 * JobX-plan) — replaces reading /dev/backfill-matches output by hand.
 *
 * Matches are only ever created by FetchScheduler after a poll cycle; this
 * controller is read + status-update only (dashboard alert feed: mark
 * seen/applied/dismissed).
 */
@RestController
@RequestMapping("/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchRepository matchRepository;

    @GetMapping
    public List<MatchResponse> list(@AuthenticationPrincipal User user) {
        return matchRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(MatchResponse::from)
                .toList();
    }

    @PatchMapping("/{id}")
    public MatchResponse updateStatus(@PathVariable UUID id, @AuthenticationPrincipal User user,
                                       @Valid @RequestBody UpdateMatchStatusRequest request) {
        Match match = requireOwnedMatch(id, user);
        match.setStatus(request.status());
        return MatchResponse.from(matchRepository.save(match));
    }

    private Match requireOwnedMatch(UUID id, User user) {
        Match match = matchRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "match not found"));
        if (!match.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "match not found");
        }
        return match;
    }
}
