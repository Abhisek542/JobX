package com.jobx.controller;

import com.jobx.dto.ManualFetchResponse;
import com.jobx.dto.UpdateWatchedCompanyStatusRequest;
import com.jobx.dto.WatchedCompanyRequest;
import com.jobx.dto.WatchedCompanyResponse;
import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.WatchedCompanyRepository;
import com.jobx.scheduler.FetchScheduler;
import com.jobx.service.MatchingService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Real CRUD for the watchlist. Since V4 a watch row is a subscription to a
 * shared Company board (get-or-create by platform+token), so adding, removing
 * and manually fetching all changed semantics:
 *  - add: joins the existing company if someone already watches it (the
 *    company keeps the FIRST adder's display name — canonical, per Abhisek's
 *    2026-08-23 call), and backfills this user's matches from jobs the board
 *    already has, because the pre-V4 "new watcher re-fetches everything"
 *    accident is gone.
 *  - remove: deletes only THIS user's matches explicitly — jobs are shared
 *    now, so no cascade may touch them.
 *  - fetch: the cooldown anchor (company.lastFetchedAt) is shared by all
 *    watchers. A recent successful check by anyone returns 200 with zeros
 *    ("the board really was just checked" is the honest answer); 429 is kept
 *    only for a recently FAILED attempt, so users can't hammer a broken board.
 */
@Slf4j
@RestController
@RequestMapping("/watchlist")
public class WatchlistController {

    private final WatchedCompanyRepository watchedCompanyRepository;
    private final CompanyRepository companyRepository;
    private final MatchRepository matchRepository;
    private final MatchingService matchingService;
    private final FetchScheduler fetchScheduler;
    private final FetcherRegistry fetcherRegistry;
    private final long manualCooldownMs;

    public WatchlistController(WatchedCompanyRepository watchedCompanyRepository,
                               CompanyRepository companyRepository,
                               MatchRepository matchRepository,
                               MatchingService matchingService,
                               FetchScheduler fetchScheduler,
                               FetcherRegistry fetcherRegistry,
                               @Value("${jobx.fetch.manual-cooldown-ms:300000}") long manualCooldownMs) {
        this.watchedCompanyRepository = watchedCompanyRepository;
        this.companyRepository = companyRepository;
        this.matchRepository = matchRepository;
        this.matchingService = matchingService;
        this.fetchScheduler = fetchScheduler;
        this.fetcherRegistry = fetcherRegistry;
        this.manualCooldownMs = manualCooldownMs;
    }

    @GetMapping
    public List<WatchedCompanyResponse> list(@AuthenticationPrincipal User user) {
        return watchedCompanyRepository.findByUser(user).stream()
                .map(WatchedCompanyResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public WatchedCompanyResponse add(@AuthenticationPrincipal User user,
                                      @Valid @RequestBody WatchedCompanyRequest request) {
        Company company = getOrCreateCompany(request);

        WatchedCompany watch = new WatchedCompany();
        watch.setUser(user);
        watch.setCompany(company);
        // New watches start ACTIVE so FetchScheduler picks the board up on the next 30-min cycle.
        watch.setStatus(WatchedCompany.CompanyStatus.ACTIVE);

        WatchedCompany saved;
        try {
            saved = watchedCompanyRepository.saveAndFlush(watch);
        } catch (DataIntegrityViolationException e) {
            // unique (user_id, company_id) constraint
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already watching this company on this ATS");
        }

        // Backfill: if the board already has jobs (someone else was watching it
        // first), score them for this user now — without this, a second watcher's
        // feed stays empty until the board posts something NEW.
        matchingService.backfillForWatcher(user, company);

        return WatchedCompanyResponse.from(saved);
    }

    @PatchMapping("/{id}")
    public WatchedCompanyResponse updateStatus(@PathVariable UUID id, @AuthenticationPrincipal User user,
                                                @Valid @RequestBody UpdateWatchedCompanyStatusRequest request) {
        WatchedCompany watch = requireOwnedWatch(id, user);
        watch.setStatus(request.status());
        return WatchedCompanyResponse.from(watchedCompanyRepository.save(watch));
    }

    /**
     * Manual "Check now" — same fetch/dedup/scoring flow as the scheduler.
     * The cooldown rides on the shared company.lastFetchedAt: a board polled by
     * the scheduler — or checked by ANY watcher — moments ago is "fresh".
     */
    @PostMapping("/{id}/fetch")
    public ManualFetchResponse fetchNow(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        WatchedCompany watch = requireOwnedWatch(id, user);
        Company company = watch.getCompany();

        if (watch.getStatus() != WatchedCompany.CompanyStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "company is " + watch.getStatus() + " — only ACTIVE companies can be checked");
        }

        Instant lastFetched = company.getLastFetchedAt();
        if (lastFetched != null) {
            long sinceMs = Duration.between(lastFetched, Instant.now()).toMillis();
            if (sinceMs < manualCooldownMs) {
                // Someone (any watcher, or the scheduler) checked this board
                // moments ago. If that check succeeded, "checked just now, no
                // new roles" is simply true — say so with a 200 instead of
                // punishing user B for user A's click. 429 stays for a recent
                // FAILURE so a broken board can't be hammered.
                if (company.getLastFetchStatus() == Company.FetchStatus.SUCCESS) {
                    return new ManualFetchResponse(watch.getId(), company.getDisplayName(),
                            lastFetched, 0, 0);
                }
                long retryInSeconds = (manualCooldownMs - sinceMs + 999) / 1000;
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "checked recently — try again in " + retryInSeconds + "s");
            }
        }

        FetchScheduler.FetchResult result = fetchScheduler.fetchCompany(company, user);

        // Don't dress a failed fetch up as "no new roles" — the attempt is already
        // recorded as FAILED (its own transaction, committed), so say so.
        if (result.failed()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "couldn't reach the " + company.getAtsPlatform()
                            + " board for " + company.getDisplayName() + " — we'll keep retrying");
        }

