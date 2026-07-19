package com.jobx.fetcher.workable;

import com.jobx.entity.Job;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Workable fetcher — STUB. Deferred per CLAUDE.md build order.
 *
 * When implemented:
 *   Endpoint: GET https://apply.workable.com/api/v1/widget/accounts/{token}
 *   (the embed-widget endpoint — NOT apply.workable.com/api/v3/*)
 *   Confirmed live Indian target: Apna (apply.workable.com/api/v1/widget/accounts/apna)
 *   Note: Zerodha assumption is in question — their careers page looks custom-built,
 *   not Workable-hosted. Verify before using Zerodha as test target.
 */
@Component
@Slf4j
public class WorkableFetcher implements AtsFetcher {

    @Override
    public AtsPlatform supports() {
        return AtsPlatform.WORKABLE;
    }

    @Override
    public List<Job> fetch(WatchedCompany company) {
        log.warn("WorkableFetcher not yet implemented — returning empty for {}", company.getCompanyName());
        return List.of();
    }
}
