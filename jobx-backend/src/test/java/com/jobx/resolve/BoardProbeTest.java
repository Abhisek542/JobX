package com.jobx.resolve;

import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetcherRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Probing turns a slug guess into a candidate — or refuses to.
 *
 * The rule under test is the one that makes the whole guess-then-confirm design
 * safe: a board counts only if it has live roles. Two of the five platforms
 * answer a token that was never theirs with a cheerful 200, and Workable also
 * echoes back a plausible company name, so anything trusting names or status
 * codes would hand a user a board that never produces a job.
 */
class BoardProbeTest {

    private FetcherRegistry registry;
    private BoardProbe probe;

    @BeforeEach
    void setUp() {
        registry = mock(FetcherRegistry.class);
        when(registry.getFetcher(any())).thenReturn(Optional.empty());
        probe = new BoardProbe(registry, 4, 5000, 60_000);
    }

    /** Registers a fetcher whose previewBoard answers however the test needs. */
    private AtsFetcher stub(AtsPlatform platform) {
        AtsFetcher fetcher = mock(AtsFetcher.class);
        when(fetcher.supports()).thenReturn(platform);
        when(registry.getFetcher(platform)).thenReturn(Optional.of(fetcher));
        return fetcher;
    }

    private static BoardPreview preview(String name, int count, String... titles) {
        return new BoardPreview(name, count, List.of(titles));
    }

    /**
     * THE case this class exists for. Verified live:
     * apply.workable.com/api/v1/widget/accounts/razorpay returns
     * {"name":"Razorpay","description":null,"jobs":[]} — Razorpay is not a
     * Workable customer. The name is a lie; the empty board is the truth.
     */
    @Test
    void aGhostAccountThatEchoesTheCompanyNameIsNotABoard() {
        AtsFetcher workable = stub(AtsPlatform.WORKABLE);
        when(workable.previewBoard(any())).thenReturn(preview("Razorpay", 0));

        assertTrue(probe.probe(List.of(AtsPlatform.WORKABLE), List.of("razorpay")).isEmpty(),
                "a board with no live roles must never be offered, however convincing its name");
    }

    @Test
    void aBoardWithLiveRolesIsAHit() {
        AtsFetcher ashby = stub(AtsPlatform.ASHBY);
        when(ashby.previewBoard(any())).thenReturn(preview(null, 5, "Senior Security Engineer"));

        List<BoardProbe.Hit> hits = probe.probe(List.of(AtsPlatform.ASHBY), List.of("atlan"));

        assertEquals(1, hits.size());
        assertEquals(new BoardRef(AtsPlatform.ASHBY, "atlan"), hits.get(0).ref());
        assertEquals(5, hits.get(0).preview().jobCount());
        assertEquals(List.of("Senior Security Engineer"), hits.get(0).preview().sampleTitles());
    }

    /** The ordinary outcome — four of five platforms 404 an unknown token. */
    @Test
    void aFailedProbeIsAnAnswerNotAnError() {
        AtsFetcher lever = stub(AtsPlatform.LEVER);
        when(lever.previewBoard(any())).thenThrow(new AtsFetchException("404"));

        assertDoesNotThrow(() -> probe.probe(List.of(AtsPlatform.LEVER), List.of("nope")));
        assertTrue(probe.probe(List.of(AtsPlatform.LEVER), List.of("nope")).isEmpty());
    }

