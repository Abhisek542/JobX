package com.jobx.fetcher.custom;

import com.jobx.crawler.BrowserRenderer;
import com.jobx.crawler.CareerPageExtractor;
import com.jobx.crawler.CustomCrawler;
import com.jobx.crawler.ExtractedJob;
import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.enums.AtsPlatform;
import com.jobx.enums.CrawlMode;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.ExperienceParser;
import com.jobx.fetcher.FetchFilter;
import com.jobx.fetcher.PastTtlMemo;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Fetcher for {@code CUSTOM} boards: a company's own careers page, read by the
 * smart crawler (add-company PR E). The board token is the canonical careers
 * URL.
 *
 * Modelled on Workable's two-call design:
 *  - LIST: the careers page, read by {@link CustomCrawler} the way the board was
 *    added ({@code crawl_mode} STATIC = a plain GET, RENDER = headless Chromium
 *    on the background lane). Every request goes through SafeUrlFetcher or the
 *    guarded renderer; robots.txt is honoured.
 *  - DETAIL: a posting's own page, fetched plainly (never rendered), only for
 *    postings the {@link FetchFilter} doesn't know yet, at most
 *    {@code jobx.crawler.max-detail-fetches} per cycle. A failed or deferred
 *    detail skips the posting so the next cycle retries it, as on Workable.
 *
 * The external id is a hash of the posting's canonical URL (or of title +
 * location when a JSON-LD block has no URL), so it is stable across cycles and
 * tracking parameters.
 *
 * FAIL LOUDLY: a page that loads but yields no recognisable job, and doesn't
 * say it has no openings, throws. A redesigned careers page must show up as
 * FAILED health, never as a quietly empty feed. A bot challenge or a
 * robots.txt refusal throws too, and is never worked around.
 */
@Component
@Slf4j
public class CustomPageFetcher implements AtsFetcher {

    private final CustomCrawler crawler;
    private final int maxDetailFetches;
    private final PastTtlMemo pastTtl = new PastTtlMemo();