        return new ManualFetchResponse(watch.getId(), company.getDisplayName(),
                Instant.now(), result.newJobs(), result.newMatchesForOwner());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remove(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        WatchedCompany watch = requireOwnedWatch(id, user);
        // V4: jobs are shared per board, so nothing cascades from a watch row
        // any more. Delete this user's matches on the board explicitly (pre-V4
        // the schema's ON DELETE CASCADE did this — and wiped OTHER users'
        // matches with it, which is exactly what V4 exists to prevent).
        matchRepository.deleteByUserAndCompany(user, watch.getCompany());
        watchedCompanyRepository.delete(watch);
        // The Company row (and its jobs) is deliberately left in place even if
        // this was the last watcher: the scheduler skips companies without
        // active watchers, so an orphaned board costs nothing, and re-adding it
        // later starts with a warm job history instead of an empty board.
    }

    /**
     * Company rows are shared and keyed (ats_platform, board_token). First
     * adder's companyName becomes the canonical displayName; later adders'
     * typed names are discarded — one shared label, no cross-user leakage.
     */
    private Company getOrCreateCompany(WatchedCompanyRequest request) {
        return companyRepository
                .findByAtsPlatformAndBoardToken(request.atsPlatform(), request.boardToken())
                .orElseGet(() -> {
                    Company company = new Company();
                    company.setAtsPlatform(request.atsPlatform());
                    company.setBoardToken(request.boardToken());
                    company.setDisplayName(request.companyName());

                    // Only for a board nobody watches yet: prove the token is
                    // real before it becomes a row. Most platforms 404 a bad
                    // token so their fetchers no-op here, but SmartRecruiters
                    // answers a nonsense company id with an empty 200 — without
                    // this check a typo would sit on the watchlist looking
                    // perfectly healthy and simply never produce a job.
                    validateBoard(company);

                    // Two users adding the same brand-new board in the same
                    // instant: the loser hits the (ats_platform, board_token)
                    // unique constraint and the request fails — recovery inside
                    // this transaction is impossible (flush failure marks it
                    // rollback-only), and a retry simply joins the winner's row.
                    return companyRepository.saveAndFlush(company);
                });
    }

    /**
     * 400, not 502: at add time the likely cause is a mistyped board token in
     * something the user just pasted, and it is the one thing they can fix. A
     * board that breaks later still reports 502 through the fetch path.
     */
    private void validateBoard(Company company) {
        AtsFetcher fetcher = fetcherRegistry.getFetcher(company.getAtsPlatform()).orElse(null);
        if (fetcher == null) {
            return; // UNSUPPORTED and friends — nothing to check against
        }
        try {
            fetcher.validateBoard(company);
        } catch (AtsFetchException e) {
            log.info("Rejected watch on {} board '{}': {}",
                    company.getAtsPlatform(), company.getBoardToken(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "we couldn't find a " + company.getAtsPlatform() + " board called '"
                            + company.getBoardToken() + "' — check the company ID in your careers URL");
        }
    }

    private WatchedCompany requireOwnedWatch(UUID id, User user) {
        WatchedCompany watch = watchedCompanyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "watched company not found"));
        if (!watch.getUser().getId().equals(user.getId())) {
            // 404 rather than 403 — don't reveal that another user's row exists
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "watched company not found");
        }
        return watch;
    }
}
