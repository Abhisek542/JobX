package com.jobx.fetcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.ashby.AshbyFetcher;
import com.jobx.fetcher.bamboohr.BambooHrFetcher;
import com.jobx.fetcher.greenhouse.GreenhouseFetcher;
import com.jobx.fetcher.gusto.GustoFetcher;
import com.jobx.fetcher.jazzhr.JazzHrFetcher;
import com.jobx.fetcher.jobvite.JobviteFetcher;
import com.jobx.fetcher.lever.LeverFetcher;
import com.jobx.fetcher.rippling.RipplingFetcher;
import com.jobx.fetcher.smartrecruiters.SmartRecruitersFetcher;
import com.jobx.fetcher.workable.WorkableFetcher;
import com.jobx.fetcher.workday.WorkdayFetcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR B: which platforms may have an empty board watched.
 *
 * The zero-roles rejection exists because Workable and SmartRecruiters answer a
 * bogus token with an empty 200. Greenhouse, Lever and Ashby 404 it instead
 * (checked live 2026-10-31), so on those an empty preview proves the board is
 * real, and validateBoard accepts it.
 */
class ValidateBoardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BoardPreview EMPTY = new BoardPreview(null, 0, List.of());
    private static final BoardPreview LIVE = new BoardPreview(null, 3, List.of("Backend Engineer"));

    private static Company board(AtsPlatform platform, String token) {
        Company company = new Company();
        company.setAtsPlatform(platform);
        company.setBoardToken(token);
        company.setDisplayName(token);
        return company;
    }

    // Each fetcher with previewBoard stubbed, so validateBoard runs the real
    // inherited logic against the real distinguishesMissingBoard() answer.

    private static AtsFetcher greenhouse(BoardPreview preview) {
        return new GreenhouseFetcher(null, MAPPER) {
            @Override public BoardPreview previewBoard(Company company) { return preview; }
        };
    }

    private static AtsFetcher lever(BoardPreview preview) {
        return new LeverFetcher(null, MAPPER) {
            @Override public BoardPreview previewBoard(Company company) { return preview; }
        };
    }

    private static AtsFetcher ashby(BoardPreview preview) {
        return new AshbyFetcher(null, MAPPER) {
            @Override public BoardPreview previewBoard(Company company) { return preview; }
        };
    }

    private static AtsFetcher workable(BoardPreview preview) {
        return new WorkableFetcher(null, MAPPER) {
            @Override public BoardPreview previewBoard(Company company) { return preview; }
        };
    }

    private static AtsFetcher smartRecruiters(BoardPreview preview) {
        return new SmartRecruitersFetcher(null, MAPPER) {
            @Override public BoardPreview previewBoard(Company company) { return preview; }
        };
    }

    @Test
    void anEmptyGreenhouseBoardIsAccepted() {
        assertDoesNotThrow(() -> greenhouse(EMPTY).validateBoard(board(AtsPlatform.GREENHOUSE, "quietco")));
    }

    @Test
    void anEmptyLeverBoardIsAccepted() {
        assertDoesNotThrow(() -> lever(EMPTY).validateBoard(board(AtsPlatform.LEVER, "quietco")));
    }

    @Test
    void anEmptyAshbyBoardIsAccepted() {
        assertDoesNotThrow(() -> ashby(EMPTY).validateBoard(board(AtsPlatform.ASHBY, "quietco")));
    }

    /** The ghost-account case: Razorpay is not a Workable customer. */
    @Test
    void anEmptyWorkableBoardIsStillRejected() {
        AtsFetchException e = assertThrows(AtsFetchException.class,
                () -> workable(new BoardPreview("Razorpay", 0, List.of()))
                        .validateBoard(board(AtsPlatform.WORKABLE, "razorpay")));
        assertTrue(e.getMessage().contains("razorpay"));
    }

    @Test
    void anEmptySmartRecruitersBoardIsStillRejected() {
        assertThrows(AtsFetchException.class,
                () -> smartRecruiters(EMPTY).validateBoard(board(AtsPlatform.SMARTRECRUITERS, "PHONEPELIMTED")));
    }

    @Test
    void aBoardWithLiveRolesIsAcceptedEverywhere() {
        for (AtsFetcher fetcher : List.of(greenhouse(LIVE), lever(LIVE), ashby(LIVE),
                workable(LIVE), smartRecruiters(LIVE))) {
            assertDoesNotThrow(() -> fetcher.validateBoard(board(fetcher.supports(), "acme")),
                    fetcher.supports().name());
        }
    }

    /**
     * A board that can't be read at all is still a rejection on the lenient
     * platforms. That is the 404 the leniency relies on.
     */
    @Test
    void anUnreadableBoardIsRejectedEvenOnALenientPlatform() {
        AtsFetcher greenhouse = new GreenhouseFetcher(null, MAPPER) {
            @Override public BoardPreview previewBoard(Company company) {
                throw new AtsFetchException("404");
            }
        };
        assertThrows(AtsFetchException.class,
                () -> greenhouse.validateBoard(board(AtsPlatform.GREENHOUSE, "nope")));
    }

    /**
     * Only the three recon-checked platforms opt in. Any other fetcher that opts
     * in must first pass the same recon check, and then this list is updated.
     */
    @Test
    void onlyGreenhouseLeverAndAshbyDistinguishMissingBoards() {
        List<AtsFetcher> all = List.of(
                new GreenhouseFetcher(null, MAPPER),
                new LeverFetcher(null, MAPPER),
                new AshbyFetcher(null, MAPPER),
                new WorkableFetcher(null, MAPPER),
                new SmartRecruitersFetcher(null, MAPPER),
                new RipplingFetcher(null, MAPPER),
                new BambooHrFetcher(null, MAPPER),
                new JobviteFetcher(null, MAPPER, 20),
                new JazzHrFetcher(null, MAPPER),
                new GustoFetcher(null, MAPPER),
                new WorkdayFetcher(null, MAPPER, 60));
        Set<AtsPlatform> lenient = Set.of(AtsPlatform.GREENHOUSE, AtsPlatform.LEVER, AtsPlatform.ASHBY);

        for (AtsFetcher fetcher : all) {
            assertEquals(lenient.contains(fetcher.supports()), fetcher.distinguishesMissingBoard(),
                    fetcher.supports().name());
        }
    }
}
