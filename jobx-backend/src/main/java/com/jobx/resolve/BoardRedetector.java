package com.jobx.resolve;

import com.jobx.crawler.BrowserRenderer;
import com.jobx.crawler.CustomCrawler;
import com.jobx.crawler.PageSignals;
import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
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
 * (add-company-improvements PR D, CUSTOM directions from PR E).
 *
 * Board tokens rot. PhonePe's Greenhouse board went from 68 live jobs to a hard
 * 404 in six days because they moved to SmartRecruiters, and the board then sat
 * FAILED until a person went and found the new one. This does the looking.
 *
 * Two kinds of board are due:
 *  - a board FAILED for {@code failing-for-hours}, looked at no more than once
 *    every {@code retry-after-hours};
 *  - a HEALTHY {@code CUSTOM} (crawled) board, once every
 *    {@code custom-check-days}: a company can adopt a real ATS at any time, and
 *    native API data always beats crawled data.
 *
 * What it looks at, in order, stopping at the first board with live roles that
 * isn't the current one:
 *  1. the careers page (a CUSTOM board's own URL, else {@code careers_url}):
 *     static SNIFF, the one-hop follow, then a rendered SNIFF if the page is
 *     JavaScript-driven;
 *  2. failing boards only: a name probe on the other probeable platforms;
 *  3. failing boards only: the crawler on that careers page and its one-hop
 *     pages. Jobs there become a CUSTOM proposal: a company that left its ATS
 *     for an in-house page, or a crawled page that moved.
 *
 * Only a board with at least one LIVE role counts. Every candidate here is a
 * guess about where a company went, so the empty-board exceptions (PR B, and a
 * crawled page that says "no openings") never apply.
 *
 * NOTHING IS SWITCHED HERE. The candidate is stored on the company as a
 * proposal ({@code moved_to_*}) with its reason — FAILING, or NATIVE_AVAILABLE
 * for a healthy crawled board — and a watcher confirms it with
 * POST /watchlist/{id}/move. That keeps the project's "never guess a board
 * token silently" rule.
 *
 * Not @Transactional, like the resolver: everything here is outbound HTTP, and
 * holding a pool connection across it is BUG_REPORT #6. The single write per
 * company is its own short save.
 */
@Component
@Slf4j
public class BoardRedetector {

    /** A board this company may have moved to, and why it is being proposed. */
    record Proposal(BoardRef board, Company.MoveReason reason) {
    }

    /** What reading the careers page produced, kept so the crawl step reuses it. */
    private record PageLook(Optional<BoardRef> nativeBoard,
                            Optional<SafeUrlFetcher.FetchedPage> page,
                            List<SafeUrlFetcher.FetchedPage> hops,
                            BrowserRenderer.Rendered rendered) {
        static final PageLook NOTHING = new PageLook(Optional.empty(), Optional.empty(), List.of(), null);
    }

    private final CompanyRepository companyRepository;
    private final FetcherRegistry fetcherRegistry;
    private final SafeUrlFetcher safeUrlFetcher;
    private final CareersLinkFollower careersLinkFollower;
    private final BoardProbe boardProbe;
    private final CustomCrawler customCrawler;
    private final Clock clock;
    private final Duration failingFor;
    private final Duration retryAfter;
    private final Duration customCheckEvery;
    private final int maxPerCycle;
    private final int maxSlugCandidates;
    private final int maxSniffedBoards;

    @Autowired
    public BoardRedetector(CompanyRepository companyRepository,
                           FetcherRegistry fetcherRegistry,
                           SafeUrlFetcher safeUrlFetcher,
                           CareersLinkFollower careersLinkFollower,
                           BoardProbe boardProbe,
                           CustomCrawler customCrawler,
                           @Value("${jobx.redetect.failing-for-hours:12}") long failingForHours,
                           @Value("${jobx.redetect.retry-after-hours:24}") long retryAfterHours,
                           @Value("${jobx.redetect.custom-check-days:7}") long customCheckDays,
                           @Value("${jobx.redetect.max-per-cycle:5}") int maxPerCycle,
                           @Value("${jobx.resolve.max-slug-candidates:4}") int maxSlugCandidates,
                           @Value("${jobx.resolve.max-sniffed-boards:3}") int maxSniffedBoards) {
        this(companyRepository, fetcherRegistry, safeUrlFetcher, careersLinkFollower, boardProbe, customCrawler,
                Clock.systemUTC(), failingForHours, retryAfterHours, customCheckDays, maxPerCycle,
                maxSlugCandidates, maxSniffedBoards);
    }

    /** With an explicit clock, so tests can pin the 12h/24h/7-day gates. */
    BoardRedetector(CompanyRepository companyRepository,
                    FetcherRegistry fetcherRegistry,
                    SafeUrlFetcher safeUrlFetcher,
                    CareersLinkFollower careersLinkFollower,
                    BoardProbe boardProbe,
                    CustomCrawler customCrawler,
                    Clock clock,
                    long failingForHours,
                    long retryAfterHours,
                    long customCheckDays,
                    int maxPerCycle,
                    int maxSlugCandidates,
                    int maxSniffedBoards) {
        this.companyRepository = companyRepository;
        this.fetcherRegistry = fetcherRegistry;
        this.safeUrlFetcher = safeUrlFetcher;
        this.careersLinkFollower = careersLinkFollower;
        this.boardProbe = boardProbe;
        this.customCrawler = customCrawler;
        this.clock = clock;
        this.failingFor = Duration.ofHours(failingForHours);
        this.retryAfter = Duration.ofHours(retryAfterHours);
        this.customCheckEvery = Duration.ofDays(customCheckDays);
        this.maxPerCycle = maxPerCycle;
        this.maxSlugCandidates = maxSlugCandidates;
        this.maxSniffedBoards = maxSniffedBoards;
    }

    /**
     * Called at the end of each fetch cycle with the boards that cycle polled
     * (the ones with an ACTIVE watcher). Picks the due ones, at most
     * {@code max-per-cycle} across both kinds, the longest-unchecked first.
     * Never throws: a redetect problem must not look like a fetch-cycle problem.
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
     * A failing board: FAILED for at least {@code failing-for-hours}, and not
     * looked at in the last {@code retry-after-hours}.
     *
     * A healthy CUSTOM board: not looked at in {@code custom-check-days}. A board
     * never looked at counts from when it was added, not as overdue: the
     * resolver sniffed that same page for a native board moments before the
     * board was created.
     */
    boolean isDue(Company company, Instant now) {
        if (company.getLastFetchStatus() == Company.FetchStatus.FAILED) {
            if (company.getFailingSince() == null || company.getFailingSince().isAfter(now.minus(failingFor))) {
                return false;
            }
            Instant last = company.getLastRedetectAt();
            return last == null || last.isBefore(now.minus(retryAfter));
        }
        if (company.getAtsPlatform() == AtsPlatform.CUSTOM
                && company.getLastFetchStatus() == Company.FetchStatus.SUCCESS) {
            Instant last = company.getLastRedetectAt() != null ? company.getLastRedetectAt() : company.getCreatedAt();
            return last == null || last.isBefore(now.minus(customCheckEvery));
        }
        return false;
    }

    /**
     * Look once and record the outcome. A candidate replaces any earlier
     * proposal; finding nothing clears it, because a proposal whose board has
     * since emptied out (or a native board a crawled page no longer links) is no
     * longer one a user should act on.
     */
    void redetect(Company company) {
        Optional<Proposal> candidate = findCandidate(company);

        // Re-read before writing: the cycle's Company instance is minutes old,
        // and saving it whole could put back fetch-health fields that a
        // "Check now" changed in the meantime.
        Company fresh = companyRepository.findById(company.getId()).orElse(company);
        candidate.ifPresentOrElse(
                p -> fresh.proposeMove(p.board().platform(), p.board().token(), p.reason()),
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
                p -> log.info("Redetect: {} ({} '{}') -> {} '{}' ({})",
                        company.getDisplayName(), company.getAtsPlatform(), company.getBoardToken(),
                        p.board().platform(), p.board().token(), p.reason()),
                () -> log.info("Redetect: no new board found for {} ({} '{}')",
                        company.getDisplayName(), company.getAtsPlatform(), company.getBoardToken()));
    }

    /** The first board with live roles that isn't the current one, and why. */
    Optional<Proposal> findCandidate(Company company) {
        BoardRef current = new BoardRef(company.getAtsPlatform(), company.getBoardToken());
        boolean failing = company.getLastFetchStatus() == Company.FetchStatus.FAILED;
        String pageUrl = company.getAtsPlatform() == AtsPlatform.CUSTOM
                ? company.getBoardToken()
                : company.getCareersUrl();

        // 1. The careers page names a native board.
        PageLook look = lookAtCareersPage(pageUrl, current);
        if (look.nativeBoard().isPresent()) {
            Company.MoveReason reason = failing ? Company.MoveReason.FAILING : Company.MoveReason.NATIVE_AVAILABLE;
            return look.nativeBoard().map(ref -> new Proposal(ref, reason));
        }
        if (!failing) {
            // A healthy crawled board only ever gets a native upgrade.
            return Optional.empty();
        }

        // 2. A name probe on the other platforms.
        Optional<BoardRef> probed = probeOtherPlatforms(company.getDisplayName(), current);
        if (probed.isPresent()) {
            return probed.map(ref -> new Proposal(ref, Company.MoveReason.FAILING));
        }

        // 3. The crawler: an in-house page with live jobs, other than this board.
        return crawlCareersPage(look, current).map(ref -> new Proposal(ref, Company.MoveReason.FAILING));
    }

    /**
     * Every native board the careers page (its one-hop careers links, and its
     * rendered DOM when it is JavaScript-built) points at, in page order, minus
     * the current board, previewed until one has live roles.
     */
    private PageLook lookAtCareersPage(String careersUrl, BoardRef current) {
        if (careersUrl == null || careersUrl.isBlank()) {
            return PageLook.NOTHING;
        }

        Optional<SafeUrlFetcher.FetchedPage> page;
        try {
            page = safeUrlFetcher.fetchPage(careersUrl);
        } catch (SafeUrlFetcher.UnsafeUrlException e) {
            // Stored from a user's input; the guard refusing it now (a host
            // that resolves somewhere private) just means no sniff.
            log.info("Redetect: not fetching careers page {}: {}", careersUrl, e.getMessage());
            return PageLook.NOTHING;
        }
        if (page.isEmpty()) {
            return PageLook.NOTHING;
        }

        Set<BoardRef> refs = new LinkedHashSet<>(AtsUrlParser.findAll(page.get().body()));
        List<SafeUrlFetcher.FetchedPage> hops = List.of();
        if (refs.stream().noneMatch(ref -> isCandidate(ref, current))) {
            hops = careersLinkFollower.follow(page.get());
            for (SafeUrlFetcher.FetchedPage next : hops) {
                refs.addAll(AtsUrlParser.findAll(next.body()));
            }
        }
        BrowserRenderer.Rendered rendered = null;
        if (refs.stream().noneMatch(ref -> isCandidate(ref, current)) && customCrawler.canRender()
                && PageSignals.looksJsDriven(Jsoup.parse(page.get().body(), page.get().finalUri().toString()))) {
            rendered = customCrawler.render(page.get().finalUri(), BrowserRenderer.Lane.BACKGROUND).orElse(null);
            if (rendered != null) {
                refs.addAll(AtsUrlParser.findAll(rendered.html()));
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
                return new PageLook(Optional.of(ref), page, hops, rendered);
            }
        }
        return new PageLook(Optional.empty(), page, hops, rendered);
    }

    /** The careers page itself, then its one-hop pages, read by the crawler. */
    private Optional<BoardRef> crawlCareersPage(PageLook look, BoardRef current) {
        if (look.page().isEmpty()) {
            return Optional.empty();
        }
        List<CustomCrawler.Outcome> outcomes = new ArrayList<>();
        outcomes.add(customCrawler.discover(look.page().get(), look.rendered(), BrowserRenderer.Lane.BACKGROUND));
        for (SafeUrlFetcher.FetchedPage hop : look.hops()) {
            outcomes.add(customCrawler.discoverStatic(hop));
        }
        for (CustomCrawler.Outcome outcome : outcomes) {
            if (outcome.status() != CustomCrawler.Status.JOBS || outcome.canonicalUrl() == null) {
                continue;
            }
            BoardRef ref = new BoardRef(AtsPlatform.CUSTOM, outcome.canonicalUrl());
            if (isCandidate(ref, current)) {
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
