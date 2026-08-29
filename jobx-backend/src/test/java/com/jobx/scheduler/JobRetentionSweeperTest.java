package com.jobx.scheduler;

import com.jobx.entity.Company;
import com.jobx.entity.ExpiredJob;
import com.jobx.entity.Job;
import com.jobx.entity.Match;
import com.jobx.enums.AtsPlatform;
import com.jobx.repository.ExpiredJobRepository;
import com.jobx.repository.JobRepository;
import com.jobx.repository.MatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * The six-day TTL sweep.
 *
 * The behaviour worth pinning down here is not "old jobs get deleted" — it is
 * the two things that make deleting them safe: a tombstone is written so the
 * next fetch cycle cannot resurrect a posting still live on the board, and the
 * user's own SEEN/APPLIED history survives the delete.
 */
class JobRetentionSweeperTest {

    private JobRepository jobRepository;
    private MatchRepository matchRepository;
    private ExpiredJobRepository expiredJobRepository;
    private JobRetentionSweeper sweeper;

    private Company company;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jobRepository = mock(JobRepository.class);
        matchRepository = mock(MatchRepository.class);
        expiredJobRepository = mock(ExpiredJobRepository.class);
        ObjectProvider<JobRetentionSweeper> self = mock(ObjectProvider.class);

        sweeper = new JobRetentionSweeper(jobRepository, matchRepository,
                expiredJobRepository, self, 6);
        when(self.getObject()).thenReturn(sweeper);

