package com.jobx.controller;

import com.jobx.dto.MatchResponse;
import com.jobx.dto.UpdateMatchStatusRequest;
import com.jobx.entity.Match;
import com.jobx.entity.User;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
 *
 * No auth yet — same userId-as-query-param caveat as WatchlistController.
 */
@RestController
@RequestMapping("/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchRepository matchRepository;
    private final UserRepository userRepository;

    @GetMapping
    public List<MatchResponse> list(@RequestParam UUID userId) {
        User user = requireUser(userId);
        return matchRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(MatchResponse::from)
                .toList();
    }

    @PatchMapping("/{id}")
    public MatchResponse updateStatus(@PathVariable UUID id, @RequestParam UUID userId,
                                       @RequestBody UpdateMatchStatusRequest request) {
        Match match = requireOwnedMatch(id, userId);
        if (request.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        match.setStatus(request.status());
        return MatchResponse.from(matchRepository.save(match));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    private Match requireOwnedMatch(UUID id, UUID userId) {
        Match match = matchRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "match not found"));
        if (!match.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "match not found");
        }
        return match;
    }
}