    public CustomPageFetcher(CustomCrawler crawler,
                             @Value("${jobx.crawler.max-detail-fetches:25}") int maxDetailFetches) {
        this.crawler = crawler;
        this.maxDetailFetches = maxDetailFetches;
    }

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.CUSTOM;
    }

    @Override
    public List<Job> fetch(Company company, FetchFilter filter) {
        CrawlMode mode = company.getCrawlMode() == null ? CrawlMode.STATIC : company.getCrawlMode();
        CustomCrawler.Outcome outcome = crawler.read(company.getBoardToken(), mode, BrowserRenderer.Lane.BACKGROUND);
        return switch (outcome.status()) {
            case JOBS -> toJobs(company, outcome.jobs(), filter);
            case NO_OPENINGS -> List.of();
            default -> throw new AtsFetchException(outcome.describe());
        };
    }

    /** Package-private seam: the mapping and detail logic, without the list fetch. */
    List<Job> toJobs(Company company, List<ExtractedJob> extracted, FetchFilter filter) {
        List<Job> results = new ArrayList<>();
        int detailCalls = 0;
        int deferred = 0;
        int skipped = 0;

        for (ExtractedJob posting : extracted) {
            String externalId = externalId(posting);
            if (filter.isKnown(externalId) || pastTtl.contains(company.getBoardToken(), externalId)) {
                continue;
            }
            if (isTooOld(filter, posting.postedAt())) {
                continue;
            }

            Job job = new Job();
            job.setCompany(company);
            job.setAtsPlatform(AtsPlatform.CUSTOM);
            job.setExternalId(externalId);
            job.setTitle(posting.title());
            job.setApplyUrl(posting.url() != null ? posting.url() : company.getBoardToken());
            job.setLocation(posting.location());
            job.setDepartment(posting.department());
            job.setEmploymentType(posting.employmentType());
            job.setPlatformPostedAt(posting.postedAt());

            String description = posting.description();
            if (description == null && posting.url() != null) {
                if (detailCalls >= maxDetailFetches) {
                    deferred++;
                    continue; // next cycle; the posting is still unknown then
                }
                detailCalls++;
                Optional<Document> detail = fetchDetail(posting.url());
                if (detail.isEmpty()) {
                    log.warn("CUSTOM detail fetch failed for {} ({}) — skipping, will retry next cycle",
                            posting.url(), company.getDisplayName());
                    skipped++;
                    continue;
                }
                URI detailUri = URI.create(posting.url());
                description = CareerPageExtractor.detailDescription(detail.get(), detailUri);
                applyDetailPosting(detail.get(), detailUri, job);
                if (isTooOld(filter, job.getPlatformPostedAt())) {
                    // Only the detail page knew the date. Remember it so this
                    // stale posting never costs another detail call.
                    pastTtl.remember(company.getBoardToken(), externalId);
                    continue;
                }
            }
            if (description != null && !description.isBlank()) {
                job.setDescription(description);
                ExperienceParser.parse(description, job);
            }
            job.setFirstSeenAt(Instant.now());
            results.add(job);
        }

        log.info("Translated {} new jobs for {} (CUSTOM, {} detail calls, {} deferred to next cycle, "
                        + "{} skipped pending retry)",
                results.size(), company.getDisplayName(), detailCalls, deferred, skipped);
        return results;
    }

    /** The per-posting detail fetch. Package-private seam so tests can count calls. */
    Optional<Document> fetchDetail(String url) {
        return crawler.fetchDetail(url);
    }

    /** Fill what the list didn't carry from the detail page's own JSON-LD. */
    private static void applyDetailPosting(Document detail, URI detailUri, Job job) {
        Optional<ExtractedJob> posting = CareerPageExtractor.detailPosting(detail, detailUri);
        if (posting.isEmpty()) {
            return;
        }
        ExtractedJob p = posting.get();
        if (job.getLocation() == null) {
            job.setLocation(p.location());
        }
        if (job.getEmploymentType() == null) {
            job.setEmploymentType(p.employmentType());
        }
        if (job.getPlatformPostedAt() == null) {
            job.setPlatformPostedAt(p.postedAt());
        }
    }

    /**
     * Dates here are often day-precision (JSON-LD {@code datePosted:
     * "2026-10-30"}), so judge by the end of that day, as Workable does:
     * midnight would make a posting look up to a day older than it is.
     */
    private static boolean isTooOld(FetchFilter filter, Instant postedAt) {
        return postedAt != null && filter.isTooOld(postedAt.plus(Duration.ofDays(1)));
    }

    /**
     * One cheap read of the page: count + first titles. Discovery mode (a static
     * read, then a render on the interactive lane if that found nothing),
     * because this runs while a user is adding the board.
     */
    @Override
    public BoardPreview previewBoard(Company company) {
        CustomCrawler.Outcome outcome = discover(company);
        return switch (outcome.status()) {
            case JOBS -> new BoardPreview(outcome.siteName(), outcome.jobs().size(),
                    outcome.jobs().stream().limit(BoardPreview.SAMPLE_SIZE).map(ExtractedJob::title).toList());
            case NO_OPENINGS -> new BoardPreview(outcome.siteName(), 0, List.of());
            default -> throw new AtsFetchException(outcome.describe());
        };
    }

    /**
     * At add time: the page must show at least one job, or say it has none open.
     * Also records on the (not yet saved) company which pass found them, so the
     * scheduler only renders boards that needed a browser to read.
     */
    @Override
    public void validateBoard(Company company) {
        CustomCrawler.Outcome outcome = discover(company);
        if (!outcome.isWatchable()) {
            throw new AtsFetchException(outcome.describe());
        }
        company.setCrawlMode(outcome.mode());
    }

    private CustomCrawler.Outcome discover(Company company) {
        return crawler.discover(company.getBoardToken(), BrowserRenderer.Lane.INTERACTIVE);
    }

    /** SHA-256 of the canonical posting URL (else title + location), 32 hex chars. */
    public static String externalId(ExtractedJob posting) {
        String key = posting.url() != null
                ? posting.url()
                : posting.title() + "|" + (posting.location() == null ? "" : posting.location());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
