package com.jobx.controller;

import com.jobx.dto.UpdateWatchedCompanyStatusRequest;
import com.jobx.dto.WatchedCompanyRequest;
import com.jobx.dto.WatchedCompanyResponse;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.repository.WatchedCompanyRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Real CRUD for the watchlist (Phase 2's last open item, per CLAUDE.md /
 * JobX-plan) — replaces manually poking the DB during dev.
 */
@RestController
@RequestMapping("/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchedCompanyRepository watchedCompanyRepository;

    @GetMapping
    public List<WatchedCompanyResponse> list(@AuthenticationPrincipal User user) {
        return watchedCompanyRepository.findByUser(user).stream()
                .map(WatchedCompanyResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchedCompanyResponse add(@AuthenticationPrincipal User user,
                                      @Valid @RequestBody WatchedCompanyRequest request) {
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
    public WatchedCompanyResponse updateStatus(@PathVariable UUID id, @AuthenticationPrincipal User user,
                                                @Valid @RequestBody UpdateWatchedCompanyStatusRequest request) {
        WatchedCompany company = requireOwnedCompany(id, user);
        company.setStatus(request.status());
        return WatchedCompanyResponse.from(watchedCompanyRepository.save(company));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        WatchedCompany company = requireOwnedCompany(id, user);
        // Cascades to jobs (and matches on those jobs) per V1__create_schema.sql ON DELETE CASCADE.
        watchedCompanyRepository.delete(company);
    }

    private WatchedCompany requireOwnedCompany(UUID id, User user) {
        WatchedCompany company = watchedCompanyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "watched company not found"));
        if (!company.getUser().getId().equals(user.getId())) {
            // 404 rather than 403 — don't reveal that another user's row exists
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "watched company not found");
        }
        return company;
    }
}
