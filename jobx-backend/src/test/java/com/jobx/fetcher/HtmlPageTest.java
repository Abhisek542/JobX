package com.jobx.fetcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HtmlPageTest {

    @Test
    void aBoardPageBodyComesBackOnlyFromA2xx() {
        assertEquals("<html/>", new HtmlPage(200, null, "<html/>").requireBoard("Jobvite", "egnyte"));
    }

    @Test
    void aRedirectOrA404MeansTheBoardDoesNotExist() {
        AtsFetchException redirect = assertThrows(AtsFetchException.class,
                () -> new HtmlPage(302, "https://info.jazzhr.com/job-seekers.html", "").requireBoard("JazzHR", "nope"));
        assertEquals("JazzHR board 'nope' does not exist (redirected to https://info.jazzhr.com/job-seekers.html)",
                redirect.getMessage());

        AtsFetchException missing = assertThrows(AtsFetchException.class,
                () -> new HtmlPage(404, null, "<title>404</title>").requireBoard("Gusto", "nope"));
        assertEquals("Gusto board 'nope' does not exist (HTTP 404)", missing.getMessage());
    }

    @Test
    void anyOtherErrorStatusIsAFailedFetchNotAMissingBoard() {
        AtsFetchException e = assertThrows(AtsFetchException.class,
                () -> new HtmlPage(500, null, "").requireBoard("Gusto", "sage"));
        assertEquals("Gusto board 'sage' answered HTTP 500", e.getMessage());
    }

    @Test
    void aDetailPageMustBe2xx() {
        assertEquals("body", new HtmlPage(200, null, "body").requireOk("Jobvite job abc"));

        // A posting closed between list and detail: Jobvite answers 303.
        AtsFetchException e = assertThrows(AtsFetchException.class,
                () -> new HtmlPage(303, "https://jobs.jobvite.com/careers/egnyte/jobs?error=404", "")
                        .requireOk("Jobvite job abc"));
        assertEquals("Jobvite job abc answered HTTP 303", e.getMessage());
    }

    @Test
    void cardsPassThrough() {
        List<String> cards = List.of("a", "b");
        assertSame(cards, HtmlPage.requireCardsOrEmptyMarker(cards, false, "JazzHR", "acme"));
    }

    @Test
    void noCardsIsAnEmptyBoardOnlyWithThePlatformsMarker() {
        assertTrue(HtmlPage.requireCardsOrEmptyMarker(List.of(), true, "JazzHR", "acme").isEmpty());

        // The shape of a changed layout: a 200 page the selectors no longer match.
        AtsFetchException e = assertThrows(AtsFetchException.class,
                () -> HtmlPage.requireCardsOrEmptyMarker(List.of(), false, "JazzHR", "acme"));
        assertTrue(e.getMessage().contains("no empty-board marker"), e.getMessage());
    }
}
