package com.jobx.fetcher;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation for board tokens that end up in a HOSTNAME rather than a URL path.
 *
 * The first five fetchers put the token in a path on a fixed host, so the worst
 * a hostile token can do is 404. BambooHR, JazzHR, iCIMS and Workday put it in
 * the host itself ({@code {sub}.bamboohr.com}), and POST /watchlist takes
 * {@code boardToken} straight from the user — an unchecked "evil.com#" there is
 * SSRF. So the rule for every host-building fetcher is: validate here before the
 * first request, then build the host as {@code validatedLabel + ".fixedsuffix"},
 * never by concatenating free text.
 *
 * {@link #SUBDOMAIN_LABEL} is shared with {@code AtsUrlParser}, so the parser can
 * never produce a host token the fetcher would reject.
 */
public final class BoardTokens {

    private BoardTokens() {
    }

    /** One DNS label: alphanumeric ends, hyphens inside, at most 63 chars. No group, no anchors. */
    public static final String SUBDOMAIN_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

    private static final Pattern LABEL = Pattern.compile(SUBDOMAIN_LABEL, Pattern.CASE_INSENSITIVE);
    private static final Pattern WORKDAY_SHARD = Pattern.compile("wd\\d{1,3}", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORKDAY_SITE = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    /**
     * @return the token lower-cased (hostnames are case-insensitive)
     * @throws AtsFetchException when the token is not a single DNS label
     */
    public static String requireSubdomainLabel(String token) {
        if (token == null || !LABEL.matcher(token).matches()) {
            throw new AtsFetchException("Board token '" + token + "' is not a valid subdomain");
        }
        return token.toLowerCase(Locale.ROOT);
    }

    /**
     * Workday's board token. A Workday board is addressed by three things that
     * can't be guessed from one another — the tenant, the data-centre shard and
     * the career-site name — so the token stores all three as
     * {@code tenant/wdN/site}, e.g. {@code salesforce/wd12/External_Career_Site}.
     */
    public record WorkdayToken(String tenant, String shard, String site) {

        /** @throws AtsFetchException unless the token is exactly {@code tenant/wdN/site} */
        public static WorkdayToken parse(String token) {
            if (token == null) {
                throw new AtsFetchException("Workday board token is missing");
            }
            String[] parts = token.split("/", -1);
            if (parts.length != 3
                    || !WORKDAY_SHARD.matcher(parts[1]).matches()
                    || !WORKDAY_SITE.matcher(parts[2]).matches()) {
                throw new AtsFetchException(
                        "Workday board token '" + token + "' is not in the form tenant/wdN/site");
            }
            return new WorkdayToken(requireSubdomainLabel(parts[0]),
                    parts[1].toLowerCase(Locale.ROOT), parts[2]);
        }

        public String host() {
            return tenant + "." + shard + ".myworkdayjobs.com";
        }

        /** The canonical stored form, {@code tenant/wdN/site}. */
        public String token() {
            return tenant + "/" + shard + "/" + site;
        }

        @Override
        public String toString() {
            return token();
        }
    }
}
