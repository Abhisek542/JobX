package com.jobx.enums;

/**
 * How the user asked the add-company flow to look a company up. The modal makes
 * them pick, so each input takes one predictable path instead of the resolver
 * guessing whether "razorpay.com" was meant as a name or a link.
 *
 * <ul>
 *   <li>{@code NAME}: catalog, then slug probing. Never fetches a web page.</li>
 *   <li>{@code URL}: read the board out of the link, else sniff the careers page
 *       (following at most a few same-site careers links, one hop), else probe
 *       on the domain label. The catalog is skipped, since a link is more
 *       specific than any name match.</li>
 * </ul>
 */
public enum ResolveMode {
    NAME,
    URL
}