        company = new Company();
        company.setId(UUID.randomUUID());
        company.setDisplayName("Razorpay");
        company.setAtsPlatform(AtsPlatform.GREENHOUSE);
        company.setBoardToken("razorpaysoftwareprivatelimited");
    }

    private Job job(String externalId, Instant postedAt, Instant firstSeenAt) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setCompany(company);
        job.setExternalId(externalId);
        job.setAtsPlatform(AtsPlatform.GREENHOUSE);
        job.setTitle("Backend Engineer");
        job.setApplyUrl("https://example.com/" + externalId);
        job.setPlatformPostedAt(postedAt);
        job.setFirstSeenAt(firstSeenAt);
        return job;
    }

    @Test
    void nothingOlderThanTheWindowMeansNoWork() {
        when(jobRepository.findExpiredAsOf(any())).thenReturn(List.of());

        sweeper.sweepExpiredJobs();

        verify(jobRepository, never()).deleteAllInBatch(anyCollection());
        verify(expiredJobRepository, never()).saveAll(any());
        verifyNoInteractions(matchRepository);
    }

    @Test
    void theCutoffIsTtlDaysBeforeNow() {
        when(jobRepository.findExpiredAsOf(any())).thenReturn(List.of());
        Instant before = Instant.now();

        sweeper.sweepExpiredJobs();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(jobRepository).findExpiredAsOf(cutoff.capture());

        // Six days back, give or take the time the call took.
        Duration age = Duration.between(cutoff.getValue(), before);
        assertTrue(age.toHours() >= 6 * 24 - 1 && age.toHours() <= 6 * 24 + 1,
                "expected a ~6-day cutoff, got an age of " + age);
    }

    @Test
    void expiredJobIsTombstonedThenDeleted() {
        Job stale = job("77", Instant.now().minus(Duration.ofDays(9)), Instant.now().minus(Duration.ofDays(8)));
        when(jobRepository.findExpiredAsOf(any())).thenReturn(List.of(stale));

        sweeper.sweepExpiredJobs();

        // The tombstone must carry the dedup key, and nothing heavy.
        ArgumentCaptor<List<ExpiredJob>> saved = ArgumentCaptor.forClass(List.class);
        verify(expiredJobRepository).saveAll(saved.capture());
        assertEquals(1, saved.getValue().size());
        ExpiredJob tombstone = saved.getValue().get(0);
        assertEquals("77", tombstone.getExternalId());
        assertSame(company, tombstone.getCompany());
        assertEquals(stale.getPlatformPostedAt(), tombstone.getPostedAt());

        verify(jobRepository).deleteAllInBatch(List.of(stale));
    }

    @Test
    void tombstoneIsWrittenBeforeTheJobIsDeleted() {
        Job stale = job("77", Instant.now().minus(Duration.ofDays(9)), Instant.now());
        when(jobRepository.findExpiredAsOf(any())).thenReturn(List.of(stale));

        sweeper.sweepExpiredJobs();

        // Order matters: a crash between the two must leave the job present
        // (retried next sweep), never the job gone with no tombstone — that is
        // the state that resurrects the posting on the next fetch.
        InOrder order = inOrder(expiredJobRepository, jobRepository);
        order.verify(expiredJobRepository).saveAll(any());
        order.verify(jobRepository).deleteAllInBatch(anyCollection());
    }

    @Test
    void onlyUntouchedMatchesAreDropped() {
        Job stale = job("77", Instant.now().minus(Duration.ofDays(9)), Instant.now());
        when(jobRepository.findExpiredAsOf(any())).thenReturn(List.of(stale));

        sweeper.sweepExpiredJobs();

        ArgumentCaptor<Collection<Match.MatchStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(matchRepository).deleteByJobIdsAndStatuses(eq(List.of(stale.getId())), statuses.capture());

        // SEEN is "the user saved this" and APPLIED is "the user applied" —
        // both are history we promised to keep, so neither may appear here.
        assertTrue(statuses.getValue().containsAll(
                List.of(Match.MatchStatus.NEW, Match.MatchStatus.DISMISSED)));
        assertFalse(statuses.getValue().contains(Match.MatchStatus.SEEN));
        assertFalse(statuses.getValue().contains(Match.MatchStatus.APPLIED));
    }

    @Test
    void survivingMatchesAreStampedBeforeTheJobGoes() {
        Job stale = job("77", Instant.now().minus(Duration.ofDays(9)), Instant.now());
        when(jobRepository.findExpiredAsOf(any())).thenReturn(List.of(stale));

        sweeper.sweepExpiredJobs();

        // The stamp has to happen while job_id still points at the matches;
        // once the job row is gone the pointer is null and they're unreachable.
        InOrder order = inOrder(matchRepository, jobRepository);
        order.verify(matchRepository).markExpiredByJobIds(eq(List.of(stale.getId())), any());
        order.verify(jobRepository).deleteAllInBatch(anyCollection());
    }

    @Test
    void firstSeenAtIsTheClockWhenTheBoardPublishedNoPostingDate() {
        Instant seen = Instant.now().minus(Duration.ofDays(8));
        Job undated = job("88", null, seen);
        when(jobRepository.findExpiredAsOf(any())).thenReturn(List.of(undated));

        sweeper.sweepExpiredJobs();

        ArgumentCaptor<List<ExpiredJob>> saved = ArgumentCaptor.forClass(List.class);
        verify(expiredJobRepository).saveAll(saved.capture());
        assertEquals(seen, saved.getValue().get(0).getPostedAt());
    }

    @Test
    void countsSeparateDroppedMatchesFromKeptHistory() {
        Job stale = job("77", Instant.now().minus(Duration.ofDays(9)), Instant.now());
        when(jobRepository.findExpiredAsOf(any())).thenReturn(List.of(stale));
        // 5 matches on the job, 2 of them NEW/DISMISSED.
        when(matchRepository.markExpiredByJobIds(anyCollection(), any())).thenReturn(5);
        when(matchRepository.deleteByJobIdsAndStatuses(anyCollection(), anyCollection())).thenReturn(2);

        JobRetentionSweeper.SweepCounts counts = sweeper.expireBatch(List.of(stale));

        assertEquals(2, counts.dropped());
        assertEquals(3, counts.kept());
    }
}
