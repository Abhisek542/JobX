package com.jobx.crawler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * One spelling per page, so a CUSTOM board and its postings get stable keys.
 *
 * A CUSTOM board's {@code board_token} is its careers URL, and
 * {@code companies} is UNIQUE (ats_platform, board_token). Two users pasting
 * "Acme.com/Careers/?utm_source=x" and "acme.com/Careers" must land on the same
 * row, and a posting's {@code external_id} is a hash of its canonical URL, so a
 * tracking parameter that changes per visit must not turn one posting into a new
 * one every cycle.
 *
 * The rules: lowercase scheme and host, no default port, no fragment, no
 * tracking parameters ({@code utm_*}, {@code gclid}, {@code fbclid},
 * {@code ref}, …), no trailing slash on the path. The path itself and every
 * other query parameter are kept as written: paths are case-sensitive on many
 * servers, and a parameter like {@code gh_jid=123} is the only thing telling two
 * postings apart on some pages (Fastly's).
 *
 * The scheme is kept, not forced to https. The plan said https, but the
 * canonical URL is always taken from where a page was actually served after
 * redirects, which already settles http vs https for a site, and upgrading an
 * http-only site would make its board unreachable.
 */
public final class CanonicalUrl {

    private static final Set<String> TRACKING = Set.of(
            "gclid", "fbclid", "msclkid", "dclid", "yclid", "mc_cid", "mc_eid",
            "ref", "ref_src", "referrer", "_ga", "_gl", "trk", "hsctatracking");

    private CanonicalUrl() {
    }

    /** The canonical form, or empty when this is not an absolute http(s) URL. */
    public static Optional<String> of(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            return of(new URI(url.trim()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    public static Optional<String> of(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return Optional.empty();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        StringBuilder out = new StringBuilder(scheme).append("://").append(host);
        if (!defaultPort) {
            out.append(':').append(port);
        }
        out.append(path);

        String query = cleanQuery(uri.getRawQuery());
        if (!query.isEmpty()) {
            out.append('?').append(query);
        }
        return Optional.of(out.toString());
    }

    private static String cleanQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (name.startsWith("utm_") || TRACKING.contains(name)) {
                continue;
            }
            kept.add(pair);
        }
        return String.join("&", kept);
    }
}
