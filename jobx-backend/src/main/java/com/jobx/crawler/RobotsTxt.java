package com.jobx.crawler;

import com.jobx.resolve.SafeUrlFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * robots.txt for CUSTOM careers pages. The crawler fetches a page on a schedule,
 * forever, so it asks first; an ATS API is a published integration point and is
 * not checked here.
 *
 * The file is fetched through {@link SafeUrlFetcher} like every other CUSTOM
 * request, and cached for 24 hours per scheme + host. A missing or unreadable
 * robots.txt means "allowed", which is what the standard says for a 4xx and is
 * the only workable reading of a timeout.
 *
 * Matching follows RFC 9309: the group naming our product token ("jobx") wins
 * over {@code *}; consecutive {@code User-agent} lines share one group; the
 * longest matching rule wins and {@code Allow} wins a tie; {@code *} and a
 * trailing {@code $} are supported in paths.
 */
@Component
@Slf4j
public class RobotsTxt {

    /** Our product token, as it appears in SafeUrlFetcher's User-Agent. */
    static final String AGENT = "jobx";

    private static final Duration TTL = Duration.ofHours(24);

    private final SafeUrlFetcher safeUrlFetcher;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RobotsTxt(SafeUrlFetcher safeUrlFetcher) {
        this(safeUrlFetcher, Clock.systemUTC());
    }

    RobotsTxt(SafeUrlFetcher safeUrlFetcher, Clock clock) {
        this.safeUrlFetcher = safeUrlFetcher;
        this.clock = clock;
    }

    private record Cached(Rules rules, Instant fetchedAt) {
    }

    /** Whether the site's robots.txt lets JobX fetch this URL. Never throws. */
    public boolean isAllowed(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getScheme() == null) {
            return false;
        }
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        Instant now = clock.instant();
        Cached cached = cache.get(origin);
        if (cached == null || cached.fetchedAt().isBefore(now.minus(TTL))) {
            cached = new Cached(load(origin), now);
            cache.put(origin, cached);
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        return cached.rules().allows(path);
    }

    private Rules load(String origin) {
        try {
            Optional<String> body = safeUrlFetcher.fetch(origin + "/robots.txt");
            return body.map(text -> Rules.parse(text, AGENT)).orElse(Rules.ALLOW_ALL);
        } catch (RuntimeException e) {
            log.debug("robots.txt for {} unreadable, treating as allow-all: {}", origin, e.toString());
            return Rules.ALLOW_ALL;
        }
    }

    /** The rules that apply to one user agent. Pure, so it is tested on text directly. */
    record Rules(List<Rule> rules) {

        static final Rules ALLOW_ALL = new Rules(List.of());

        record Rule(boolean allow, Pattern pattern, int length) {
        }

        boolean allows(String path) {
            Rule best = null;
            for (Rule rule : rules) {
                if (rule.pattern().matcher(path).lookingAt()
                        && (best == null || rule.length() > best.length()
                        || (rule.length() == best.length() && rule.allow() && !best.allow()))) {
                    best = rule;
                }
            }
            return best == null || best.allow();
        }

        static Rules parse(String text, String agent) {
            List<Rule> specific = null;
            List<Rule> wildcard = null;

            List<String> groupAgents = new ArrayList<>();
            List<Rule> groupRules = new ArrayList<>();
            boolean lastWasAgent = false;

            for (String rawLine : (text + "\nuser-agent: __end__").split("\\r?\\n")) {
                String line = rawLine.replaceFirst("#.*$", "").trim();
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();

                if (field.equals("user-agent")) {
                    if (!lastWasAgent && !groupAgents.isEmpty()) {
                        // A new group starts: file the finished one.
                        for (String a : groupAgents) {
                            String name = a.toLowerCase(Locale.ROOT);
                            if (name.equals("*")) {
                                wildcard = merge(wildcard, groupRules);
                            } else if (name.equals(agent) || name.startsWith(agent + "/")) {
                                specific = merge(specific, groupRules);
                            }
                        }
                        groupAgents = new ArrayList<>();
                        groupRules = new ArrayList<>();
                    }
                    groupAgents.add(value);
                    lastWasAgent = true;
                } else if (field.equals("allow") || field.equals("disallow")) {
                    lastWasAgent = false;
                    if (value.isEmpty()) {
                        continue; // "Disallow:" with no path allows everything
                    }
                    groupRules.add(new Rule(field.equals("allow"), compile(value), value.length()));
                } else {
                    lastWasAgent = false;
                }
            }
            List<Rule> chosen = specific != null ? specific : wildcard;
            return chosen == null ? ALLOW_ALL : new Rules(List.copyOf(chosen));
        }

        private static List<Rule> merge(List<Rule> into, List<Rule> rules) {
            List<Rule> merged = into == null ? new ArrayList<>() : into;
            merged.addAll(rules);
            return merged;
        }

        private static Pattern compile(String path) {
            boolean anchored = path.endsWith("$");
            String body = anchored ? path.substring(0, path.length() - 1) : path;
            String[] parts = body.split("\\*", -1);
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(parts[i]));
            }
            return Pattern.compile(regex + (anchored ? "$" : ""));
        }
    }
}
