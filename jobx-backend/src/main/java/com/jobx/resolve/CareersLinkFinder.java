package com.jobx.resolve;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks the links on a careers landing page that most likely lead to the job
 * list itself, for the resolver's one-hop follow.
 *
 * Plenty of companies paste-able as a "careers page" have a /careers landing
 * page that never names their ATS: the board sits one click away, behind an
 * "Open roles" or "See all jobs" link. SNIFF reads only the exact page, so
 * those resolved to nothing. This finds those links; it fetches nothing.
 *
 * Only links on the SAME SITE are kept: the page's registrable domain or a
 * subdomain of it ({@code careers.acme.com}, {@code jobs.acme.co.in}). A
 * "Careers" link to some other domain is not this company's careers page, and
 * following it would let any page steer our fetches anywhere. Links straight to
 * an ATS are not this class's business either: SNIFF already read them off the
 * page.
 *
 * Pure and static so it can be tested on fixture HTML.
 */
final class CareersLinkFinder {

    private CareersLinkFinder() {
    }

    /** Matched against the anchor's visible text. */
    private static final Pattern CAREERS_TEXT = Pattern.compile(
            "(?i)\\b(careers?|jobs?|open (roles|positions)|join us|work with us|we'?re hiring)\\b");

    /**
     * Matched against the link's path and query only, never its host: on
     * careers.acme.com every link would otherwise look like a careers link.
     */
    private static final Pattern CAREERS_PATH = Pattern.compile(
            "(?i)(?<![a-z])(careers?|jobs?|open[-_]?(roles|positions)|join[-_]?us"
                    + "|work[-_]?with[-_]?us|were[-_]?hiring)(?![a-z])");

    /** A link to a file is never the page that lists the jobs. */
    private static final Pattern FILE_DOWNLOAD = Pattern.compile(
            "(?i)\\.(pdf|docx?|xlsx?|pptx?|zip|png|jpe?g|gif|svg|webp|mp4)$");

    /**
     * Up to {@code limit} same-site careers links on the page, in document order,
     * as absolute http(s) URLs without fragments. Never includes the page itself.
     *
     * @param pageUri where the HTML was actually served from (after redirects);
     *                relative links and the same-site check are resolved against it
     */
    static List<String> find(URI pageUri, String html, int limit) {
        if (pageUri == null || html == null || limit <= 0 || pageUri.getHost() == null) {
            return List.of();
        }
        String site = SlugCandidates.registrableDomain(pageUri.getHost());
        if (site == null) {
            return List.of();
        }
        String self = normalize(pageUri);

        Document document = Jsoup.parse(html, pageUri.toString());
        Set<String> found = new LinkedHashSet<>();
        for (Element anchor : document.select("a[href]")) {
            if (found.size() >= limit) {
                break;
            }
            String absolute = anchor.absUrl("href");
            if (absolute.isEmpty()) {
                continue; // mailto:, tel:, javascript: and unparseable hrefs resolve to ""
            }

            URI link;
            try {
                link = URI.create(absolute);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String scheme = link.getScheme() == null ? "" : link.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                continue;
            }
            if (!isSameSite(link.getHost(), site)) {
                continue;
            }

            String path = link.getRawPath() == null ? "" : link.getRawPath();
            if (FILE_DOWNLOAD.matcher(path).find()) {
                continue;
            }

            String pathAndQuery = path + (link.getRawQuery() == null ? "" : "?" + link.getRawQuery());
            boolean careersLink = CAREERS_TEXT.matcher(anchor.text()).find()
                    || CAREERS_PATH.matcher(pathAndQuery).find();
            if (!careersLink) {
                continue;
            }

            String normalized = normalize(link);
            if (!normalized.equals(self)) {
                found.add(normalized);
            }
        }
        return List.copyOf(new ArrayList<>(found));
    }

    private static boolean isSameSite(String host, String site) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals(site) || lower.endsWith("." + site);
    }

    /**
     * Without the fragment, and with the host lower-cased and a lone trailing
     * slash dropped, so "/careers", "/careers/" and "/careers#open" are one link.
     */
    private static String normalize(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                + uri.getHost().toLowerCase(Locale.ROOT) + port + path + query;
    }
}
