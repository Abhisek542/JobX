package com.jobx.resolve;

import com.jobx.enums.AtsPlatform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * Path or subdomain segments that are never a company's board token. Without
     * this, {@code apply.workable.com/...} yields the token "apply" and
     * {@code boards.greenhouse.io/embed/job_board} yields "embed".
     */
    private static final Set<String> RESERVED = Set.of(
            "embed", "api", "apply", "jobs", "job", "careers", "career", "search",
            "job_board", "job-board", "posting-api", "postings", "widget", "accounts",
            "companies", "boards", "www", "static", "assets", "cdn", "images", "img",
            "css", "js", "help", "blog", "resources", "support", "docs", "status",
            "v0", "v1", "v2", "v3", "index.html", "favicon.ico", "robots.txt");

    /**
     * Ordered per platform, most specific (API) form first, so
     * {@code api.lever.co/v0/postings/fampay} yields "fampay" and not "v0".
     */
    private static final Map<AtsPlatform, List<Pattern>> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put(AtsPlatform.GREENHOUSE, compile(
                "boards-api\\.greenhouse\\.io/v[0-9]+/boards/" + TOKEN,
                // The embed form carries the token in a query parameter, not a path segment
                "(?:job-)?boards(?:\\.[a-z]{2})?\\.greenhouse\\.io/embed/job_board\\?(?:[^\"\\s]*&(?:amp;)?)?for=" + TOKEN,
                "(?:job-)?boards(?:\\.[a-z]{2})?\\.greenhouse\\.io/" + TOKEN));

        PATTERNS.put(AtsPlatform.LEVER, compile(
                "api\\.lever\\.co/v[0-9]+/postings/" + TOKEN,
                "jobs(?:\\.[a-z]{2})?\\.lever\\.co/" + TOKEN));

        PATTERNS.put(AtsPlatform.ASHBY, compile(
                "api\\.ashbyhq\\.com/posting-api/job-board/" + TOKEN,
                "jobs\\.ashbyhq\\.com/" + TOKEN));

        PATTERNS.put(AtsPlatform.WORKABLE, compile(
                "apply\\.workable\\.com/api/v[0-9]+/(?:widget/)?accounts/" + TOKEN,
                "apply\\.workable\\.com/" + TOKEN,
                // Legacy per-company subdomain. RESERVED keeps apply/help/www out.
                TOKEN + "\\.workable\\.com"));

        PATTERNS.put(AtsPlatform.SMARTRECRUITERS, compile(
                "api\\.smartrecruiters\\.com/v[0-9]+/companies/" + TOKEN,
                "(?:jobs|careers)\\.smartrecruiters\\.com/" + TOKEN));
    }

    /** Bare host mentions — enough to name the platform, never enough to name the board. */
    private static final Map<AtsPlatform, Pattern> HOST_HINTS = Map.of(
            AtsPlatform.GREENHOUSE, Pattern.compile("greenhouse\\.io", Pattern.CASE_INSENSITIVE),
            AtsPlatform.LEVER, Pattern.compile("lever\\.co", Pattern.CASE_INSENSITIVE),
            AtsPlatform.ASHBY, Pattern.compile("ashbyhq\\.com", Pattern.CASE_INSENSITIVE),
            AtsPlatform.WORKABLE, Pattern.compile("workable\\.com", Pattern.CASE_INSENSITIVE),
            AtsPlatform.SMARTRECRUITERS, Pattern.compile("smartrecruiters\\.com", Pattern.CASE_INSENSITIVE));

    private static List<Pattern> compile(String... regexes) {
        List<Pattern> compiled = new ArrayList<>(regexes.length);
        for (String regex : regexes) {
            compiled.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return List.copyOf(compiled);
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
        for (Map.Entry<AtsPlatform, List<Pattern>> entry : PATTERNS.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    String token = cleanToken(matcher.group(1));
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
            case UNSUPPORTED -> null;
        };
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
