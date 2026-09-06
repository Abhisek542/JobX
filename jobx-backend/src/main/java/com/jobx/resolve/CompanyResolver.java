package com.jobx.resolve;

import com.jobx.dto.CompanySearchResponse;
import com.jobx.dto.ResolveResponse;
import com.jobx.dto.ResolvedBoardResponse;
import com.jobx.entity.Company;
import com.jobx.entity.User;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import com.jobx.repository.JobRepository;
import com.jobx.repository.WatchedCompanyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Works out which ATS board a user means from a company name, a website, or a
 * careers link — so that the add-company form can stop asking for an ATS
 * platform and a board token, neither of which any user knows.
 *
 * Four strategies, cheapest and most certain first, stopping at the first that
 * produces anything:
 *
 *  1. CATALOG — a board Jobx already knows. No network, no guessing.
 *  2. URL — the input is itself an ATS link; the token is read straight out of it.
 *  3. SNIFF — fetch the careers page and read the ATS link out of the HTML.
 *  4. PROBE — derive candidate slugs and ask the five APIs which one is real.
 *
 * Steps 3 and 4 are complementary rather than redundant, which is why both
 * exist. Measured live: sniffing is the only thing that recovers Razorpay's
 * {@code razorpaysoftwareprivatelimited}, a token no derivation from the name
 * reaches; probing is the only thing that finds Atlan and FamPay, whose careers
 * pages render the board in JavaScript and expose nothing to a server-side
 * fetch. Each alone resolved about half the companies tested; together, all of
 * them.
 *
 * NOTHING HERE WRITES TO THE DATABASE. Resolution proposes; the user confirms
 * against real job titles; POST /watchlist is what actually watches a board.
 * That separation is what keeps this inside CLAUDE.md's "never guess a board
 * token" rule — the rule exists because a silent guess once produced a wrong
 * board with no signal, and here nothing is silent.
 */
@Service
@Slf4j
public class CompanyResolver {

    /** How this candidate was found — surfaced so the UI can be honest about it. */
    public enum Source {
        CATALOG, URL, SNIFF, PROBE
    }

    /** The platforms worth probing. UNSUPPORTED has no fetcher and no API. */
    private static final List<AtsPlatform> PROBEABLE = List.of(
            AtsPlatform.GREENHOUSE, AtsPlatform.LEVER, AtsPlatform.ASHBY,
            AtsPlatform.WORKABLE, AtsPlatform.SMARTRECRUITERS);

    private final CompanyRepository companyRepository;
    private final WatchedCompanyRepository watchedCompanyRepository;
    private final JobRepository jobRepository;
    private final FetcherRegistry fetcherRegistry;
    private final SafeUrlFetcher safeUrlFetcher;
    private final BoardProbe boardProbe;
    private final int maxSlugCandidates;
    private final int maxSniffedBoards;
    private final int catalogPageSize;

    public CompanyResolver(CompanyRepository companyRepository,
                           WatchedCompanyRepository watchedCompanyRepository,
                           JobRepository jobRepository,
                           FetcherRegistry fetcherRegistry,
                           SafeUrlFetcher safeUrlFetcher,
                           BoardProbe boardProbe,
                           @Value("${jobx.resolve.max-slug-candidates:4}") int maxSlugCandidates,
                           @Value("${jobx.resolve.max-sniffed-boards:3}") int maxSniffedBoards,
                           @Value("${jobx.resolve.catalog-page-size:8}") int catalogPageSize) {
        this.companyRepository = companyRepository;
        this.watchedCompanyRepository = watchedCompanyRepository;
        this.jobRepository = jobRepository;
        this.fetcherRegistry = fetcherRegistry;
        this.safeUrlFetcher = safeUrlFetcher;
        this.boardProbe = boardProbe;
        this.maxSlugCandidates = maxSlugCandidates;
        this.maxSniffedBoards = maxSniffedBoards;
        this.catalogPageSize = catalogPageSize;
    }

    /**
     * Boards Jobx already knows, for the typeahead. Runs on every keystroke
     * (debounced), so it must never touch the network.
     */
    @Transactional(readOnly = true)
    public List<CompanySearchResponse> search(User user, String query) {
        return searchCatalog(query).stream()
                .map(company -> new CompanySearchResponse(
                        company.getId(),
                        company.getDisplayName(),
                        company.getAtsPlatform(),
                        company.getBoardToken(),
                        watchedCompanyRepository.existsByUserAndCompany(user, company)))
                .toList();
    }

