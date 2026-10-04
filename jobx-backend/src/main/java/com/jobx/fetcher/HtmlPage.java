package com.jobx.fetcher;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One GET of an HTML-tier board page, keeping what {@code .retrieve()} throws
 * away: the status and the {@code Location} header.
 *
 * The HTML-tier platforms say "this board does not exist" with a redirect
 * (JazzHR sends an unknown subdomain to its marketing site), and the shared
 * WebClient never follows redirects — reactor-netty's default. Through
 * {@code .retrieve()} a 3xx is a success with an empty body, which a fetcher
 * would read as an empty board: the silent-empty-feed shape again. So HTML
 * fetchers read pages through this and decide on the status themselves.
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
