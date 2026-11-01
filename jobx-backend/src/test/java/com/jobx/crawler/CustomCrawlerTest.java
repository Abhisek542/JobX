package com.jobx.crawler;

import com.jobx.enums.CrawlMode;
import com.jobx.resolve.SafeUrlFetcher;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomCrawlerTest {

    /** A real SafeUrlFetcher: the guard refuses before any socket is opened. */
    private final CustomCrawler realGuard = CrawlerTestSupport.crawler(new SafeUrlFetcher(1000, 1000));

    @Test
    void neverFetchesANonPublicCareersUrl() {
        for (String url : new String[]{"http://127.0.0.1/careers", "http://169.254.169.254/latest",
                "http://[::1]/jobs", "http://10.0.0.5/careers"}) {
            assertEquals(CustomCrawler.Status.UNSAFE,
                    realGuard.read(url, CrawlMode.STATIC, BrowserRenderer.Lane.BACKGROUND).status(), url);
            assertEquals(CustomCrawler.Status.UNSAFE,
                    realGuard.discover(url, BrowserRenderer.Lane.INTERACTIVE).status(), url);
        }
    }

    @Test
    void aNonWebUrlIsUnsafeWithoutAnyRequest() {
        SafeUrlFetcher fetcher = mock(SafeUrlFetcher.class);
        CustomCrawler crawler = CrawlerTestSupport.crawler(fetcher);

        assertEquals(CustomCrawler.Status.UNSAFE,
                crawler.read("file:///etc/passwd", CrawlMode.STATIC, BrowserRenderer.Lane.BACKGROUND).status());
        verifyNoInteractions(fetcher);
    }

    @Test
    void theBrowserRefusesPrivateTargetsToo() {
        assertFalse(BrowserRenderer.isAllowedTarget("http://127.0.0.1/"));
        assertFalse(BrowserRenderer.isAllowedTarget("http://169.254.169.254/latest/meta-data"));
        assertFalse(BrowserRenderer.isAllowedTarget("file:///etc/passwd"));
        assertFalse(BrowserRenderer.isAllowedTarget("ws://example.com/socket"));
        assertTrue(BrowserRenderer.isAllowedTarget("data:text/html,hi"));
    }

    @Test
    void robotsDisallowStopsTheFetch() {
        SafeUrlFetcher fetcher = mock(SafeUrlFetcher.class);
        when(fetcher.fetch("https://acme.example/robots.txt"))
                .thenReturn(Optional.of("User-agent: *\nDisallow: /careers\n"));
        CustomCrawler crawler = CrawlerTestSupport.crawler(fetcher);

        CustomCrawler.Outcome outcome =
                crawler.read("https://acme.example/careers", CrawlMode.STATIC, BrowserRenderer.Lane.BACKGROUND);

        assertEquals(CustomCrawler.Status.ROBOTS_DISALLOWED, outcome.status());
        verify(fetcher, never()).fetchPage(anyString());
        assertTrue(crawler.fetchDetail("https://acme.example/careers/jobs/1").isEmpty());
    }

    @Test
    void aRenderBoardIsUnreachableWhileRenderingIsOff() {
        SafeUrlFetcher fetcher = mock(SafeUrlFetcher.class);
        CustomCrawler crawler = CrawlerTestSupport.crawler(fetcher);

        assertEquals(CustomCrawler.Status.UNREACHABLE,
                crawler.read("https://acme.example/careers", CrawlMode.RENDER, BrowserRenderer.Lane.BACKGROUND).status());
        verify(fetcher, never()).fetchPage(anyString());
    }

    @Test
    void discoverReadsAStaticPage() {
        SafeUrlFetcher fetcher = mock(SafeUrlFetcher.class);
        URI page = URI.create("https://acme.example/careers");
        when(fetcher.fetchPage("https://acme.example/careers")).thenReturn(Optional.of(
                new SafeUrlFetcher.FetchedPage(page, CareerPageExtractorTest.fixture("static-listing.html"))));

        CustomCrawler.Outcome outcome =
                CrawlerTestSupport.crawler(fetcher).discover("https://acme.example/careers", BrowserRenderer.Lane.INTERACTIVE);

        assertEquals(CustomCrawler.Status.JOBS, outcome.status());
        assertEquals(CrawlMode.STATIC, outcome.mode());
        assertEquals(4, outcome.jobs().size());
    }
}
