package com.jobx.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.fetcher.JobLabels;
import com.jobx.fetcher.JsonLd;
import com.jobx.resolve.SlugCandidates;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads job postings off a careers page that isn't a known ATS (PR E, platform
 * {@code CUSTOM}). A pure function of (page URL, HTML), so it runs the same on a
 * live fetch, a rendered DOM and a benchmark snapshot.
 *
 * Two strategies, in order, stopping at the first that yields anything:
 *
 *  1. JSON-LD {@code JobPosting} blocks (schema.org), including arrays,
 *     {@code @graph} and {@code ItemList.itemListElement}. Pages publish these
 *     for Google for Jobs, so they are the most reliable source there is.
 *  2. Listing detection. Same-site links whose URL looks like a job detail page
 *     are grouped by their DOM position (tag and first class of the link and
 *     two ancestors). A real listing repeats a structure; nav and footer links
 *     don't. Within a group, titles must look like titles, and the group as a
 *     whole must read like job titles (enough of them name a role).
 *
 * Precision matters more than recall here: a fake job in someone's feed is worse
 * than a missed one, because the crawler's misses show up as FAILED health or a
 * short list, while a false positive looks exactly like a real posting. Every
 * filter below exists because a real page in the benchmark corpus
 * ({@code CrawlerBenchmarkTest}) produced that false positive.
 *
 * Nothing found is an empty list. Whether that means "not hiring" or "page we
 * can't read" is the caller's call, via {@link PageSignals#saysNoOpenings}.
 */
public final class CareerPageExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A path segment that marks a job area: /jobs/, /careers/, /remote-jobs/,
     * /open-positions/ … "opportunities" counts only on its own or next to a
     * job word, so /funding-opportunities/ is not mistaken for one.
     */
    private static final Pattern JOBBY_SEGMENT = Pattern.compile(
            "(?i)(.*(job|career|position|opening|vacanc|role|requisition|stellen|vacature|empleo).*"
                    + "|opportunit(y|ies))");

    /** A segment after the job area that makes the link a filter or info page, not a posting. */
    private static final Set<String> FACET_SEGMENTS = Set.of(
            "type", "types", "category", "categories", "location", "locations", "department",
            "departments", "team", "teams", "tag", "tags", "search", "filter", "filters", "page",
            "feed", "rss", "benefits", "culture", "life", "faq", "faqs", "about", "students",
            "university", "perks", "values", "alumni", "events", "blog", "news", "login", "signup",
            "sign-in", "register", "account", "profile", "saved", "alerts", "all", "index", "list",
            "listings", "results", "hiring-process", "how-we-hire", "inclusion", "diversity", "fellows",
            "programs", "early-careers", "graduates", "internships", "campus", "teams-and-roles");

    private static final Pattern NOISE_PATH = Pattern.compile(
            "(?i)/(blog|news|press|events?|tag|tags|category|categories|author|media|podcast|webinars?|"
                    + "resources|docs|help|support|legal|privacy|terms|cookies?)(/|$)");

    private static final Pattern FILE_DOWNLOAD = Pattern.compile(
            "(?i)\\.(pdf|docx?|xlsx?|pptx?|zip|rar|png|jpe?g|gif|svg|webp|mp4|mp3|ics|csv|txt|xml|json|rss)$");

    private static final Pattern ID_PARAM = Pattern.compile(
            "(?i)(^|&)(gh_jid|jobid|job_id|job|jid|id|reqid|req_id|requisitionid|requisition_id|postingid|"
                    + "posting_id|positionid|position_id|vacancyid|vacancy_id|jobreqid|jobcode|opening_id)=([^&]+)");

    private static final Pattern PAGINATION_PARAM = Pattern.compile("(?i)(^|&)(page|p|offset|start|pg)=");

    /** Link texts that are never a job title (compared after normalisation). */
    private static final Set<String> STOP_TITLES = Set.of(
            "about", "about us", "blog", "login", "log in", "sign in", "sign up", "register", "privacy",
            "privacy policy", "terms", "cookies", "contact", "contact us", "home", "careers", "jobs",
            "open roles", "open positions", "openings", "see all jobs", "view all jobs", "all jobs",
            "view jobs", "see jobs", "see open roles", "view open roles", "see all roles", "view all roles",
            "apply", "apply now", "apply here", "learn more", "read more", "more", "details", "view",
            "view job", "view role", "view details", "see details", "job details", "benefits", "perks",
            "culture", "our culture", "our values", "values", "teams", "our teams", "locations",
            "departments", "faq", "faqs", "students", "graduates", "internships", "early careers",
            "university", "life", "news", "press", "events", "next", "previous", "prev", "back",
            "search", "search jobs", "job search", "join us", "join our team", "work with us",
            "how we hire", "how we work", "hiring process", "inclusion", "diversity", "subscribe",
            "rss", "feed", "share", "linkedin", "twitter", "facebook", "instagram", "youtube", "glassdoor");

    private static final Pattern STOP_PREFIX = Pattern.compile(
            "^(life at|working at|work at|careers at|jobs at|meet (the|our)|see all|view all|browse|explore|"
                    + "back to|join (us|our)|learn (more|about)|read (more|about)|why (join|work)|how we|our (story|mission|"
                    + "values|culture|benefits|team)|more (jobs|roles|about)|all (jobs|roles|openings|positions)|"
                    + "sign (up|in)|subscribe|follow us|apply (now|today|here)|search)\\b");

    /**
     * "Sales Jobs", "Engineering Roles", "Internship Opportunities": a link to a
     * category of postings, not a posting. Real titles don't end on the plural.
     */
    private static final Pattern CATEGORY_TITLE = Pattern.compile(
            "\\b(jobs|roles|openings|positions|careers|opportunities|vacancies|listings)$");

    /** Subdomains that hold articles about working somewhere, never postings. */
    private static final Pattern NOISE_HOST = Pattern.compile(
            "(?i)^(blog|blogs|news|press|newsroom|media|community|forum|forums|support|help|docs|developer|"
                    + "developers|status|events|stories|engineering|investors?|ir)\\.");

    /**
     * Words that make a link text read like a job title. A group passes only if
     * enough of its titles contain one; this is what keeps a careers landing
     * page's "How we work / Grow with us / Benefits" nav row out of a feed.
     */
    private static final Pattern ROLE_WORD = Pattern.compile(
            "(?i)\\b(engineer(ing)?|developer|programmer|architect|designer|manager|lead|head|"
                    + "director|chief|officer|vp|vice president|president|analyst|scientist|researcher|"
                    + "specialist|consultant|associate|executive|coordinator|administrator|admin|assistant|"
                    + "intern(ship)?|trainee|apprentice|fellow(ship)?|representative|rep|agent|advisor|adviser|"
                    + "accountant|controller|counsel|attorney|lawyer|paralegal|recruiter|recruiting|"
                    + "writer|editor|producer|marketer|strategist|technician|"
                    + "operator|owner|staff|principal|senior|sr|junior|jr|"
                    + "sde|sdet|swe|qa|devops|sre|"
                    + "nurse|doctor|physician|teacher|instructor|professor|lecturer|tutor|driver|cook|chef|"
                    + "clerk|cashier|mechanic|electrician|planner|buyer|auditor|economist|statistician|"
                    + "illustrator|animator|photographer|translator|evangelist|advocate|"
                    + "generalist|ambassador|expert|tester|investigator|inspector|surveyor|"
                    + "entwickler|ingenieur|leiter|berater|desarrollador|ingeniero)\\b");

    private static final double MIN_ROLE_RATIO = 0.5;

    /** Groups smaller than this need a role word in every title. */
    private static final int SMALL_GROUP = 4;

    /** A URL linked more often than this on one page is a facet (a category, a location), not a posting. */
    private static final int MAX_LINKS_PER_POSTING = 3;

    private CareerPageExtractor() {
    }

    public static List<ExtractedJob> extract(URI pageUri, String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        return extract(pageUri, Jsoup.parse(html, pageUri == null ? "" : pageUri.toString()));
    }

    public static List<ExtractedJob> extract(URI pageUri, Document doc) {
        List<ExtractedJob> fromJsonLd = fromJsonLd(doc, pageUri);
        if (!fromJsonLd.isEmpty()) {
            return fromJsonLd;
        }
        return fromListing(doc, pageUri);
    }

    // ---------------------------------------------------------------------
    // 1. JSON-LD
    // ---------------------------------------------------------------------

    static List<ExtractedJob> fromJsonLd(Document doc, URI pageUri) {
        Map<String, ExtractedJob> byKey = new LinkedHashMap<>();
        for (JsonNode node : JsonLd.nodes(doc, MAPPER)) {
            collectPostings(node, pageUri, byKey, 0);
        }
        return List.copyOf(byKey.values());
    }

    private static void collectPostings(JsonNode node, URI pageUri, Map<String, ExtractedJob> into, int depth) {
        if (node == null || depth > 4) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectPostings(child, pageUri, into, depth + 1));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (hasType(node, "JobPosting")) {
            toJob(node, pageUri).ifPresent(job -> into.putIfAbsent(key(job), job));
            return;
        }
        if (hasType(node, "ItemList")) {
            for (JsonNode element : iterable(node.path("itemListElement"))) {
                JsonNode item = element.has("item") ? element.get("item") : element;
                if (item.isObject() && hasType(item, "JobPosting")) {
                    toJob(item, pageUri).ifPresent(job -> into.putIfAbsent(key(job), job));
                }
            }
        }
        // @graph is already flattened by JsonLd.nodes; nested arrays of postings
        // under other keys are not a schema.org shape worth guessing at.
    }

    private static Optional<ExtractedJob> toJob(JsonNode posting, URI pageUri) {
        String title = cleanText(firstNonBlank(posting.path("title").asText(""), posting.path("name").asText("")));
        if (title == null || title.length() < 2 || title.length() > 200) {
            return Optional.empty();
        }
        String url = null;
        String rawUrl = posting.path("url").asText("");
        if (!rawUrl.isBlank()) {
            url = resolve(pageUri, rawUrl).flatMap(CanonicalUrl::of).orElse(null);
        }
        String location = JsonLd.location(posting);
        if (location == null && "TELECOMMUTE".equalsIgnoreCase(posting.path("jobLocationType").asText(""))) {
            location = "Remote";
        }
        JsonNode type = posting.path("employmentType");
        String employmentType = type.isArray() && !type.isEmpty() ? type.get(0).asText(null) : type.asText(null);
        String description = posting.path("description").asText("");
        return Optional.of(new ExtractedJob(
                title,
                url,
                JobLabels.clean(location),
                null,
                JobLabels.employmentType(employmentType),
                parseDate(posting.path("datePosted").asText("")),
                description.isBlank() ? null : Jsoup.parse(description).text().trim()));
    }

    /** The JSON-LD JobPosting on a detail page, if there is one. */
    public static Optional<ExtractedJob> detailPosting(Document doc, URI pageUri) {
        return JsonLd.find(doc, MAPPER, "JobPosting").flatMap(node -> toJob(node, pageUri));
    }

    /**
     * The posting text on a detail page: its JSON-LD description, else
     * {@code <main>}/{@code <article>}, else the element with the most direct
     * text. Null when the page has nothing that reads like a description.
     */
    public static String detailDescription(Document doc, URI pageUri) {
        Optional<ExtractedJob> posting = detailPosting(doc, pageUri);
        if (posting.isPresent() && posting.get().description() != null) {
            return posting.get().description();
        }
        Element body = doc.body();
        if (body == null) {
            return null;
        }
        Element copy = body.clone();
        copy.select("script, style, noscript, template, svg, nav, header, footer, form, aside").remove();
        Element main = copy.selectFirst("main, article, [role=main]");
        String text = main != null ? main.text() : largestTextBlock(copy);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String largestTextBlock(Element root) {
        Element best = null;
        int bestLength = 0;
        for (Element el : root.select("div, section")) {
            int own = 0;
            for (Element child : el.children()) {
                if (child.tagName().matches("p|ul|ol|h[1-6]|li|table")) {
                    own += child.text().length();
                }
            }
            if (own > bestLength) {
                best = el;
                bestLength = own;
            }
        }
        return best != null ? best.text() : root.text();
    }

    // ---------------------------------------------------------------------
    // 2. Listing detection
    // ---------------------------------------------------------------------

    /** One link that passed the URL and title filters. */
    private record Candidate(Element anchor, String url, String title, String signature, String shape) {
    }

    static List<ExtractedJob> fromListing(Document doc, URI pageUri) {
        if (pageUri == null || pageUri.getHost() == null) {
            return List.of();
        }
        String site = SlugCandidates.registrableDomain(pageUri.getHost());
        String pageCanonical = CanonicalUrl.of(pageUri).orElse("");
        String pageTitle = normaliseTitle(doc.title());
        Set<String> headings = new java.util.HashSet<>();
        for (Element h : doc.select("h1")) {
            headings.add(normaliseTitle(h.text()));
        }

        // How often each URL is linked on the whole page. A category or location
        // link sits on every card that has that tag; a posting is linked once or
        // twice (title, "Apply").
        Map<String, Integer> linkCounts = new HashMap<>();
        List<Element> anchors = doc.select("a[href]");
        Map<Element, String> canonicalByAnchor = new HashMap<>();
        for (Element a : anchors) {
            Optional<String> url = jobUrl(a, pageUri, site, pageCanonical);
            if (url.isPresent()) {
                canonicalByAnchor.put(a, url.get());
                linkCounts.merge(url.get(), 1, Integer::sum);
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Element a : anchors) {
            String url = canonicalByAnchor.get(a);
            if (url == null || linkCounts.get(url) > MAX_LINKS_PER_POSTING) {
                continue;
            }
            String title = titleFor(a);
            if (!isPlausibleTitle(title, pageTitle, headings)) {
                continue;
            }
            candidates.add(new Candidate(a, url, title, signature(a), shape(url)));
        }

        // Group by DOM position; within a group, one entry per posting URL.
        Map<String, LinkedHashMap<String, Candidate>> groups = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            groups.computeIfAbsent(c.signature(), k -> new LinkedHashMap<>()).putIfAbsent(c.url(), c);
        }

        List<List<Candidate>> qualifying = new ArrayList<>();
        for (LinkedHashMap<String, Candidate> group : groups.values()) {
            List<Candidate> members = List.copyOf(group.values());
            // Small groups are where nav rows hide, so they must read as roles
            // throughout; a big repeated list may carry a few odd titles.
            double needed = members.size() < SMALL_GROUP ? 1.0 : MIN_ROLE_RATIO;
            if (members.size() >= 2 && roleRatio(members) >= needed) {
                qualifying.add(members);
            }
        }
        if (qualifying.isEmpty()) {
            return List.of();
        }

        // The largest repeated group is the listing. Other qualifying groups
        // join it only when their links share its URL shape (a "featured roles"
        // strip rendered with different markup, say), never on markup alone.
        qualifying.sort((x, y) -> Integer.compare(y.size(), x.size()));
        List<Candidate> winner = qualifying.get(0);
        String shape = dominantShape(winner);
        Map<String, Candidate> chosen = new LinkedHashMap<>();
        winner.forEach(c -> chosen.putIfAbsent(c.url(), c));
        for (List<Candidate> other : qualifying.subList(1, qualifying.size())) {
            if (Objects.equals(dominantShape(other), shape)) {
                other.forEach(c -> chosen.putIfAbsent(c.url(), c));
            }
        }

        // Back into document order, then read each card's labels.
        Set<String> jobUrls = chosen.keySet();
        List<ExtractedJob> jobs = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            Candidate picked = chosen.get(c.url());
            if (picked == null || !emitted.add(c.url())) {
                continue;
            }
            Element card = card(picked.anchor(), jobUrls, canonicalByAnchor);
            jobs.add(new ExtractedJob(
                    picked.title(),
                    picked.url(),
                    label(card, picked.title(), "[class*=location], [class*=Location], [data-location], [itemprop=jobLocation]", "data-location"),
                    label(card, picked.title(), "[class*=department], [class*=Department], [class*=dept], [class*=team], [data-team], [data-department]", "data-team"),
                    employmentType(card, picked.title()),
                    null,
                    null));
        }
        return jobs;
    }

    /** The canonical URL of a link that looks like a job detail page on this site, or empty. */
    static Optional<String> jobUrl(Element a, URI pageUri, String site, String pageCanonical) {
        String href = a.attr("href").trim();
        if (href.isEmpty() || href.startsWith("#") || href.regionMatches(true, 0, "javascript:", 0, 11)
                || href.regionMatches(true, 0, "mailto:", 0, 7) || href.regionMatches(true, 0, "tel:", 0, 4)) {
            return Optional.empty();
        }
        Optional<URI> resolved = resolve(pageUri, href);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        URI uri = resolved.get();
        if (uri.getHost() == null || site == null
                || !site.equals(SlugCandidates.registrableDomain(uri.getHost()))
                || NOISE_HOST.matcher(uri.getHost()).find()) {
            return Optional.empty();
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        if (FILE_DOWNLOAD.matcher(path).find() || NOISE_PATH.matcher(path).find()
                || PAGINATION_PARAM.matcher(query).find()) {
            return Optional.empty();
        }
        Optional<String> canonical = CanonicalUrl.of(uri);
        if (canonical.isEmpty() || canonical.get().equals(pageCanonical)) {
            return Optional.empty();
        }
        return looksLikePosting(path, query) ? canonical : Optional.empty();
    }

    /**
     * A job area segment followed by at least one more segment that isn't a
     * facet (/jobs/8183, /careers/jobs/uuid, /companies/x/jobs/slug), or an
     * id-like query parameter (?gh_jid=123, ?jobId=abc).
     */
    static boolean looksLikePosting(String path, String query) {
        String[] segments = path.replaceAll("^/+|/+$", "").split("/+");
        for (int i = 0; i < segments.length - 1; i++) {
            if (!JOBBY_SEGMENT.matcher(segments[i]).matches()) {
                continue;
            }
            boolean facet = false;
            for (int j = i + 1; j < segments.length; j++) {
                if (FACET_SEGMENTS.contains(segments[j].toLowerCase(Locale.ROOT))) {
                    facet = true;
                    break;
                }
            }
            if (!facet && !segments[segments.length - 1].isBlank()) {
                return true;
            }
            break;
        }
        if (query == null) {
            return false;
        }
        var id = ID_PARAM.matcher(query);
        while (id.find()) {
            String value = id.group(3);
            if (value.matches(".*\\d.*") || value.length() >= 6) {
                return true;
            }
        }
        return false;
    }

    /**
     * The link's title: its own text when that is short; otherwise a heading or
     * "title" element inside it; and when the link is an "Apply" button, the
     * nearest heading in the same card.
     */
    static String titleFor(Element a) {
        // A card-sized link (the whole card is the <a>) names its title in a
        // heading; reading the link's full text would glue on the company,
        // location and date.
        Element inner = a.selectFirst("h1, h2, h3, h4, h5, h6, [class*=title], [class*=Title]");
        if (inner != null) {
            String innerText = stripCallToAction(cleanText(spacedText(inner)));
            if (innerText != null && !isStopTitle(innerText)) {
                return innerText;
            }
        }
        String text = stripCallToAction(cleanText(spacedText(a)));
        if (text != null && text.length() <= 120 && wordCount(text) <= 15 && !isStopTitle(text)) {
            return text;
        }
        String own = cleanText(a.ownText());
        if (own != null && own.length() >= 3 && !isStopTitle(own)) {
            return own;
        }
        String aria = cleanText(firstNonBlank(a.attr("aria-label"), a.attr("title")));
        if (aria != null && !isStopTitle(aria)) {
            return aria;
        }
        // An "Apply" / "View" button: take the card's heading. Only inside a
        // card, i.e. an ancestor that links nowhere else; past that, the
        // nearest heading belongs to a section or a nav menu, not to this link.
        String ownHref = a.absUrl("href");
        Element ancestor = a.parent();
        for (int level = 0; level < 3 && ancestor != null; level++, ancestor = ancestor.parent()) {
            boolean linksElsewhere = ancestor.select("a[href]").stream()
                    .map(link -> link.absUrl("href"))
                    .anyMatch(href -> !href.isEmpty() && !href.equals(ownHref));
            if (linksElsewhere) {
                break;
            }
            Element heading = ancestor.selectFirst("h1, h2, h3, h4, h5, h6, [class*=title], [class*=Title]");
            if (heading != null && !heading.equals(a)) {
                String headingText = cleanText(spacedText(heading));
                if (headingText != null && !isStopTitle(headingText)) {
                    return headingText;
                }
            }
        }
        return text;
    }

    static boolean isPlausibleTitle(String title, String pageTitle, Set<String> headings) {
        if (title == null) {
            return false;
        }
        if (title.length() < 3 || title.length() > 120 || wordCount(title) > 15) {
            return false;
        }
        if (!title.matches(".*\\p{L}.*")) {
            return false;
        }
        String normalised = normaliseTitle(title);
        if (isStopTitle(title) || normalised.equals(pageTitle) || headings.contains(normalised)) {
            return false;
        }
        return true;
    }

    private static boolean isStopTitle(String text) {
        String normalised = normaliseTitle(text);
        return normalised.isEmpty() || STOP_TITLES.contains(normalised) || STOP_PREFIX.matcher(normalised).find()
                || CATEGORY_TITLE.matcher(normalised).find();
    }

    private static String normaliseTitle(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}→›»←‹«➜➔↗↘–—•·]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double roleRatio(List<Candidate> members) {
        long withRole = members.stream().filter(c -> ROLE_WORD.matcher(c.title()).find()).count();
        return (double) withRole / members.size();
    }

    /** Tag + first class of the link and its two nearest ancestors; digits dropped from classes. */
    private static String signature(Element a) {
        StringBuilder sig = new StringBuilder();
        Element el = a;
        for (int level = 0; level < 3 && el != null; level++, el = el.parent()) {
            if (level > 0) {
                sig.append(" < ");
            }
            sig.append(el.tagName());
            String first = el.classNames().stream().findFirst().orElse("");
            if (!first.isEmpty()) {
                sig.append('.').append(first.replaceAll("\\d+", ""));
            }
        }
        return sig.toString();
    }

    /** A URL's shape: its path without the last segment, plus its query parameter names. */
    private static String shape(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String head = slash > 0 ? path.substring(0, slash) : "";
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().replaceAll("=[^&]*", "");
        return uri.getHost() + head + (query.isEmpty() ? "" : "?" + query);
    }

    private static String dominantShape(List<Candidate> members) {
        Map<String, Integer> counts = new HashMap<>();
        for (Candidate c : members) {
            counts.merge(c.shape(), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /**
     * The posting's card: the highest ancestor (up to four levels) that holds no
     * other posting's link. Labels are read only from inside it, so one card's
     * location can never leak onto its neighbour.
     */
    private static Element card(Element anchor, Set<String> jobUrls, Map<Element, String> canonicalByAnchor) {
        String own = canonicalByAnchor.get(anchor);
        Element card = anchor;
        Element parent = anchor.parent();
        for (int level = 0; level < 4 && parent != null; level++, parent = parent.parent()) {
            boolean holdsOther = false;
            for (Element a : parent.select("a[href]")) {
                String url = canonicalByAnchor.get(a);
                if (url != null && !url.equals(own) && jobUrls.contains(url)) {
                    holdsOther = true;
                    break;
                }
            }
            if (holdsOther) {
                break;
            }
            card = parent;
        }
        return card;
    }

    private static String label(Element card, String title, String selector, String dataAttribute) {
        for (String attribute : List.of(dataAttribute, dataAttribute + "s")) {
            String own = card.attr(attribute);
            if (!own.isBlank() && own.length() <= 120) {
                return JobLabels.clean(own);
            }
        }
        for (Element el : card.select(selector)) {
            String data = el.attr(dataAttribute);
            String text = !data.isBlank() ? data : cleanText(el.text());
            if (text != null && text.length() >= 2 && text.length() <= 80 && !text.equalsIgnoreCase(title)) {
                return JobLabels.clean(text);
            }
        }
        return null;
    }

    private static final Pattern EMPLOYMENT_TYPE_TEXT = Pattern.compile(
            "(?i)^(full[- ]?time|part[- ]?time|contract(or)?|intern(ship)?|temporary|temp|freelance|permanent|seasonal)$");

    private static String employmentType(Element card, String title) {
        for (Element el : card.select("*")) {
            if (!el.ownText().isBlank()) {
                String text = cleanText(el.ownText());
                if (text != null && EMPLOYMENT_TYPE_TEXT.matcher(text).matches()) {
                    return JobLabels.employmentType(text);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static boolean hasType(JsonNode node, String type) {
        JsonNode types = node.path("@type");
        if (types.isArray()) {
            for (JsonNode t : types) {
                if (type.equalsIgnoreCase(t.asText())) {
                    return true;
                }
            }
            return false;
        }
        return type.equalsIgnoreCase(types.asText());
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        return node.isArray() ? node : List.of(node);
    }

    private static String key(ExtractedJob job) {
        return job.url() != null ? job.url() : job.title() + "|" + job.location();
    }

    static Optional<URI> resolve(URI base, String href) {
        try {
            URI target = base == null ? new URI(href.trim()) : base.resolve(href.trim().replace(" ", "%20"));
            String scheme = target.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return Optional.empty();
            }
            return Optional.of(target);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            return LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final Pattern TRAILING_CTA = Pattern.compile(
            "(?i)\\s*[→›»➜➔↗]*\\s*\\b(apply( now| here| today)?|view( job| role| details| position)?|learn more|read more|"
                    + "see (more|details)|details|more info)\\s*[→›»➜➔↗]*\\s*$");

    /**
     * An element's text with a space at every element boundary.
     * {@code Element.text()} joins adjacent inline elements with no space, so
     * {@code <span>Engineer</span><span>Apply</span>} reads "EngineerApply" and
     * neither the call-to-action strip nor the role-word check can see the word.
     */
    static String spacedText(Element element) {
        StringBuilder out = new StringBuilder();
        org.jsoup.select.NodeTraversor.traverse((node, depth) -> {
            if (node instanceof org.jsoup.nodes.TextNode text) {
                out.append(text.text()).append(' ');
            } else if (node instanceof Element el && el.normalName().equals("br")) {
                out.append(' ');
            }
        }, element);
        return out.toString();
    }

    /** "Account Executive Apply" → "Account Executive": a button glued onto the title. */
    private static String stripCallToAction(String text) {
        if (text == null) {
            return null;
        }
        String stripped = TRAILING_CTA.matcher(text).replaceFirst("").trim();
        return stripped.length() >= 3 ? stripped : text;
    }

    private static String cleanText(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static int wordCount(String text) {
        return text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
    }
}