    /**
     * @throws SafeUrlFetcher.UnsafeUrlException when the input is a URL Jobx
     *         refuses to request at all — the controller turns that into a 400,
     *         because it is the one failure the user can actually act on.
     */
    @Transactional(readOnly = true)
    public ResolveResponse resolve(User user, String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return new ResolveResponse(List.of(), null);
        }

        // 1. CATALOG — a board someone already proved real.
        List<ResolvedBoardResponse> catalog = resolveFromCatalog(user, trimmed);
        if (!catalog.isEmpty()) {
            return new ResolveResponse(catalog, null);
        }

        // 2. URL — the user pasted the ATS link itself.
        Optional<BoardRef> direct = AtsUrlParser.parse(trimmed);
        if (direct.isPresent()) {
            Optional<ResolvedBoardResponse> confirmed =
                    previewCandidate(user, direct.get(), Source.URL, null);
            if (confirmed.isPresent()) {
                return new ResolveResponse(List.of(confirmed.get()), null);
            }
            // A parsed-but-dead token falls through: the link may be stale, and
            // the other strategies can still find where the company moved to.
            log.debug("Parsed {} board '{}' from the input but it has no live roles",
                    direct.get().platform(), direct.get().token());
        }

        // 3. SNIFF — fetch the careers page and read the board off it.
        AtsPlatform platformHint = null;
        if (looksLikeUrl(trimmed)) {
            Optional<String> html = safeUrlFetcher.fetch(trimmed);
            if (html.isPresent()) {
                List<ResolvedBoardResponse> sniffed = new ArrayList<>();
                for (BoardRef ref : AtsUrlParser.findAll(html.get())) {
                    if (sniffed.size() >= maxSniffedBoards) {
                        break;
                    }
                    previewCandidate(user, ref, Source.SNIFF, null).ifPresent(sniffed::add);
                }
                if (!sniffed.isEmpty()) {
                    return new ResolveResponse(sniffed, null);
                }
                // No token, but the page may still have named the platform —
                // Atlan's only mentions Ashby in a CSP header. Worth a great
                // deal: it turns the probe below from 5 platforms into 1.
                platformHint = AtsUrlParser.platformHint(html.get()).orElse(null);
            }
        }

        // 4. PROBE — guess the slug, then make the ATS prove it.
        List<String> tokens = SlugCandidates.from(trimmed, maxSlugCandidates);
        if (!tokens.isEmpty()) {
            List<AtsPlatform> platforms = platformHint == null ? PROBEABLE : List.of(platformHint);
            List<ResolvedBoardResponse> probed = boardProbe.probe(platforms, tokens).stream()
                    .map(hit -> toResponse(user, hit.ref(), hit.preview(), Source.PROBE, null))
                    .toList();
            if (!probed.isEmpty()) {
                return new ResolveResponse(probed, platformHint);
            }
        }

