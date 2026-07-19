package com.jobx.controller;

import com.jobx.dto.UpdateWatchedCompanyStatusRequest;
import com.jobx.dto.WatchedCompanyRequest;
import com.jobx.dto.WatchedCompanyResponse;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.repository.UserRepository;
import com.jobx.repository.WatchedCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Real CRUD for the watchlist (Phase 2's last open item, per CLAUDE.md /
 * JobX-plan) — replaces manually poking the DB during dev.
 *
 * No auth yet (Phase 4 not started, SecurityConfig still permitAll()s
 * everything): userId is taken as an explicit query param instead of an
 * authenticated principal. Swap requireUser()'s source for the real
 * principal once Spring Security lands.
 */
@RestController
@RequestMapping("/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchedCompanyRepository watchedCompanyRepository;
    private final UserRepository userRepository;

    @GetMapping
    public List<WatchedCompanyResponse> list(@RequestParam UUID userId) {
        User user = requireUser(userId);
        return watchedCompanyRepository.findByUser(user).stream()
                .map(WatchedCompanyResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchedCompanyResponse add(@RequestParam UUID userId, @RequestBody WatchedCompanyRequest request) {
        User user = requireUser(userId);

        if (isBlank(request.companyName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "companyName is required");
        }
        if (request.atsPlatform() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "atsPlatform is required");
        }
        if (isBlank(request.boardToken())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "boardToken is required — never guess it, read it from the live careers URL");
        }

        WatchedCompany company = new WatchedCompany();
        company.setUser(user);
        company.setCompanyName(request.companyName());
        company.setAtsPlatform(request.atsPlatform());
        company.setBoardToken(request.boardToken());
        // New watches start ACTIVE so FetchScheduler picks them up on the next 30-min cycle.
        company.setStatus(WatchedCompany.CompanyStatus.ACTIVE);

        try {
            return WatchedCompanyResponse.from(watchedCompanyRepository.save(company));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // unique (user_id, board_token, ats_platform) constraint
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already watching this company on this ATS");
        }
    }

    @PatchMapping("/{id}")
    public WatchedCompanyResponse updateStatus(@PathVariable UUID id, @RequestParam UUID userId,
                                                @RequestBody UpdateWatchedCompanyStatusRequest request) {
        WatchedCompany company = requireOwnedCompany(id, userId);
        if (request.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        company.setStatus(request.status());
        return WatchedCompanyResponse.from(watchedCompanyRepository.save(company));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id, @RequestParam UUID userId) {
        WatchedCompany company = requireOwnedCompany(id, userId);
        // Cascades to jobs (and matches on those jobs) per V1__create_schema.sql ON DELETE CASCADE.
        watchedCompanyRepository.delete(company);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    private WatchedCompany requireOwnedCompany(UUID id, UUID userId) {
        WatchedCompany company = watchedCompanyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "watched company not found"));
        if (!company.getUser().getId().equals(userId)) {
            // 404 rather than 403 — don't reveal that another user's row exists
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "watched company not found");
        }
        return company;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
