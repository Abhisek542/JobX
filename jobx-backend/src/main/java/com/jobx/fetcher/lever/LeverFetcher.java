package com.jobx.fetcher.lever;

import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lever fetcher — STUB. Deferred per CLAUDE.md build order.
 * Build after GreenhouseFetcher is working end-to-end.
 *
 * When implemented:
 *   Endpoint: GET https://api.lever.co/v0/postings/{company}?mode=json
 *   Field mapping:
 *     text        → Job.title         (NOT "title")
 *     hostedUrl   → Job.applyUrl      (NOT "absolute_url")
 *     createdAt   → epoch MS → Instant (NOT ISO 8601)
 *     descriptionPlain → Job.description (no HTML stripping needed)
 *   Real Indian targets: FamPay (fampay), Sprinto (Sprinto), Postman (postman)
 */
@Component
@Slf4j
public class LeverFetcher implements AtsFetcher {

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.LEVER;
    }

    @Override
    public List<Job> fetch(WatchedCompany company) {
        log.warn("LeverFetcher not yet implemented — returning empty for {}", company.getCompanyName());
        return List.of();
    }
}
