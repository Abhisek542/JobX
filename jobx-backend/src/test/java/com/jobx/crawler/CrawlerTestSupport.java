package com.jobx.crawler;

import com.jobx.resolve.SafeUrlFetcher;

/**
 * A real {@link CustomCrawler} over a (usually mocked) SafeUrlFetcher, with
 * rendering off and robots.txt read through the same fetcher — so a mock that
 * returns nothing for /robots.txt means "allowed", as it does live.
 */
public final class CrawlerTestSupport {

    private CrawlerTestSupport() {
    }

    public static CustomCrawler crawler(SafeUrlFetcher safeUrlFetcher) {
        return new CustomCrawler(safeUrlFetcher, new RobotsTxt(safeUrlFetcher), disabledRenderer());
    }

    public static BrowserRenderer disabledRenderer() {
        return new BrowserRenderer(false, 20000, 8000, 25000, 1, 1);
    }
}
