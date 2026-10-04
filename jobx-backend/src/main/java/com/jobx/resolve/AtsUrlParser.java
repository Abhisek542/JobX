package com.jobx.resolve;

import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.BoardTokens;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises ATS boards inside a blob of text — a URL the user pasted, or the
 * whole HTML of a careers page.
 *
 * This is the deterministic half of add-company resolution and the only half
 * that can recover a token nobody would guess: Razorpay's careers page links to
 * {@code job-boards.greenhouse.io/razorpaysoftwareprivatelimited}, verified
 * live. Reading it off the page is exactly what CLAUDE.md means by "read the
 * token from the real careers URL, never guess it from the company name".
 *
 * Two things learned from live pages shape the patterns below:
 *
 *  - REGIONAL HOSTS ARE NOT OPTIONAL. Groww's careers page links to
 *    {@code job-boards.eu.greenhouse.io/groww}; a pattern anchored on
 *    {@code boards.greenhouse.io} finds nothing on the second company tested.
 *  - A PAGE CAN NAME THE PLATFORM WITHOUT NAMING THE BOARD. Atlan's careers
 *    page mentions {@code *.ashbyhq.com} only in a Content-Security-Policy
 *    header, because the board itself is rendered by JavaScript. That is not a
 *    token, but it is worth a great deal: {@link #platformHint} returns it, and
 *    it narrows slug probing from five platforms to one.
 *
 * The API hosts are matched as well as the public ones, so a URL copied out of
 * this project's own docs or a browser network tab resolves too.
 */
public final class AtsUrlParser {

    private AtsUrlParser() {
    }

    /** A board token: starts alphanumeric, then the punctuation ATS slugs actually use. */
    private static final String TOKEN = "([A-Za-z0-9][A-Za-z0-9._-]{0,99})";

    /**
     * A token that lives in a hostname — one DNS label, the same definition
     * {@link BoardTokens} enforces in the fetchers. The lookbehind stops a match
     * starting mid-host: without it {@code a.b.bamboohr.com} would yield "b".
     * {@link #TOKEN} is wrong here because it allows dots.
     */
    private static final String LABEL = "(?<![A-Za-z0-9.-])(" + BoardTokens.SUBDOMAIN_LABEL + ")";

    /** Workday's {@code /en-US/} segment, which sits between the host and the site name. */
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}-[a-z]{2}", Pattern.CASE_INSENSITIVE);

    /**
     * Path or subdomain segments that are never a company's board token. Without
     * this, {@code apply.workable.com/...} yields the token "apply" and
     * {@code boards.greenhouse.io/embed/job_board} yields "embed".
     */
    private static final Set<String> RESERVED = Set.of(
            "embed", "api", "apply", "jobs", "job", "careers", "career", "search",
            "job_board", "job-board", "posting-api", "postings", "widget", "accounts",
            "companies", "boards", "www", "static", "assets", "cdn", "images", "img",
            "css", "js", "help", "blog", "resources", "support", "docs", "status",
            "v0", "v1", "v2", "v3", "index.html", "favicon.ico", "robots.txt",
            "app", "platform", "ats", "board", "wday");

    /**
     * One way a URL names a board: the pattern, and how to read the token out of
     * a match. Most platforms carry the token in group 1; Workday needs three
     * groups to build {@code tenant/wdN/site}, which is why this isn't just a
     * Pattern. The token function returns null to reject a match.
     */
    private record Rule(Pattern pattern, Function<Matcher, String> token) {
    }

    /**
     * Ordered per platform, most specific (API) form first, so
     * {@code api.lever.co/v0/postings/fampay} yields "fampay" and not "v0".
     */
    private static final Map<AtsPlatform, List<Rule>> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put(AtsPlatform.GREENHOUSE, rules(
                "boards-api\\.greenhouse\\.io/v[0-9]+/boards/" + TOKEN,
                // The embed form carries the token in a query parameter, not a path segment
                "(?:job-)?boards(?:\\.[a-z]{2})?\\.greenhouse\\.io/embed/job_board\\?(?:[^\"\\s]*&(?:amp;)?)?for=" + TOKEN,
                "(?:job-)?boards(?:\\.[a-z]{2})?\\.greenhouse\\.io/" + TOKEN));

        PATTERNS.put(AtsPlatform.LEVER, rules(
                "api\\.lever\\.co/v[0-9]+/postings/" + TOKEN,
                "jobs(?:\\.[a-z]{2})?\\.lever\\.co/" + TOKEN));

        PATTERNS.put(AtsPlatform.ASHBY, rules(
                "api\\.ashbyhq\\.com/posting-api/job-board/" + TOKEN,
                "jobs\\.ashbyhq\\.com/" + TOKEN));

        PATTERNS.put(AtsPlatform.WORKABLE, rules(
                "apply\\.workable\\.com/api/v[0-9]+/(?:widget/)?accounts/" + TOKEN,
                "apply\\.workable\\.com/" + TOKEN,
                // Legacy per-company subdomain. RESERVED keeps apply/help/www out.
                TOKEN + "\\.workable\\.com"));

        PATTERNS.put(AtsPlatform.SMARTRECRUITERS, rules(
                "api\\.smartrecruiters\\.com/v[0-9]+/companies/" + TOKEN,
                "(?:jobs|careers)\\.smartrecruiters\\.com/" + TOKEN));

        // Next-wave platforms: recognised so the resolver can name them; each
        // becomes watchable only when its fetcher ships (new-ats-add.md).

        // Public site, the wday/cxs API form, and any of them with a locale
        // segment: salesforce.wd12.myworkdayjobs.com/en-US/External_Career_Site
        PATTERNS.put(AtsPlatform.WORKDAY, List.of(new Rule(Pattern.compile(
                LABEL + "\\.(wd[0-9]{1,3})\\.myworkdayjobs\\.com/"
                        + "(?:wday/cxs/" + BoardTokens.SUBDOMAIN_LABEL + "/)?"
                        + "(?:[a-z]{2}-[a-z]{2}/)?"
                        + "([A-Za-z0-9_-]{1,100})",
                Pattern.CASE_INSENSITIVE), AtsUrlParser::workdayToken)));

        PATTERNS.put(AtsPlatform.RIPPLING, rules(
                "api\\.rippling\\.com/platform/api/ats/v[0-9]+/board/" + TOKEN,
                "ats\\.rippling\\.com/api/v[0-9]+/board/" + TOKEN,
                "ats\\.rippling\\.com/" + TOKEN));

        PATTERNS.put(AtsPlatform.BAMBOOHR, hostRules(LABEL + "\\.bamboohr\\.com/(?:careers|jobs)"));

        PATTERNS.put(AtsPlatform.JOBVITE, rules("jobs\\.jobvite\\.com/" + TOKEN));

        PATTERNS.put(AtsPlatform.JAZZHR, hostRules(LABEL + "\\.applytojob\\.com"));

        // The token is the whole label, e.g. careers-acme.icims.com -> "careers-acme"
        PATTERNS.put(AtsPlatform.ICIMS, hostRules(LABEL + "\\.icims\\.com/jobs"));

        PATTERNS.put(AtsPlatform.GUSTO, rules("jobs\\.gusto\\.com/boards/" + TOKEN));
    }

    /**
     * Bare host mentions — enough to name the platform, never enough to name the
     * board. Insertion-ordered so the answer is stable when a page mentions two.
     * The next-wave hints are deliberately narrow: a page that merely uses
     * Rippling or Gusto for payroll must not be read as hosting its board there.
     */
    private static final Map<AtsPlatform, Pattern> HOST_HINTS = new LinkedHashMap<>();

    static {
        hint(AtsPlatform.GREENHOUSE, "greenhouse\\.io");
        hint(AtsPlatform.LEVER, "lever\\.co");
        hint(AtsPlatform.ASHBY, "ashbyhq\\.com");
        hint(AtsPlatform.WORKABLE, "workable\\.com");
        hint(AtsPlatform.SMARTRECRUITERS, "smartrecruiters\\.com");
        hint(AtsPlatform.WORKDAY, "myworkdayjobs\\.com");
        hint(AtsPlatform.RIPPLING, "ats\\.rippling\\.com");
        hint(AtsPlatform.BAMBOOHR, "bamboohr\\.com/careers");
        hint(AtsPlatform.JOBVITE, "jobs\\.jobvite\\.com");
        hint(AtsPlatform.JAZZHR, "applytojob\\.com");
        hint(AtsPlatform.ICIMS, "icims\\.com/jobs");
        hint(AtsPlatform.GUSTO, "jobs\\.gusto\\.com");
    }

    private static void hint(AtsPlatform platform, String regex) {
        HOST_HINTS.put(platform, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** Rules whose token is a path segment in group 1. */
    private static List<Rule> rules(String... regexes) {
        List<Rule> compiled = new ArrayList<>(regexes.length);
        for (String regex : regexes) {
            compiled.add(new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE),
                    m -> cleanToken(m.group(1))));
        }
        return List.copyOf(compiled);
    }

    /** Rules whose token is a subdomain in group 1 — lower-cased, as hostnames are. */
    private static List<Rule> hostRules(String... regexes) {
        List<Rule> compiled = new ArrayList<>(regexes.length);
        for (String regex : regexes) {
            compiled.add(new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), m -> {
                String label = m.group(1).toLowerCase(Locale.ROOT);
                return RESERVED.contains(label) ? null : label;
            }));
        }
        return List.copyOf(compiled);
    }

    /** {@code tenant/wdN/site}, or null when the match isn't really a board. */
    private static String workdayToken(Matcher m) {
        String tenant = m.group(1);
        String site = m.group(3);
        // The site is checked against "wday" only, not RESERVED: real Workday
        // site names include "Careers" and "External", which RESERVED would drop.
        if (RESERVED.contains(tenant.toLowerCase(Locale.ROOT))
                || site.equalsIgnoreCase("wday")
                // host/en-US with no site after it: the locale is not the site
                || LOCALE.matcher(site).matches()) {
            return null;
        }
        try {
            return BoardTokens.WorkdayToken.parse(tenant + "/" + m.group(2) + "/" + site).token();
        } catch (AtsFetchException e) {
            return null;
        }
    }

    /**
     * The board a single URL points at, if any. Tolerates a bare host with no
     * scheme, which is what users paste about half the time.
     */
    public static Optional<BoardRef> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return findAll(url).stream().findFirst();
    }

    /**
     * Every distinct board referenced in a blob of text, most-referenced first.
     *
     * Frequency is the ranking signal on purpose: a careers page links its own
     * board from the nav, the hero button and the footer (Groww's links it three
     * times), while an incidental mention of some other company's board appears
     * once. Deliberately scans raw text rather than a parsed DOM — on Razorpay's
     * page the token sits in an {@code href}, but ATS links just as often live
     * inside an inline script or a JSON island that a walk over anchors misses.
     */
    public static List<BoardRef> findAll(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Map<BoardRef, Integer> hits = new LinkedHashMap<>();
        for (Map.Entry<AtsPlatform, List<Rule>> entry : PATTERNS.entrySet()) {
            for (Rule rule : entry.getValue()) {
                Matcher matcher = rule.pattern().matcher(text);
                while (matcher.find()) {
                    String token = rule.token().apply(matcher);
                    if (token == null) {
                        continue;
                    }
                    hits.merge(new BoardRef(entry.getKey(), token), 1, Integer::sum);
                }
            }
        }

        return hits.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The platform a page is on when the token could not be read — a board
     * rendered client-side, or a host named only in a CSP header. Callers use it
     * to narrow slug probing; it is never enough to add a company by itself.
     */
    public static Optional<AtsPlatform> platformHint(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Map.Entry<AtsPlatform, Pattern> entry : HOST_HINTS.entrySet()) {
            if (entry.getValue().matcher(text).find()) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** The public board page for a resolved board — shown on the confirmation card. */
    public static String boardUrl(AtsPlatform platform, String token) {
        return switch (platform) {
            case GREENHOUSE -> "https://job-boards.greenhouse.io/" + token;
            case LEVER -> "https://jobs.lever.co/" + token;
            case ASHBY -> "https://jobs.ashbyhq.com/" + token;
            case WORKABLE -> "https://apply.workable.com/" + token;
            case SMARTRECRUITERS -> "https://jobs.smartrecruiters.com/" + token;
            case WORKDAY -> workdayBoardUrl(token);
            case RIPPLING -> "https://ats.rippling.com/" + token;
            case BAMBOOHR -> "https://" + token + ".bamboohr.com/careers";
            case JOBVITE -> "https://jobs.jobvite.com/" + token;
            case JAZZHR -> "https://" + token + ".applytojob.com/apply";
            case ICIMS -> "https://" + token + ".icims.com/jobs";
            case GUSTO -> "https://jobs.gusto.com/boards/" + token;
            case UNSUPPORTED -> null;
        };
    }

    private static String workdayBoardUrl(String token) {
        try {
            BoardTokens.WorkdayToken workday = BoardTokens.WorkdayToken.parse(token);
            return "https://" + workday.host() + "/" + workday.site();
        } catch (AtsFetchException e) {
            return null;
        }
    }

    private static String cleanToken(String raw) {
        if (raw == null) {
            return null;
        }
        // A URL in prose or markup often drags trailing punctuation into the match.
        String token = raw;
        while (!token.isEmpty()
                && (token.endsWith(".") || token.endsWith("-") || token.endsWith("_"))) {
            token = token.substring(0, token.length() - 1);
        }
        if (token.isEmpty() || RESERVED.contains(token.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return token;
    }
}
