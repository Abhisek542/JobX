package com.jobx.service;

import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.entity.WatchedCompany;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.MatchRepository;
import com.jobx.repository.WatchedCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The write half of POST /watchlist, kept apart from the controller so that the
 * board-validation HTTP call that sits between the two writes can run with no
 * transaction open.
 *
 * Adding a watch used to be one @Transactional controller method, so an ATS
 * round trip (validateBoard) held one of the ten pool connections for its whole
 * duration — BUG_REPORT #6. The orchestration stays in WatchlistController;
 * only these two short transactions live here, alongside MatchingService, which
 * is where this project keeps @Transactional.
 *
 * The split costs the add its all-or-nothing property: a company row created for
 * a brand-new board survives a later failure of the watch insert. That is
 * harmless by existing design — the scheduler skips boards with no ACTIVE
 * watcher, so an orphan company costs nothing and re-adding it later starts with
 * a warm job history (the V4 rework's deliberate call).
 */
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final CompanyRepository companyRepository;
    private final WatchedCompanyRepository watchedCompanyRepository;
    private final MatchingService matchingService;
    private final MatchRepository matchRepository;

    /**
     * Persist a board nobody watches yet. The caller has already proved the token
     * is real; this is the write on its own, so that proof is not paid for with a
     * held connection.
     *
     * Throws DataIntegrityViolationException on the (ats_platform, board_token)
     * race — two users adding the same brand-new board in the same instant.
     * Unlike the old single-transaction version, the caller can recover from it:
     * this transaction is finished by the time it surfaces, so re-reading finds
     * the winner's row.
     */
    @Transactional
    public Company createCompany(Company candidate) {
        return companyRepository.saveAndFlush(candidate);
    }

    /**
     * Start watching a board and score what it already holds.
     *
     * Backfill: if the board already has jobs (someone else was watching it
     * first), score them for this user now — without this, a second watcher's
     * feed stays empty until the board posts something NEW.
     *
     * Throws DataIntegrityViolationException on the unique (user_id, company_id)
     * constraint; the controller maps that to 409.
     */
    @Transactional
    public WatchedCompany watchAndBackfill(User user, Company company) {
        WatchedCompany watch = new WatchedCompany();
        watch.setUser(user);
        watch.setCompany(company);
        // New watches start ACTIVE so FetchScheduler picks the board up on the next 30-min cycle.
        watch.setStatus(WatchedCompany.CompanyStatus.ACTIVE);

        WatchedCompany saved = watchedCompanyRepository.saveAndFlush(watch);
        matchingService.backfillForWatcher(user, company);
        return saved;
    }

    /**
     * Point a user's watch at the board their company moved to (PR D,
     * POST /watchlist/{id}/move), in one transaction.
     *
     * The user's matches on the old board are deleted, the same as unwatching
     * it. The watch row itself is kept and re-pointed, so its id and its
     * ACTIVE/PAUSED status survive the switch. If the user somehow already
     * watches the new board too, the old row is simply dropped and the existing
     * one returned.
     *
     * The old company row stays, like any orphan: the scheduler skips it once
     * nobody watches it, and other watchers still see the proposal on it.
     */
    @Transactional
    public WatchedCompany moveWatch(User user, WatchedCompany watch, Company target) {
        Company old = watch.getCompany();
        matchRepository.deleteByUserAndCompany(user, old);

        Optional<WatchedCompany> existing = watchedCompanyRepository.findByUserAndCompany(user, target);
        if (existing.isPresent()) {
            watchedCompanyRepository.delete(watch);
            return existing.get();
        }

        watch.setCompany(target);
        WatchedCompany saved = watchedCompanyRepository.saveAndFlush(watch);
        matchingService.backfillForWatcher(user, target);
        return saved;
    }
}
