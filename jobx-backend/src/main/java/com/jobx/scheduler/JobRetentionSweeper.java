package com.jobx.scheduler;

import com.jobx.entity.ExpiredJob;
import com.jobx.entity.Job;
import com.jobx.entity.Match;
import com.jobx.repository.ExpiredJobRepository;
import com.jobx.repository.JobRepository;
import com.jobx.repository.MatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The six-day retention sweep.
 *
 * Jobx's whole proposition is getting a user onto the company's own careers
 * page before the role reaches the aggregators. A posting older than the
 * window has already lost that race, so keeping it costs disk and dilutes the
 * feed. This deletes it — description, raw JSON and all.
 *
 * Order matters, and each step exists for a reason:
 *
 *   1. mark the surviving matches expired (while job_id still points at them)
 *   2. delete the NEW/DISMISSED matches — nobody engaged with those
 *   3. write a tombstone per job
 *   4. delete the job; ON DELETE SET NULL nulls out the SEEN/APPLIED matches
 *      the user asked to keep
 *
 * Step 3 is the one that is easy to leave out and impossible to do without.
 * FetchScheduler dedups against the jobs table, so a posting that is still
 * live on the board would come straight back on the next poll as a brand-new
 * job — re-scored, re-notified, then swept again the next day, forever. The
 * tombstone is how the deletion sticks.
 */
@Component
@Slf4j
public class JobRetentionSweeper {

    /** Matches the user never engaged with — safe to drop with the posting. */
    private static final Set<Match.MatchStatus> DISPOSABLE_STATUSES =
            Set.of(Match.MatchStatus.NEW, Match.MatchStatus.DISMISSED);

    /** Jobs deleted per transaction, so one sweep can't hold a huge lock. */
    private static final int BATCH_SIZE = 500;

    private final JobRepository jobRepository;
    private final MatchRepository matchRepository;
    private final ExpiredJobRepository expiredJobRepository;
    /**
     * Own proxy, so each batch gets a real transaction. Calling
     * this.expireBatch(...) directly would skip @Transactional entirely — the
     * same self-invocation trap FetchScheduler documents.
     */
    private final ObjectProvider<JobRetentionSweeper> self;
    private final int ttlDays;

    public JobRetentionSweeper(JobRepository jobRepository,
                               MatchRepository matchRepository,
                               ExpiredJobRepository expiredJobRepository,
                               ObjectProvider<JobRetentionSweeper> self,
                               @Value("${jobx.retention.job-ttl-days:6}") int ttlDays) {
        this.jobRepository = jobRepository;
        this.matchRepository = matchRepository;
        this.expiredJobRepository = expiredJobRepository;
        this.self = self;
        this.ttlDays = ttlDays;
    }

    /**
     * Deliberately NOT @Transactional itself — it batches, so that a board with
     * thousands of stale postings commits in chunks rather than in one lock.
     */
    @Scheduled(fixedDelayString = "${jobx.retention.sweep-interval-ms:86400000}") // daily
    public void sweepExpiredJobs() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(ttlDays));
        List<Job> expired = jobRepository.findExpiredAsOf(cutoff);

        if (expired.isEmpty()) {
            log.debug("Retention sweep: nothing older than {} days", ttlDays);
            return;
        }

        log.info("Retention sweep: {} jobs posted before {} ({}-day TTL)",
                expired.size(), cutoff, ttlDays);

        int jobsDeleted = 0;
        int matchesDropped = 0;
        int matchesKept = 0;

        for (int from = 0; from < expired.size(); from += BATCH_SIZE) {
            List<Job> batch = expired.subList(from, Math.min(from + BATCH_SIZE, expired.size()));
            SweepCounts counts = self.getObject().expireBatch(batch);
            jobsDeleted += batch.size();
            matchesDropped += counts.dropped();
            matchesKept += counts.kept();
        }

        log.info("Retention sweep complete: {} jobs deleted, {} matches dropped, "
                        + "{} kept as saved/applied history",
                jobsDeleted, matchesDropped, matchesKept);
    }

    public record SweepCounts(int dropped, int kept) {}

    /** Public and called through the proxy above — @Transactional needs both. */
    @Transactional
    public SweepCounts expireBatch(List<Job> batch) {
        List<UUID> jobIds = batch.stream().map(Job::getId).toList();
        Instant now = Instant.now();

        // 1. Stamp every match on these jobs, so the ones we keep can be
        //    rendered as "no longer listed" rather than silently going stale.
        //    Must run first: after the delete, job_id is null and unreachable.
        int stamped = matchRepository.markExpiredByJobIds(jobIds, now);

        // 2. Drop what nobody engaged with.
        int dropped = matchRepository.deleteByJobIdsAndStatuses(jobIds, DISPOSABLE_STATUSES);

        // 3. Tombstone before deleting — this is what stops the next fetch
        //    cycle re-adding a posting that is still live on the board.
        List<ExpiredJob> tombstones = batch.stream().map(job -> {
            ExpiredJob tombstone = new ExpiredJob();
            tombstone.setCompany(job.getCompany());
            tombstone.setExternalId(job.getExternalId());
            tombstone.setPostedAt(effectivePostedAt(job));
            tombstone.setExpiredAt(now);
            return tombstone;
        }).toList();
        expiredJobRepository.saveAll(tombstones);

        // 4. Delete. Surviving matches keep their row and lose their job
        //    pointer (ON DELETE SET NULL, per V5).
        jobRepository.deleteAllInBatch(batch);

        return new SweepCounts(dropped, stamped - dropped);
    }

    /** The TTL clock: the ATS's own date when it published one, else when we first saw it. */
    private Instant effectivePostedAt(Job job) {
        return job.getPlatformPostedAt() != null ? job.getPlatformPostedAt() : job.getFirstSeenAt();
    }
}
