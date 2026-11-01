package com.jobx.resolve;

import com.jobx.entity.Company;
import com.jobx.enums.AtsPlatform;
import com.jobx.fetcher.AtsFetchException;
import com.jobx.fetcher.AtsFetcher;
import com.jobx.fetcher.BoardPreview;
import com.jobx.fetcher.FetcherRegistry;
import com.jobx.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * PR D: a board that has been failing for a while gets a look at where the
 * company went, and anything found is stored as a proposal only.
 */
class BoardRedetectorTest {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final String CAREERS = "https://phonepe.com/careers";

    private CompanyRepository companyRepository;
    private FetcherRegistry fetcherRegistry;
    private SafeUrlFetcher safeUrlFetcher;
    private CareersLinkFollower follower;
    private BoardProbe boardProbe;
    private AtsFetcher greenhouse;
    private AtsFetcher lever;
    private AtsFetcher smartRecruiters;
    private BoardRedetector redetector;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        fetcherRegistry = mock(FetcherRegistry.class);
        safeUrlFetcher = mock(SafeUrlFetcher.class);
        follower = mock(CareersLinkFollower.class);
        boardProbe = mock(BoardProbe.class);
        greenhouse = mock(AtsFetcher.class);
        lever = mock(AtsFetcher.class);
        smartRecruiters = mock(AtsFetcher.class);

        when(fetcherRegistry.getFetcher(any())).thenReturn(Optional.empty());
        when(fetcherRegistry.getFetcher(AtsPlatform.GREENHOUSE)).thenReturn(Optional.of(greenhouse));
        when(fetcherRegistry.getFetcher(AtsPlatform.LEVER)).thenReturn(Optional.of(lever));
        when(fetcherRegistry.getFetcher(AtsPlatform.SMARTRECRUITERS)).thenReturn(Optional.of(smartRecruiters));
        when(follower.follow(any())).thenReturn(List.of());
        when(boardProbe.probe(anyList(), anyList())).thenReturn(List.of());
        when(companyRepository.findById(any())).thenReturn(Optional.empty());

