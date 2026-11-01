package com.jobx.crawler;

import com.jobx.resolve.PrivateAddressGuard;
import com.jobx.resolve.SafeUrlFetcher;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Renders a careers page in headless Chromium and hands back the DOM, for pages
 * that build their job list (or their ATS link) in JavaScript (PR E2).
 *
 * <h2>Threading</h2>
 * Playwright's Java objects are NOT thread-safe: a {@code Playwright}, its
 * browser, contexts and pages must be created and used on one thread. So no
 * caller ever touches Playwright. Each of two LANES is a single-thread executor
 * that lazily owns its own Playwright + Chromium; callers submit a URL and wait
 * on a {@code Future}.
 * <ul>
 *   <li>{@link Lane#INTERACTIVE}: add-company resolution, where a person is
 *       waiting. Callers give up after {@code interactive-wait-ms}.</li>
 *   <li>{@link Lane#BACKGROUND}: the scheduler's {@code crawl_mode = RENDER}
 *       boards. Slowness is harmless there.</li>
 * </ul>
 * Two lanes so a user never queues behind a scheduler cycle; one thread each
 * because every thread costs a Chromium process (~100-200 MB). Both sizes are
 * configurable.
 *
 * <h2>Safety</h2>
 * A rendered page loads sub-resources and makes XHRs of its own, so the
 * server's browser could be pointed at internal addresses by any page a user
 * pastes. Every request the page makes (document, script, XHR, iframe) goes
 * through a context route that refuses non-http(s) schemes and any host with a
 * non-public address ({@link PrivateAddressGuard}). Playwright does not route
 * the follow-up request of a redirect, so the route fetches each request
 * itself with redirects off and checks the {@code Location} before handing the
 * response to the browser. WebSockets and service workers are blocked outright.
 * The same DNS-rebinding residual as {@link SafeUrlFetcher} applies.
 * <p>
 * The browser identifies with JobX's own User-Agent, never a browser's
 * (CLAUDE.md: never send a browser UA, never bypass bot protection).
 * <p>
 * Each render gets a fresh private context, closed afterwards, so nothing
 * carries between sites. Images, media and fonts are not loaded.
 */
@Component
@Slf4j
public class BrowserRenderer {

    public enum Lane { INTERACTIVE, BACKGROUND }

    /** What a render produced: the URL the page ended on and its DOM as HTML. */
    public record Rendered(URI finalUri, String html) {
    }

    private static final int MAX_HTML_CHARS = 2 * 1024 * 1024;
    private static final Set<String> SKIPPED_TYPES = Set.of("image", "media", "font");

    private final boolean enabled;
    private final Duration pageBudget;
    private final Duration idleWait;
    private final Duration interactiveWait;
    private final Map<Lane, LaneWorker> lanes;

    public BrowserRenderer(@Value("${jobx.crawler.render.enabled:false}") boolean enabled,
                           @Value("${jobx.crawler.render.page-budget-ms:20000}") long pageBudgetMs,
                           @Value("${jobx.crawler.render.network-idle-ms:8000}") long idleMs,
                           @Value("${jobx.crawler.render.interactive-wait-ms:25000}") long interactiveWaitMs,
                           @Value("${jobx.crawler.render.interactive-threads:1}") int interactiveThreads,
                           @Value("${jobx.crawler.render.background-threads:1}") int backgroundThreads) {
        this.enabled = enabled;
        this.pageBudget = Duration.ofMillis(pageBudgetMs);
        this.idleWait = Duration.ofMillis(idleMs);
        this.interactiveWait = Duration.ofMillis(interactiveWaitMs);
        this.lanes = Map.of(
                Lane.INTERACTIVE, new LaneWorker("render-interactive", Math.max(1, interactiveThreads)),
                Lane.BACKGROUND, new LaneWorker("render-background", Math.max(1, backgroundThreads)));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Render {@code url} on the given lane and wait for it. Empty when rendering
     * is disabled, the lane is busy past the caller's wait, or the page could
     * not be rendered. Never throws.
     */
    public Optional<Rendered> render(String url, Lane lane) {
        if (!enabled) {
            return Optional.empty();
        }
        Duration wait = lane == Lane.INTERACTIVE ? interactiveWait : pageBudget.multipliedBy(3);
        Future<Optional<Rendered>> future = lanes.get(lane).submit(url);
        try {
            return future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Still queued (or still running): drop it if it hasn't started.
            future.cancel(false);
            log.info("Render of {} on the {} lane gave up after {} ms", url, lane, wait.toMillis());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            return Optional.empty();
        } catch (Exception e) {
            log.info("Render of {} failed: {}", url, e.toString());
            return Optional.empty();
        }
    }

    @PreDestroy
    void shutdown() {
        lanes.values().forEach(LaneWorker::shutdown);
    }

    /**
     * Whether a request may leave the browser: http(s) to a host whose every
     * address is public. Package-private for tests.
     */
    static boolean isAllowedTarget(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("data") || scheme.equals("blob")) {
            return true; // in-page content; no network request
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (PrivateAddressGuard.isBlocked(address)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** One lane: a fixed pool where each thread owns its own Playwright and Chromium. */
    private final class LaneWorker {

        private final ExecutorService executor;
        private final ThreadLocal<Playwright> playwright = new ThreadLocal<>();
        private final ThreadLocal<Browser> browser = new ThreadLocal<>();

        LaneWorker(String name, int threads) {
            this.executor = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            });
        }

        Future<Optional<Rendered>> submit(String url) {
            return executor.submit(() -> renderHere(url));
        }

        private Optional<Rendered> renderHere(String url) {
            if (!isAllowedTarget(url)) {
                log.info("Refused to render {}: not a public http(s) address", url);
                return Optional.empty();
            }
            BrowserContext context = null;
            try {
                context = browser().newContext(new Browser.NewContextOptions()
                        .setUserAgent(SafeUrlFetcher.USER_AGENT)
                        .setAcceptDownloads(false)
                        .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                        .setJavaScriptEnabled(true));
                context.route("**/*", BrowserRenderer.this::guard);
                // A handler that never connects to the server blocks the socket.
                context.routeWebSocket(java.util.regex.Pattern.compile(".*"), ws -> { });

                Page page = context.newPage();
                page.onDialog(dialog -> dialog.dismiss());
                long start = System.nanoTime();
                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(pageBudget.toMillis()));
                long left = pageBudget.toMillis() - Duration.ofNanos(System.nanoTime() - start).toMillis();
                long idle = Math.min(idleWait.toMillis(), Math.max(0, left));
                if (idle > 0) {
                    try {
                        page.waitForLoadState(LoadState.NETWORKIDLE,
                                new Page.WaitForLoadStateOptions().setTimeout(idle));
                    } catch (PlaywrightException e) {
                        // Chatty pages never go idle; take what has rendered.
                    }
                }
                String html = page.content();
                if (html.length() > MAX_HTML_CHARS) {
                    html = html.substring(0, MAX_HTML_CHARS);
                }
                return Optional.of(new Rendered(URI.create(page.url()), html));
            } catch (PlaywrightException e) {
                log.info("Render of {} failed, relaunching the browser next time: {}", url, firstLine(e));
                discardBrowser();
                return Optional.empty();
            } catch (Exception e) {
                log.info("Render of {} failed: {}", url, e.toString());
                return Optional.empty();
            } finally {
                if (context != null) {
                    try {
                        context.close();
                    } catch (Exception ignored) {
                        // the browser may already be gone
                    }
                }
            }
        }

        private Browser browser() {
            Browser current = browser.get();
            if (current != null && current.isConnected()) {
                return current;
            }
            discardBrowser();
            Playwright pw = Playwright.create();
            Browser launched = pw.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--disable-dev-shm-usage")));
            playwright.set(pw);
            browser.set(launched);
            log.info("Launched headless Chromium on {}", Thread.currentThread().getName());
            return launched;
        }

        private void discardBrowser() {
            Browser b = browser.get();
            Playwright pw = playwright.get();
            browser.remove();
            playwright.remove();
            try {
                if (b != null) {
                    b.close();
                }
            } catch (Exception ignored) {
                // already dead
            }
            try {
                if (pw != null) {
                    pw.close();
                }
            } catch (Exception ignored) {
                // already dead
            }
        }

        void shutdown() {
            // Close each thread's browser on that thread, then stop the pool, so
            // no orphan Chromium process outlives the application.
            try {
                executor.submit(this::discardBrowser).get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The per-request guard. Runs on the lane thread (Playwright dispatches
     * route callbacks on the thread that owns the connection).
     */
    private void guard(Route route) {
        Request request = route.request();
        if (SKIPPED_TYPES.contains(request.resourceType())) {
            route.abort("blockedbyclient");
            return;
        }
        String url = request.url();
        if (url.startsWith("data:") || url.startsWith("blob:")) {
            route.resume();
            return;
        }
        if (!isAllowedTarget(url)) {
            log.info("Render: blocked request to {}", url);
            route.abort("blockedbyclient");
            return;
        }
        try {
            APIResponse response = route.fetch(new Route.FetchOptions()
                    .setMaxRedirects(0)
                    .setTimeout(pageBudget.toMillis()));
            int status = response.status();
            if (status >= 300 && status < 400) {
                String location = response.headers().get("location");
                String next = location == null ? null : resolveLocation(url, location);
                if (next == null || !isAllowedTarget(next)) {
                    log.info("Render: blocked redirect from {} to {}", url, location);
                    route.abort("blockedbyclient");
                    return;
                }
            }
            route.fulfill(new Route.FulfillOptions().setResponse(response));
        } catch (PlaywrightException e) {
            try {
                route.abort("failed");
            } catch (PlaywrightException ignored) {
                // the page may be closing
            }
        }
    }

    private static String resolveLocation(String base, String location) {
        try {
            return URI.create(base).resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstLine(Exception e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
