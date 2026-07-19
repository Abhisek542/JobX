package com.jobx.fetcher.ashby;

import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ashby fetcher — STUB. Deferred per CLAUDE.md build order.
 *
 * When implemented:
 *   Endpoint: GET https://api.ashbyhq.com/posting-api/job-board/{token}
 *   (NOT the jobPosting.list RPC endpoint — that requires API key)
 *   Field mapping:
 *     title           → Job.title
 *     jobUrl          → Job.applyUrl
 *     publishedAt     → Job.platformPostedAt (ISO timestamp, good format)
 *     descriptionHtml → strip HTML → Job.description
 *     isListed        → filter out false
 *   Confirmed live target: Aspora (api.ashbyhq.com/posting-api/job-board/Aspora)
 *   Note: Hasura token may be stale — verify before using
 */
@Component
@Slf4j
public class AshbyFetcher implements AtsFetcher {

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.ASHBY;
    }

    @Override
    public List<Job> fetch(WatchedCompany company) {
        log.warn("AshbyFetcher not yet implemented — returning empty for {}", company.getCompanyName());
        return List.of();
    }
}