        // Nothing. An honest empty answer, with the hint if we have one.
        return new ResolveResponse(List.of(), platformHint);
    }

    /**
     * Catalog hits, ranked. A FAILED board sinks to the bottom rather than being
     * hidden: it may simply be having a bad day, but it must never outrank a
     * healthy board with a similar name.
     */
    private List<Company> searchCatalog(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() < 2) {
            return List.of();
        }
        List<Company> found = companyRepository.searchByNameOrToken(
                trimmed, PageRequest.of(0, catalogPageSize * 3));

        String lower = trimmed.toLowerCase(Locale.ROOT);
        return found.stream()
                .sorted(Comparator
                        .comparing((Company c) -> c.getLastFetchStatus() == Company.FetchStatus.FAILED)
                        .thenComparing(c -> !c.getDisplayName().toLowerCase(Locale.ROOT).startsWith(lower))
                        .thenComparing(Company::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .limit(catalogPageSize)
                .toList();
    }

    /**
     * Catalog candidates prefer stored jobs as their evidence: the board has
     * already been fetched, so its real titles are in Postgres and no ATS call
     * is needed to show them.
     *
     * When there are none, we ask the board itself. A catalog row with no stored
     * jobs is the normal state for a seeded entry and for any board whose
     * watchers have all left, and offering one with "0 open roles" and no titles
     * would be the one thing this whole flow exists to avoid: a candidate with
     * nothing behind it. Falling back to a live preview costs a single request on
     * a path the user has explicitly asked for.
     */
    private List<ResolvedBoardResponse> resolveFromCatalog(User user, String query) {
        List<ResolvedBoardResponse> results = new ArrayList<>();
        for (Company company : searchCatalog(query)) {
            long stored = jobRepository.countByCompany(company);
            if (stored > 0) {
                results.add(new ResolvedBoardResponse(
                        Source.CATALOG.name(),
                        company.getAtsPlatform(),
                        company.getBoardToken(),
                        company.getDisplayName(),
                        AtsUrlParser.boardUrl(company.getAtsPlatform(), company.getBoardToken()),
                        (int) stored,
                        jobRepository.findTitlesByCompany(
                                company, PageRequest.of(0, BoardPreview.SAMPLE_SIZE)),
                        watchedCompanyRepository.existsByUserAndCompany(user, company)));
                continue;
            }

            BoardRef ref = new BoardRef(company.getAtsPlatform(), company.getBoardToken());
            previewCandidate(user, ref, Source.CATALOG, company.getDisplayName())
                    .ifPresent(results::add);
        }
        return results;
    }

    /**
     * Turns a claimed board into a confirmed one by asking the ATS for it.
     * Empty when the board does not exist, cannot be read, or has no live roles
     * — the last of which is the case that matters, because two platforms answer
     * a token that was never theirs with a cheerful empty 200.
     */
    private Optional<ResolvedBoardResponse> previewCandidate(User user, BoardRef ref,
                                                             Source source, String fallbackName) {
        Optional<AtsFetcher> fetcher = fetcherRegistry.getFetcher(ref.platform());
        if (fetcher.isEmpty()) {
            return Optional.empty();
        }

        Company probe = new Company();
        probe.setAtsPlatform(ref.platform());
        probe.setBoardToken(ref.token());
        probe.setDisplayName(ref.token());

        try {
            BoardPreview preview = fetcher.get().previewBoard(probe);
            if (preview.jobCount() <= 0) {
                return Optional.empty();
            }
            return Optional.of(toResponse(user, ref, preview, source, fallbackName));
        } catch (Exception e) {
            log.debug("No {} board at '{}': {}", ref.platform(), ref.token(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Display name, best available first: what the board calls itself, then the
     * name Jobx already stores for it, then the domain label or what the user
     * typed. Only Workable and SmartRecruiters name the company in a list
     * response; the other three carry no company name at all.
     */
    private ResolvedBoardResponse toResponse(User user, BoardRef ref, BoardPreview preview,
                                             Source source, String fallbackName) {
        Optional<Company> known = companyRepository
                .findByPlatformAndTokenIgnoreCase(ref.platform(), ref.token());

        String name = preview.displayName();
        if (name == null || name.isBlank()) {
            name = known.map(Company::getDisplayName).orElse(null);
        }
        if (name == null || name.isBlank()) {
            name = fallbackName != null && !fallbackName.isBlank() ? fallbackName : ref.token();
        }

        boolean watched = known
                .map(company -> watchedCompanyRepository.existsByUserAndCompany(user, company))
                .orElse(false);

        return new ResolvedBoardResponse(
                source.name(),
                ref.platform(),
                ref.token(),
                name,
                AtsUrlParser.boardUrl(ref.platform(), ref.token()),
                preview.jobCount(),
                preview.sampleTitles(),
                watched);
    }

    /**
     * A name is a name even with a dot in it; a host is a host.
     *
     * An explicit {@code scheme://} counts too, even a scheme we will not fetch.
     * Somebody who pastes {@code file:///etc/passwd} has unambiguously given us a
     * URL, and telling them plainly that only http and https links are checked is
     * a better answer than quietly treating it as a company name and reporting
     * that no board was found.
     */
    private boolean looksLikeUrl(String query) {
        return SlugCandidates.hostOf(query) != null
                || query.matches("(?i)^[a-z][a-z0-9+.-]*://.*");
    }

}