    @Test
    void probesEveryPlatformForEveryToken() {
        AtsFetcher lever = stub(AtsPlatform.LEVER);
        when(lever.previewBoard(any())).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            // Only the capitalised spelling exists — the Sprinto case.
            return "Sprinto".equals(company.getBoardToken())
                    ? preview(null, 35, "Backend Engineer")
                    : preview(null, 0);
        });

        List<BoardProbe.Hit> hits = probe.probe(
                List.of(AtsPlatform.LEVER), List.of("sprinto", "Sprinto"));

        assertEquals(1, hits.size());
        assertEquals("Sprinto", hits.get(0).ref().token());
    }

    /** Busiest board first — a tie-break for display, not a claim of correctness. */
    @Test
    void ranksHitsByJobCount() {
        AtsFetcher greenhouse = stub(AtsPlatform.GREENHOUSE);
        when(greenhouse.previewBoard(any())).thenReturn(preview(null, 4, "Analyst"));
        AtsFetcher lever = stub(AtsPlatform.LEVER);
        when(lever.previewBoard(any())).thenReturn(preview(null, 49, "Engineer"));

        List<BoardProbe.Hit> hits = probe.probe(
                List.of(AtsPlatform.GREENHOUSE, AtsPlatform.LEVER), List.of("meesho"));

        assertEquals(2, hits.size());
        assertEquals(AtsPlatform.LEVER, hits.get(0).ref().platform());
    }

    /**
     * A user refining a search retypes the same near-miss; there is no reason to
     * spend an ATS vendor's rate limit answering it twice.
     */
    @Test
    void remembersRecentMissesInsteadOfReprobing() {
        AtsFetcher lever = stub(AtsPlatform.LEVER);
        when(lever.previewBoard(any())).thenThrow(new AtsFetchException("404"));

        probe.probe(List.of(AtsPlatform.LEVER), List.of("nope"));
        probe.probe(List.of(AtsPlatform.LEVER), List.of("nope"));
        probe.probe(List.of(AtsPlatform.LEVER), List.of("nope"));

        verify(lever, times(1)).previewBoard(any());
    }

    /** Hits are never cached: a job count shown to a user must be current. */
    @Test
    void doesNotCacheHits() {
        AtsFetcher ashby = stub(AtsPlatform.ASHBY);
        when(ashby.previewBoard(any())).thenReturn(preview(null, 5, "Engineer"));

        probe.probe(List.of(AtsPlatform.ASHBY), List.of("atlan"));
        probe.probe(List.of(AtsPlatform.ASHBY), List.of("atlan"));

        verify(ashby, times(2)).previewBoard(any());
    }

    @Test
    void platformsWithNoFetcherAreSkipped() {
        assertTrue(probe.probe(List.of(AtsPlatform.UNSUPPORTED), List.of("anything")).isEmpty());
    }

    @Test
    void noTokensMeansNoRequests() {
        AtsFetcher lever = stub(AtsPlatform.LEVER);
        assertTrue(probe.probe(List.of(AtsPlatform.LEVER), List.of()).isEmpty());
        verify(lever, never()).previewBoard(any());
    }

    /**
     * Ashby serves Atlan's board at both "atlan" and "Atlan" (verified live), so
     * probing both spellings finds the same board twice. Offering it twice asks
     * the user to choose between two things that are not different.
     */
    @Test
    void collapsesSpellingsOfTheSameBoardIntoOneCandidate() {
        AtsFetcher ashby = stub(AtsPlatform.ASHBY);
        when(ashby.previewBoard(any())).thenReturn(preview(null, 6, "Field Security Engineer"));

        List<BoardProbe.Hit> hits = probe.probe(
                List.of(AtsPlatform.ASHBY), List.of("atlan", "Atlan"));

        assertEquals(1, hits.size());
        assertEquals("atlan", hits.get(0).ref().token());
    }

    /** Different platforms are different boards, even under the same token. */
    @Test
    void doesNotCollapseAcrossPlatforms() {
        AtsFetcher greenhouse = stub(AtsPlatform.GREENHOUSE);
        when(greenhouse.previewBoard(any())).thenReturn(preview(null, 4, "Analyst"));
        AtsFetcher lever = stub(AtsPlatform.LEVER);
        when(lever.previewBoard(any())).thenReturn(preview(null, 49, "Engineer"));

        assertEquals(2, probe.probe(
                List.of(AtsPlatform.GREENHOUSE, AtsPlatform.LEVER), List.of("meesho")).size());
    }
}