        redetector = new BoardRedetector(companyRepository, fetcherRegistry, safeUrlFetcher,
                follower, boardProbe, Clock.fixed(NOW, ZoneOffset.UTC), 12, 24, 5, 4, 3);
    }

    private static Company failing(String name, String token, Duration failingFor) {
        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setDisplayName(name);
        company.setAtsPlatform(AtsPlatform.GREENHOUSE);
        company.setBoardToken(token);
        company.setLastFetchStatus(Company.FetchStatus.FAILED);
        company.setFailingSince(NOW.minus(failingFor));
        return company;
    }

    private void careersPage(String html) {
        when(safeUrlFetcher.fetchPage(CAREERS))
                .thenReturn(Optional.of(new SafeUrlFetcher.FetchedPage(URI.create(CAREERS), html)));
    }

    private static BoardPreview live(int count) {
        return new BoardPreview(null, count, List.of());
    }

    // ---- what gets proposed ----

    @Test
    void careersPageNowLinkingLeverProposesLeverAndSkipsTheCurrentBoard() {
        Company phonepe = failing("PhonePe", "phonepe", Duration.ofHours(13));
        phonepe.setCareersUrl(CAREERS);
        careersPage("<a href=\"https://job-boards.greenhouse.io/phonepe\">old</a>"
                + "<a href=\"https://jobs.lever.co/phonepe\">Open roles</a>");
        when(lever.previewBoard(any())).thenReturn(live(6));

        redetector.redetectDue(List.of(phonepe));

        assertEquals(AtsPlatform.LEVER, phonepe.getMovedToPlatform());
        assertEquals("phonepe", phonepe.getMovedToToken());
        assertEquals(Company.MoveReason.FAILING, phonepe.getMovedToReason());
        assertEquals(NOW, phonepe.getLastRedetectAt());
        verify(greenhouse, never()).previewBoard(any());
        verify(companyRepository).save(phonepe);
        // Sniff found it, so no guessing was needed
        verifyNoInteractions(boardProbe);
    }

    @Test
    void zeroRoleCandidateIsNotProposed() {
        Company phonepe = failing("PhonePe", "phonepe", Duration.ofHours(13));
        phonepe.setCareersUrl(CAREERS);
        careersPage("<a href=\"https://jobs.lever.co/phonepe\">Open roles</a>");
        // Lever 200s an empty list: a guess about where a company went needs live roles
        when(lever.previewBoard(any())).thenReturn(live(0));

        redetector.redetectDue(List.of(phonepe));

        assertFalse(phonepe.hasMoveProposal());
        assertEquals(NOW, phonepe.getLastRedetectAt()); // still counts as a look
    }

    @Test
    void previewFailureIsNotACandidate() {
        Company phonepe = failing("PhonePe", "phonepe", Duration.ofHours(13));
        phonepe.setCareersUrl(CAREERS);
        careersPage("<a href=\"https://jobs.lever.co/phonepe\">Open roles</a>");
        when(lever.previewBoard(any())).thenThrow(new AtsFetchException("404"));

        redetector.redetectDue(List.of(phonepe));

        assertFalse(phonepe.hasMoveProposal());
    }

    @Test
    void followsOneHopWhenTheCareersPageLinksNoNewBoard() {
        Company phonepe = failing("PhonePe", "phonepe", Duration.ofHours(13));
        phonepe.setCareersUrl(CAREERS);
        careersPage("<a href=\"/jobs\">Open roles</a>");
        when(follower.follow(any())).thenReturn(List.of(new SafeUrlFetcher.FetchedPage(
                URI.create("https://phonepe.com/jobs"),
                "<a href=\"https://jobs.smartrecruiters.com/PHONEPELIMITED\">jobs</a>")));
        when(smartRecruiters.previewBoard(any())).thenReturn(live(6));

        redetector.redetectDue(List.of(phonepe));

        assertEquals(AtsPlatform.SMARTRECRUITERS, phonepe.getMovedToPlatform());
        assertEquals("PHONEPELIMITED", phonepe.getMovedToToken());
    }

    @Test
    void withoutACareersPageProbesEveryOtherPlatformByName() {
        Company phonepe = failing("PhonePe", "phonepe", Duration.ofHours(13));
        BoardRef found = new BoardRef(AtsPlatform.LEVER, "phonepe");
        when(boardProbe.probe(anyList(), anyList()))
                .thenReturn(List.of(new BoardProbe.Hit(found, live(4))));

        redetector.redetectDue(List.of(phonepe));

        assertEquals(AtsPlatform.LEVER, phonepe.getMovedToPlatform());
        verifyNoInteractions(safeUrlFetcher);
        // The failing platform itself is never probed: the same slug there is the dead board
        verify(boardProbe).probe(
                argThat(platforms -> !platforms.contains(AtsPlatform.GREENHOUSE)
                        && platforms.contains(AtsPlatform.LEVER)),
                argThat(tokens -> tokens.contains("phonepe")));
    }

    @Test
    void probeFallsBackWhenTheCareersPageYieldsNothing() {
        Company phonepe = failing("PhonePe", "phonepe", Duration.ofHours(13));
        phonepe.setCareersUrl(CAREERS);
        careersPage("<p>We're hiring soon</p>");
        when(boardProbe.probe(anyList(), anyList())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.LEVER, "phonepe"), live(2))));

        redetector.redetectDue(List.of(phonepe));

        assertEquals(AtsPlatform.LEVER, phonepe.getMovedToPlatform());
    }

    @Test
    void sameBoardInOtherCasingIsNotAMove() {
        Company sprinto = failing("Sprinto", "Sprinto", Duration.ofHours(13));
        sprinto.setCareersUrl(CAREERS);
        careersPage("<a href=\"https://job-boards.greenhouse.io/sprinto\">jobs</a>");

        redetector.redetectDue(List.of(sprinto));

        assertFalse(sprinto.hasMoveProposal());
        verify(greenhouse, never()).previewBoard(any());
    }

    @Test
    void unsafeStoredCareersUrlIsSkippedNotFatal() {
        Company phonepe = failing("PhonePe", "phonepe", Duration.ofHours(13));
        phonepe.setCareersUrl("http://127.0.0.1/careers");
        when(safeUrlFetcher.fetchPage(any()))
                .thenThrow(new SafeUrlFetcher.UnsafeUrlException("not public"));

        redetector.redetectDue(List.of(phonepe));

        assertEquals(NOW, phonepe.getLastRedetectAt());
        verify(boardProbe).probe(anyList(), anyList());
    }

    @Test
    void findingNothingClearsAStaleProposal() {
        Company phonepe = failing("PhonePe", "phonepe", Duration.ofDays(3));
        phonepe.setLastRedetectAt(NOW.minus(Duration.ofHours(25)));
        phonepe.proposeMove(AtsPlatform.LEVER, "phonepe", Company.MoveReason.FAILING);

        redetector.redetectDue(List.of(phonepe));

        assertFalse(phonepe.hasMoveProposal());
    }

    @Test
    void writesToAFreshCopyOfTheRow() {
        // The cycle's instance is minutes old; saving it whole could undo a
        // "Check now" that ran meanwhile. The redetector re-reads and saves that.
        Company stale = failing("PhonePe", "phonepe", Duration.ofHours(13));
        Company fresh = failing("PhonePe", "phonepe", Duration.ofHours(13));
        fresh.setId(stale.getId());
        fresh.setLastFetchError("fresh error");
        when(companyRepository.findById(stale.getId())).thenReturn(Optional.of(fresh));
        when(boardProbe.probe(anyList(), anyList())).thenReturn(List.of(
                new BoardProbe.Hit(new BoardRef(AtsPlatform.LEVER, "phonepe"), live(2))));

        redetector.redetectDue(List.of(stale));

        ArgumentCaptor<Company> saved = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(saved.capture());
        assertSame(fresh, saved.getValue());
        assertEquals("fresh error", saved.getValue().getLastFetchError());
        assertEquals(AtsPlatform.LEVER, fresh.getMovedToPlatform());
        assertEquals(AtsPlatform.LEVER, stale.getMovedToPlatform()); // caller kept in step
    }

    // ---- gates and the cap ----

    @Test
    void gates() {
        Company healthy = failing("A", "a", Duration.ofDays(2));
        healthy.setLastFetchStatus(Company.FetchStatus.SUCCESS);
        Company noStreak = failing("B", "b", Duration.ZERO);
        noStreak.setFailingSince(null);
        Company tooRecent = failing("C", "c", Duration.ofHours(11));
        Company lookedAtYesterday = failing("D", "d", Duration.ofDays(2));
        lookedAtYesterday.setLastRedetectAt(NOW.minus(Duration.ofHours(23)));
        Company due = failing("E", "e", Duration.ofHours(13));
        Company dueAgain = failing("F", "f", Duration.ofDays(2));
        dueAgain.setLastRedetectAt(NOW.minus(Duration.ofHours(25)));

        assertFalse(redetector.isDue(healthy, NOW));
        assertFalse(redetector.isDue(noStreak, NOW));
        assertFalse(redetector.isDue(tooRecent, NOW));
        assertFalse(redetector.isDue(lookedAtYesterday, NOW));
        assertTrue(redetector.isDue(due, NOW));
        assertTrue(redetector.isDue(dueAgain, NOW));

        assertEquals(2, redetector.redetectDue(
                List.of(healthy, noStreak, tooRecent, lookedAtYesterday, due, dueAgain)));
    }

    @Test
    void capsCompaniesPerCycleLongestUncheckedFirst() {
        List<Company> companies = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Company company = failing("Co" + i, "co" + i, Duration.ofDays(3));
            // Co0 was checked most recently ... Co5 longest ago; Co6 never
            if (i < 6) {
                company.setLastRedetectAt(NOW.minus(Duration.ofHours(25 + i)));
            }
            companies.add(company);
        }

        assertEquals(5, redetector.redetectDue(companies));

        // The two most recently checked wait for the next cycle
        assertEquals(NOW.minus(Duration.ofHours(25)), companies.get(0).getLastRedetectAt());
        assertEquals(NOW.minus(Duration.ofHours(26)), companies.get(1).getLastRedetectAt());
        for (int i = 2; i < 7; i++) {
            assertEquals(NOW, companies.get(i).getLastRedetectAt(), "Co" + i);
        }
    }

    @Test
    void oneCompanyBlowingUpDoesNotStopTheRest() {
        Company first = failing("First", "first", Duration.ofHours(13));
        Company second = failing("Second", "second", Duration.ofHours(13));
        second.setLastRedetectAt(NOW.minus(Duration.ofDays(2)));
        when(companyRepository.save(first)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> redetector.redetectDue(List.of(first, second)));
        verify(companyRepository).save(second);
    }
}
