package com.jobx.enums;

/**
 * How a CUSTOM board's listing page has to be read (PR E2), stored as
 * {@code companies.crawl_mode}. Set at add time from whichever pass found the
 * jobs, so the scheduler only pays for a browser on boards that need one.
 */
public enum CrawlMode {
    /** A plain GET returns the listing. */
    STATIC,
    /** The listing is built in JavaScript; the page is rendered in headless Chromium. */
    RENDER
}
