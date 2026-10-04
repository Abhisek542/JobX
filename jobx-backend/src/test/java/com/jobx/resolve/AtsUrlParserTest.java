package com.jobx.resolve;

import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.FixtureSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deterministic half of add-company resolution.
 *
 * The careers-page fixtures are real HTML captured live on 2026-09-06 and are
 * the three cases that actually matter, one per outcome:
 *  - Razorpay: a token no guess would ever reach, sitting in an href;
 *  - Groww: a token only reachable if regional hosts are handled;
 *  - Atlan: a page that names the ATS but not the board.
 */
class AtsUrlParserTest {

    private static BoardRef parsed(String url) {
        return AtsUrlParser.parse(url).orElseThrow(() -> new AssertionError("no board found in " + url));
    }

    @Nested
    @DisplayName("public board URLs")
    class PublicUrls {

        @Test
        void greenhouse() {
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "razorpaysoftwareprivatelimited"),
                    parsed("https://job-boards.greenhouse.io/razorpaysoftwareprivatelimited"));
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "groww"),
                    parsed("https://boards.greenhouse.io/groww"));
        }

        /**
         * Groww's careers page links the EU host, and it is the second company
         * anyone tests. A pattern anchored on boards.greenhouse.io silently
         * finds nothing here.
         */
        @Test
        void greenhouseRegionalHosts() {
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "groww"),
                    parsed("https://job-boards.eu.greenhouse.io/groww"));
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "groww"),
                    parsed("https://boards.eu.greenhouse.io/groww"));
        }

        @Test
        void greenhouseEmbedForm() {
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "mixpanel"),
                    parsed("https://boards.greenhouse.io/embed/job_board?for=mixpanel"));
            // HTML-escaped ampersands are normal in a page's markup
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "mixpanel"),
                    parsed("https://boards.greenhouse.io/embed/job_board?b=1&amp;for=mixpanel"));
        }

        @Test
        void leverAshbyWorkableSmartRecruiters() {
            assertEquals(new BoardRef(AtsPlatform.LEVER, "fampay"),
                    parsed("https://jobs.lever.co/fampay"));
            assertEquals(new BoardRef(AtsPlatform.ASHBY, "atlan"),
                    parsed("https://jobs.ashbyhq.com/atlan"));
            assertEquals(new BoardRef(AtsPlatform.WORKABLE, "apna"),
                    parsed("https://apply.workable.com/apna/"));
            assertEquals(new BoardRef(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED"),
                    parsed("https://jobs.smartrecruiters.com/PHONEPELIMITED"));
        }

        /** Case is carried through untouched — Lever and Ashby 404 on the wrong one. */
        @Test
        void preservesTokenCase() {
            assertEquals("Sprinto", parsed("https://jobs.lever.co/Sprinto").token());
            assertEquals("Aspora", parsed("https://jobs.ashbyhq.com/Aspora").token());
        }

        @Test
        void deepLinkToASinglePostingStillYieldsTheBoard() {
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "razorpaysoftwareprivatelimited"),
                    parsed("https://job-boards.greenhouse.io/razorpaysoftwareprivatelimited/jobs/4567"));
            assertEquals(new BoardRef(AtsPlatform.LEVER, "fampay"),
                    parsed("https://jobs.lever.co/fampay/2a1b-c3d4"));
        }

        @Test
        void apiUrlsToo() {
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "groww"),
                    parsed("https://boards-api.greenhouse.io/v1/boards/groww/jobs?content=true"));
            assertEquals(new BoardRef(AtsPlatform.LEVER, "fampay"),
                    parsed("https://api.lever.co/v0/postings/fampay?mode=json"));
            assertEquals(new BoardRef(AtsPlatform.ASHBY, "atlan"),
                    parsed("https://api.ashbyhq.com/posting-api/job-board/atlan"));
            assertEquals(new BoardRef(AtsPlatform.WORKABLE, "epignosis"),
                    parsed("https://apply.workable.com/api/v1/widget/accounts/epignosis"));
            assertEquals(new BoardRef(AtsPlatform.SMARTRECRUITERS, "BoschGroup"),
                    parsed("https://api.smartrecruiters.com/v1/companies/BoschGroup/postings?limit=100"));
        }

        @Test
        void schemeIsOptional() {
            assertEquals(new BoardRef(AtsPlatform.LEVER, "meesho"), parsed("jobs.lever.co/meesho"));
        }

        @Test
        void nonAtsUrlsYieldNothing() {
            assertTrue(AtsUrlParser.parse("https://razorpay.com/jobs/").isEmpty());
            assertTrue(AtsUrlParser.parse("https://careers.google.com").isEmpty());
            assertTrue(AtsUrlParser.parse("Razorpay").isEmpty());
            assertTrue(AtsUrlParser.parse(null).isEmpty());
            assertTrue(AtsUrlParser.parse("   ").isEmpty());
        }

        /**
         * A path segment that belongs to the platform is not a company. Without
         * this, apply.workable.com/... adds a company called "api".
         */
        @Test
        void platformOwnSegmentsAreNeverTokens() {
            assertTrue(AtsUrlParser.parse("https://apply.workable.com/").isEmpty());
            assertTrue(AtsUrlParser.parse("https://boards.greenhouse.io/embed/").isEmpty());
            assertTrue(AtsUrlParser.parse("https://help.workable.com/article/12").isEmpty());
        }
    }

    @Nested
    @DisplayName("sniffing a careers page")
    class Sniffing {

        /**
         * The case that justifies sniffing existing at all: no derivation from
         * the word "Razorpay" reaches this token, and reading it off the page is
         * exactly what CLAUDE.md means by taking it from the real careers URL.
         */
        @Test
        void findsAnUnguessableTokenInRealMarkup() {
            String html = FixtureSupport.fixture("careers-razorpay-excerpt.html");
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "razorpaysoftwareprivatelimited"),
                    AtsUrlParser.findAll(html).get(0));
        }

        @Test
        void findsARegionallyHostedBoardInRealMarkup() {
            String html = FixtureSupport.fixture("careers-groww.html");
            assertEquals(new BoardRef(AtsPlatform.GREENHOUSE, "groww"),
                    AtsUrlParser.findAll(html).get(0));
        }

        /**
         * Atlan renders its board in JavaScript; the only trace in the server
         * HTML is a CSP allow-list entry. No token to be had — but knowing it is
         * Ashby turns the probe that follows from five platforms into one.
         */
        @Test
        void aPageCanNameThePlatformWithoutNamingTheBoard() {
            String html = FixtureSupport.fixture("careers-atlan-excerpt.html");
            assertTrue(AtsUrlParser.findAll(html).isEmpty(),
                    "a CSP wildcard is not a board token");
            assertEquals(Optional.of(AtsPlatform.ASHBY), AtsUrlParser.platformHint(html));
        }

        /** A page links its own board repeatedly; a stray mention appears once. */
        @Test
        void ranksTheMostReferencedBoardFirst() {
            String html = """
                    <a href="https://jobs.lever.co/someoneelse">a partner</a>
                    <a href="https://jobs.lever.co/meesho">Openings</a>
                    <a href="https://jobs.lever.co/meesho">See all roles</a>
                    """;
            List<BoardRef> found = AtsUrlParser.findAll(html);
            assertEquals("meesho", found.get(0).token());
            assertEquals(2, found.size());
        }

        @Test
        void platformHintIsEmptyWhenNothingIsMentioned() {
            assertTrue(AtsUrlParser.platformHint("<html><body>We are hiring!</body></html>").isEmpty());
        }
    }

    @Nested
    @DisplayName("next-wave platforms (recognised, not yet watchable)")
    class NextWave {

        @Test
        void workdayPublicSiteWithAndWithoutLocale() {
            BoardRef expected = new BoardRef(AtsPlatform.WORKDAY, "salesforce/wd12/External_Career_Site");
            assertEquals(expected,
                    parsed("https://salesforce.wd12.myworkdayjobs.com/en-US/External_Career_Site"));
            assertEquals(expected,
                    parsed("https://salesforce.wd12.myworkdayjobs.com/External_Career_Site"));
        }

        @Test
        void workdayApiFormAndDeepJobLink() {
            BoardRef expected = new BoardRef(AtsPlatform.WORKDAY, "salesforce/wd12/External_Career_Site");
            assertEquals(expected, parsed(
                    "https://salesforce.wd12.myworkdayjobs.com/wday/cxs/salesforce/External_Career_Site/jobs"));
            assertEquals(expected, parsed("https://salesforce.wd12.myworkdayjobs.com/en-US/"
                    + "External_Career_Site/job/California---San-Francisco/Senior-Engineer_JR123"));
        }

        @Test
        void workdayShardsAndCaseNormalisation() {
            assertEquals(new BoardRef(AtsPlatform.WORKDAY, "acme/wd1/Careers"),
                    parsed("acme.wd1.myworkdayjobs.com/Careers"));
            assertEquals(new BoardRef(AtsPlatform.WORKDAY, "acme/wd103/Careers"),
                    parsed("https://ACME.WD103.myworkdayjobs.com/Careers"));
        }

        /** A bare locale or a host with no site is not a board. */
        @Test
        void workdayWithoutASiteIsNothing() {
            assertTrue(AtsUrlParser.parse("https://acme.wd5.myworkdayjobs.com/en-US").isEmpty());
            assertTrue(AtsUrlParser.parse("https://acme.wd5.myworkdayjobs.com/").isEmpty());
            assertTrue(AtsUrlParser.parse("https://www.myworkdayjobs.com/").isEmpty());
        }

        @Test
        void rippling() {
            BoardRef expected = new BoardRef(AtsPlatform.RIPPLING, "rippling");
            assertEquals(expected, parsed("https://ats.rippling.com/rippling/jobs"));
            assertEquals(expected, parsed("https://ats.rippling.com/api/v2/board/rippling/jobs"));
            assertEquals(expected,
                    parsed("https://api.rippling.com/platform/api/ats/v1/board/rippling/jobs"));
        }

        @Test
        void bambooHr() {
            assertEquals(new BoardRef(AtsPlatform.BAMBOOHR, "acme"),
                    parsed("https://acme.bamboohr.com/careers"));
            assertEquals(new BoardRef(AtsPlatform.BAMBOOHR, "acme"),
                    parsed("https://ACME.bamboohr.com/careers/42"));
            assertEquals(new BoardRef(AtsPlatform.BAMBOOHR, "acme"),
                    parsed("acme.bamboohr.com/jobs/view.php?id=7"));
        }

        @Test
        void jobviteJazzHrIcimsGusto() {
            assertEquals(new BoardRef(AtsPlatform.JOBVITE, "egnyte"),
                    parsed("https://jobs.jobvite.com/egnyte/job/oAbc123"));
            assertEquals(new BoardRef(AtsPlatform.JAZZHR, "acme"),
                    parsed("https://acme.applytojob.com/apply/xyz/Engineer"));
            assertEquals(new BoardRef(AtsPlatform.ICIMS, "careers-acme"),
                    parsed("https://careers-acme.icims.com/jobs/search?ss=1"));
            assertEquals(new BoardRef(AtsPlatform.GUSTO, "acme"),
                    parsed("https://jobs.gusto.com/boards/acme"));
        }

        /**
         * A host token must be one whole DNS label: these would otherwise hand a
         * fetcher a host of the attacker's choosing, or a fragment of one.
         */
        @Test
        void hostileHostsYieldNothing() {
            assertTrue(AtsUrlParser.parse("https://evil.com#.bamboohr.com/careers").isEmpty());
            assertTrue(AtsUrlParser.parse("https://a.b.bamboohr.com/careers").isEmpty());
            assertTrue(AtsUrlParser.parse("https://www.bamboohr.com/careers").isEmpty());
            // "x@" is userinfo; the host really is y.applytojob.com
            assertEquals("y", parsed("https://x@y.applytojob.com/apply").token());
        }

        /** The payroll products share a domain with the ATS; only the ATS host is a hint. */
        @Test
        void payrollMentionsAreNotHints() {
            assertTrue(AtsUrlParser.platformHint("We run payroll on rippling.com").isEmpty());
            assertTrue(AtsUrlParser.platformHint("Benefits via gusto.com").isEmpty());
            assertTrue(AtsUrlParser.platformHint("HR on www.bamboohr.com").isEmpty());
            assertEquals(Optional.of(AtsPlatform.WORKDAY),
                    AtsUrlParser.platformHint("<link href=\"https://acme.wd5.myworkdayjobs.com\">"));
        }
    }

    @Nested
    @DisplayName("board URLs shown on the confirmation card")
    class BoardUrls {

        @Test
        void oneKnownPublicUrlPerPlatform() {
            assertEquals("https://job-boards.greenhouse.io/groww",
                    AtsUrlParser.boardUrl(AtsPlatform.GREENHOUSE, "groww"));
            assertEquals("https://jobs.lever.co/fampay",
                    AtsUrlParser.boardUrl(AtsPlatform.LEVER, "fampay"));
            assertEquals("https://jobs.ashbyhq.com/atlan",
                    AtsUrlParser.boardUrl(AtsPlatform.ASHBY, "atlan"));
            assertEquals("https://apply.workable.com/apna",
                    AtsUrlParser.boardUrl(AtsPlatform.WORKABLE, "apna"));
            assertEquals("https://jobs.smartrecruiters.com/PHONEPELIMITED",
                    AtsUrlParser.boardUrl(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMITED"));
            assertEquals("https://salesforce.wd12.myworkdayjobs.com/External_Career_Site",
                    AtsUrlParser.boardUrl(AtsPlatform.WORKDAY, "salesforce/wd12/External_Career_Site"));
            assertEquals("https://ats.rippling.com/rippling",
                    AtsUrlParser.boardUrl(AtsPlatform.RIPPLING, "rippling"));
            assertEquals("https://acme.bamboohr.com/careers",
                    AtsUrlParser.boardUrl(AtsPlatform.BAMBOOHR, "acme"));
            assertEquals("https://jobs.jobvite.com/egnyte",
                    AtsUrlParser.boardUrl(AtsPlatform.JOBVITE, "egnyte"));
            assertEquals("https://acme.applytojob.com/apply",
                    AtsUrlParser.boardUrl(AtsPlatform.JAZZHR, "acme"));
            assertEquals("https://careers-acme.icims.com/jobs",
                    AtsUrlParser.boardUrl(AtsPlatform.ICIMS, "careers-acme"));
            assertEquals("https://jobs.gusto.com/boards/acme",
                    AtsUrlParser.boardUrl(AtsPlatform.GUSTO, "acme"));
        }

        /** Round-trips: every URL this produces must parse back to what made it. */
        @Test
        void areThemselvesParseable() {
            for (AtsPlatform platform : AtsPlatform.values()) {
                if (platform == AtsPlatform.UNSUPPORTED) {
                    continue;
                }
                String token = platform == AtsPlatform.WORKDAY ? "acme/wd5/External" : "acme";
                String url = AtsUrlParser.boardUrl(platform, token);
                assertEquals(new BoardRef(platform, token), parsed(url), url);
            }
        }

        @Test
        void aWorkdayTokenThatDoesNotParseHasNoBoardUrl() {
            assertNull(AtsUrlParser.boardUrl(AtsPlatform.WORKDAY, "evil.com#/wd5/Site"));
        }

        @Test
        void unsupportedHasNoPublicBoard() {
            assertNull(AtsUrlParser.boardUrl(AtsPlatform.UNSUPPORTED, "whatever"));
        }
    }
}
