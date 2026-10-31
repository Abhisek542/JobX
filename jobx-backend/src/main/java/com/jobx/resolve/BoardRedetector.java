package com.jobx.resolve;

import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns "FAILED for days" into "looks like they moved to Lever. Switch?"
 * (add-company-improvements PR D).
 *
 * Board tokens rot. PhonePe's Greenhouse board went from 68 live jobs to a hard
 * 404 in six days because they moved to SmartRecruiters, and the board then sat
 * FAILED until a person went and found the new one. This does the looking:
 *
 *  - If the company has a {@code careers_url} (it was added in URL mode), sniff
 *    that page again, plus the same one-hop follow URL-mode resolve uses, and
 *    take any board it links other than the current one.
 *  - If that finds nothing, or there is no careers page, probe the other
 *    platforms with slugs derived from the display name.
 *
 * Only a board with at least one LIVE role counts. Every candidate here is a
 * guess about where a company went, so the empty-board exception from PR B
 * never applies.
 *
 * NOTHING IS SWITCHED HERE. The first candidate is stored on the company as a
 * proposal ({@code moved_to_*}), and a watcher confirms it with
 * POST /watchlist/{id}/move. That keeps the project's "never guess a board
 * token silently" rule.
 *
 * Not built yet, waiting on the PR E crawler: native to CUSTOM proposals (a
 * company that left its ATS for an in-house page), re-sniffing a failing CUSTOM
 * board's own URL, and the weekly CUSTOM to native ({@code NATIVE_AVAILABLE})
 * check on healthy crawled boards. They slot into {@link #findCandidate}.
 *
 * Not @Transactional, like the resolver: everything here is outbound HTTP, and
 * holding a pool connection across it is BUG_REPORT #6. The single write per
 * company is its own short save.
 */
@Component
@Slf4j
public class BoardRedetector {

    private final CompanyRepository companyRepository;
    private final FetcherRegistry fetcherRegistry;
    private final SafeUrlFetcher safeUrlFetcher;
    private final CareersLinkFollower careersLinkFollower;
    private final BoardProbe boardProbe;
    private final Clock clock;
    private final Duration failingFor;
    private final Duration retryAfter;
    private final int maxPerCycle;
    private final int maxSlugCandidates;
    private final int maxSniffedBoards;

    @Autowired
    public BoardRedetector(CompanyRepository companyRepository,
                           FetcherRegistry fetcherRegistry,
                           SafeUrlFetcher safeUrlFetcher,
                           CareersLinkFollower careersLinkFollower,
                           BoardProbe boardProbe,
                           @Value("${jobx.redetect.failing-for-hours:12}") long failingForHours,
                           @Value("${jobx.redetect.retry-after-hours:24}") long retryAfterHours,
                           @Value("${jobx.redetect.max-per-cycle:5}") int maxPerCycle,
                           @Value("${jobx.resolve.max-slug-candidates:4}") int maxSlugCandidates,
                           @Value("${jobx.resolve.max-sniffed-boards:3}") int maxSniffedBoards) {
        this(companyRepository, fetcherRegistry, safeUrlFetcher, careersLinkFollower, boardProbe,
                Clock.systemUTC(), failingForHours, retryAfterHours, maxPerCycle,
                maxSlugCandidates, maxSniffedBoards);
    }

    /** With an explicit clock, so tests can pin the 12h/24h gates. */
    BoardRedetector(CompanyRepository companyRepository,
                    FetcherRegistry fetcherRegistry,
                    SafeUrlFetcher safeUrlFetcher,
                    CareersLinkFollower careersLinkFollower,
                    BoardProbe boardProbe,
                    Clock clock,
                    long failingForHours,
                    long retryAfterHours,
                    int maxPerCycle,
                    int maxSlugCandidates,
                    int maxSniffedBoards) {
        this.companyRepository = companyRepository;
        this.fetcherRegistry = fetcherRegistry;
        this.safeUrlFetcher = safeUrlFetcher;
        this.careersLinkFollower = careersLinkFollower;
        this.boardProbe = boardProbe;
        this.clock = clock;
        this.failingFor = Duration.ofHours(failingForHours);
        this.retryAfter = Duration.ofHours(retryAfterHours);
        this.maxPerCycle = maxPerCycle;
        this.maxSlugCandidates = maxSlugCandidates;
        this.maxSniffedBoards = maxSniffedBoards;
    }

    /**
     * Called at the end of each fetch cycle with the boards that cycle polled
     * (the ones with an ACTIVE watcher). Picks the due ones, at most
     * {@code max-per-cycle}, the longest-unchecked first. Never throws: a
     * redetect problem must not look like a fetch-cycle problem.
     *
     * @return how many companies were checked
     */
    public int redetectDue(List<Company> polled) {
        Instant now = clock.instant();
        List<Company> due = polled.stream()
                .filter(company -> isDue(company, now))
                .sorted(Comparator.comparing(Company::getLastRedetectAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .limit(maxPerCycle)
                .toList();

        for (Company company : due) {
            try {
                redetect(company);
            } catch (Exception e) {
                log.warn("Redetect of {} ({}) failed: {}",
                        company.getDisplayName(), company.getAtsPlatform(), e.toString());
            }
        }
        return due.size();
    }

    /**
     * Failing for at least {@code failing-for-hours}, and not looked at in the
     * last {@code retry-after-hours}. Healthy boards are never due (until PR E
     * adds the weekly CUSTOM check).
     */
    boolean isDue(Company company, Instant now) {
        if (company.getLastFetchStatus() != Company.FetchStatus.FAILED
                || company.getFailingSince() == null
                || company.getFailingSince().isAfter(now.minus(failingFor))) {
            return false;
        }
        Instant last = company.getLastRedetectAt();
        return last == null || last.isBefore(now.minus(retryAfter));
    }

    /**
     * Look once and record the outcome. A candidate replaces any earlier
     * proposal; finding nothing clears it, because a proposal whose board has
     * since emptied out is no longer one a user should act on.
     */
    void redetect(Company company) {
        Optional<BoardRef> candidate = findCandidate(company);

        // Re-read before writing: the cycle's Company instance is minutes old,
        // and saving it whole could put back fetch-health fields that a
        // "Check now" changed in the meantime.
        Company fresh = companyRepository.findById(company.getId()).orElse(company);
        candidate.ifPresentOrElse(
                ref -> fresh.proposeMove(ref.platform(), ref.token(), Company.MoveReason.FAILING),
                fresh::clearMoveProposal);
        fresh.setLastRedetectAt(clock.instant());
        companyRepository.save(fresh);

        // Keep the caller's copy in step, so nothing later in the cycle saves
        // the stale values back.
        company.setMovedToPlatform(fresh.getMovedToPlatform());
        company.setMovedToToken(fresh.getMovedToToken());
        company.setMovedToReason(fresh.getMovedToReason());
        company.setLastRedetectAt(fresh.getLastRedetectAt());

        candidate.ifPresentOrElse(
                ref -> log.info("Redetect: {} ({} '{}') looks like it moved to {} '{}'",
                        company.getDisplayName(), company.getAtsPlatform(), company.getBoardToken(),
                        ref.platform(), ref.token()),
                () -> log.info("Redetect: no new board found for {} ({} '{}')",
                        company.getDisplayName(), company.getAtsPlatform(), company.getBoardToken()));
    }

    /** The first board with live roles that isn't the current one: sniff, then probe. */
    Optional<BoardRef> findCandidate(Company company) {
        BoardRef current = new BoardRef(company.getAtsPlatform(), company.getBoardToken());

        Optional<BoardRef> sniffed = sniffCareersPage(company.getCareersUrl(), current);
        if (sniffed.isPresent()) {
            return sniffed;
        }
        return probeOtherPlatforms(company.getDisplayName(), current);
    }

    /**
     * Every board the careers page (and its one-hop careers links) points at,
     * in page order, minus the current board, previewed until one has live roles.
     */
    private Optional<BoardRef> sniffCareersPage(String careersUrl, BoardRef current) {
        if (careersUrl == null || careersUrl.isBlank()) {
            return Optional.empty();
        }

        Optional<SafeUrlFetcher.FetchedPage> page;
        try {
            page = safeUrlFetcher.fetchPage(careersUrl);
        } catch (SafeUrlFetcher.UnsafeUrlException e) {
            // Stored from a user's input; the guard refusing it now (a host
            // that resolves somewhere private) just means no sniff.
            log.info("Redetect: not fetching careers page {}: {}", careersUrl, e.getMessage());
            return Optional.empty();
        }
        if (page.isEmpty()) {
            return Optional.empty();
        }

        Set<BoardRef> refs = new LinkedHashSet<>(AtsUrlParser.findAll(page.get().body()));
        if (refs.stream().noneMatch(ref -> isCandidate(ref, current))) {
            for (SafeUrlFetcher.FetchedPage next : careersLinkFollower.follow(page.get())) {
                refs.addAll(AtsUrlParser.findAll(next.body()));
            }
        }

        int previewed = 0;
        for (BoardRef ref : refs) {
            if (!isCandidate(ref, current)) {
                continue;
            }
            if (previewed++ >= maxSniffedBoards) {
                break;
            }
            if (hasLiveRoles(ref)) {
                return Optional.of(ref);
            }
        }
        return Optional.empty();
    }

    /**
     * Slugs from the display name, on every probeable platform except the
     * current one. The current platform is left out on purpose: the same slug
     * there is the board that is failing.
     */
    private Optional<BoardRef> probeOtherPlatforms(String displayName, BoardRef current) {
        List<String> tokens = SlugCandidates.from(displayName, maxSlugCandidates);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        List<AtsPlatform> platforms = new ArrayList<>(CompanyResolver.PROBEABLE);
        platforms.remove(current.platform());

        // BoardProbe only reports boards with at least one live posting.
        return boardProbe.probe(platforms, tokens).stream()
                .map(BoardProbe.Hit::ref)
                .filter(ref -> isCandidate(ref, current))
                .findFirst();
    }

    /** A watchable board that isn't the current one (tokens compared ignoring case). */
    private boolean isCandidate(BoardRef ref, BoardRef current) {
        boolean same = ref.platform() == current.platform()
                && ref.token().equalsIgnoreCase(current.token());
        return !same && fetcherRegistry.getFetcher(ref.platform()).isPresent();
    }

    private boolean hasLiveRoles(BoardRef ref) {
        Optional<AtsFetcher> fetcher = fetcherRegistry.getFetcher(ref.platform());
        if (fetcher.isEmpty()) {
            return false;
        }
        Company probe = new Company();
        probe.setAtsPlatform(ref.platform());
        probe.setBoardToken(ref.token());
        probe.setDisplayName(ref.token());
        try {
            BoardPreview preview = fetcher.get().previewBoard(probe);
            return preview.jobCount() > 0;
        } catch (Exception e) {
            log.debug("Redetect: no {} board at '{}': {}", ref.platform(), ref.token(), e.toString());
            return false;
        }
    }
}
