package com.jobx.crawler;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a careers page says about itself, apart from its jobs. Pure and static.
 */
public final class PageSignals {

    /**
     * Phrases a careers page uses to say it has nothing open. Matching one is
     * what separates "this company isn't hiring" (a successful empty fetch) from
     * "we no longer recognise this page's listing" (FAILED), so a redesign can
     * never pass as a quiet board. Only consulted when no job was found.
     */
    private static final Pattern NO_OPENINGS = Pattern.compile(
            "\\b(no (current |open |available )?(positions?|roles?|jobs?|openings?|vacanc(y|ies)|opportunities)"
                    + "( (are )?(currently )?(open|available|at the moment|right now))?"
                    + "|(don.?t|do not) have any (job )?(open(ings?)?|open (roles?|positions?)|vacanc(y|ies)|positions?)"
                    + "|not (currently )?hiring"
                    + "|there are (currently )?no (open |current |available )?(positions?|roles?|jobs?|openings?|vacanc(y|ies))"
                    + "|currently (have )?no (open |current )?(positions?|roles?|jobs?|openings?|vacanc(y|ies))"
                    + "|no jobs? (found|available)"
                    + "|check back (again )?(later|soon))",
            Pattern.CASE_INSENSITIVE);

    /** Interstitials that unambiguously mean "a bot wall is in the way". */
    private static final Pattern CHALLENGE_STRONG = Pattern.compile(
            "cf-chl|challenge-platform|_cf_chl_opt|cf_chl_|<title>\\s*just a moment\\.\\.\\.|attention required! \\| cloudflare"
                    + "|verify (that )?you are (a )?human|pardon our interruption|px-captcha|_incapsula_resource"
                    + "|ddos protection by|access denied</h1>.{0,400}reference #",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CHALLENGE_WEAK = Pattern.compile("captcha", Pattern.CASE_INSENSITIVE);

    private PageSignals() {
    }

    /** The page's visible text says it has no open roles. */
    public static boolean saysNoOpenings(Document doc) {
        return NO_OPENINGS.matcher(visibleText(doc)).find();
    }

    /**
     * A Cloudflare/Akamai/PerimeterX-style challenge rather than the page.
     * JobX never tries to get past one of these: the board is reported as
     * bot-protected and stays unwatchable. A plain "captcha" mention only counts
     * on a near-empty page, because a contact form's reCAPTCHA is not a wall.
     */
    public static boolean isBotChallenge(String html, Document doc) {
        if (html == null) {
            return false;
        }
        if (CHALLENGE_STRONG.matcher(html).find()) {
            return true;
        }
        return CHALLENGE_WEAK.matcher(html).find() && visibleText(doc).length() < 600;
    }

    /**
     * The page probably builds its content in JavaScript: an SPA mount point
     * and very little server-rendered text. Gates the rendered SNIFF, which is
     * only worth a browser when the static HTML had no chance of naming a board.
     */
    public static boolean looksJsDriven(Document doc) {
        int textLength = visibleText(doc).length();
        boolean spaRoot = doc.selectFirst("#root, #__next, #__nuxt, #app, #___gatsby, app-root, [ng-version], [data-reactroot]") != null;
        int scripts = doc.select("script[src]").size();
        if (textLength < 300) {
            return true;
        }
        return textLength < 2500 && (spaRoot || scripts >= 6);
    }

    /**
     * The site's own name for itself: {@code og:site_name}, else the page title
     * with "Careers"/"Jobs" decoration removed, else the host's domain label.
     */
    public static String siteName(Document doc, URI pageUri) {
        Element og = doc.selectFirst("meta[property=og:site_name], meta[name=application-name]");
        if (og != null && !og.attr("content").isBlank()) {
            return clip(og.attr("content").trim());
        }
        String title = doc.title() == null ? "" : doc.title().trim();
        if (!title.isEmpty()) {
            for (String part : title.split("\\s+[|\\-–—:·]\\s+")) {
                String cleaned = part.replaceAll("(?i)\\b(careers?|jobs?|open (roles|positions)|work with us|join us|join our team|at)\\b", "")
                        .replaceAll("\\s+", " ").trim();
                if (cleaned.length() >= 2) {
                    return clip(cleaned);
                }
            }
        }
        String host = pageUri == null ? null : pageUri.getHost();
        if (host == null) {
            return null;
        }
        String label = host.toLowerCase(Locale.ROOT).replaceFirst("^(www|careers|jobs)\\.", "").split("\\.")[0];
        return label.isEmpty() ? null : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    static String visibleText(Document doc) {
        Element body = doc.body();
        if (body == null) {
            return "";
        }
        Element copy = body.clone();
        copy.select("script, style, noscript, template, svg").remove();
        return copy.text();
    }

    private static String clip(String value) {
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}
