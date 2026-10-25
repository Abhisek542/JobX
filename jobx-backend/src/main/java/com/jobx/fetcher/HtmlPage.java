package com.jobx.fetcher;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * One GET of an HTML-tier board page, keeping what {@code .retrieve()} throws
 * away: the status and the {@code Location} header.
 *
 * The HTML-tier platforms say "this board does not exist" with a redirect
 * (JazzHR sends an unknown subdomain to its marketing site), and the shared
 * WebClient never follows redirects — reactor-netty's default. Through
 * {@code .retrieve()} a 3xx is a success with an empty body, which a fetcher
 * would read as an empty board: the silent-empty-feed shape again. So HTML
 * fetchers read pages through this, and the HTML-tier rules for reading them
 * ({@link #requireBoard}, {@link #requireOk}, {@link #requireCardsOrEmptyMarker})
 * live here once rather than in each fetcher.
 *
 * @param location the raw {@code Location} header, or null
 * @param body     never null; empty when the response had none
 */
public record HtmlPage(int status, String location, String body) {

    public boolean isRedirect() {
        return status >= 300 && status < 400;
    }

    public boolean isOk() {
        return status >= 200 && status < 300;
    }

    /**
     * The body of a board's list page. Every HTML-tier platform says "no such
     * board" with a redirect (Jobvite, JazzHR) or a 404 (Gusto), so both read as
     * "does not exist"; any other non-2xx is a failed fetch. Never an empty body
     * that a caller could mistake for an empty board.
     *
     * @param platform as it should read in the error, e.g. "Jobvite"
     */
    public String requireBoard(String platform, String token) {
        if (isRedirect()) {
            throw new AtsFetchException(platform + " board '" + token + "' does not exist (redirected to "
                    + location + ")");
        }
        if (status == 404) {
            throw new AtsFetchException(platform + " board '" + token + "' does not exist (HTTP 404)");
        }
        if (!isOk()) {
            throw new AtsFetchException(platform + " board '" + token + "' answered HTTP " + status);
        }
        return body;
    }

    /**
     * The body of a 2xx page, such as a posting's detail page. Anything else —
     * a redirect or 404 for a posting closed between list and detail — throws,
     * and the caller turns that into skip-and-retry.
     *
     * @param what names the page in the error, e.g. "Jobvite job oNyuAfw9"
     */
    public String requireOk(String what) {
        if (!isOk()) {
            throw new AtsFetchException(what + " answered HTTP " + status);
        }
        return body;
    }

    /**
     * The HTML-tier rule: FAIL LOUDLY. A page where the selectors find no job
     * cards is an empty board only when it shows the platform's own empty-board
     * marker. Otherwise the layout has changed under us, and that must surface
     * as FAILED health, never as a quietly empty feed.
     *
     * @param emptyMarkerShown whether the page carries the platform's own
     *                         "no openings" state
     * @return {@code cards}, unchanged
     */
    public static <T> List<T> requireCardsOrEmptyMarker(List<T> cards, boolean emptyMarkerShown,
                                                        String platform, String token) {
        if (cards.isEmpty() && !emptyMarkerShown) {
            throw new AtsFetchException(platform + " board '" + token
                    + "' returned a page with no job cards and no empty-board marker — layout changed?");
        }
        return cards;
    }

    /**
     * @throws AtsFetchException on a transport failure. A non-2xx status is
     *         NOT thrown — it comes back for the caller to interpret.
     */
    public static HtmlPage get(WebClient.Builder webClientBuilder, String url) {
        HtmlPage page;
        try {
            page = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new HtmlPage(response.statusCode().value(),
                                    response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION),
                                    body)))
                    .block();
        } catch (Exception e) {
            throw new AtsFetchException("Request failed for " + url, e);
        }
        if (page == null) {
            throw new AtsFetchException("No response for " + url);
        }
        return page;
    }
}
