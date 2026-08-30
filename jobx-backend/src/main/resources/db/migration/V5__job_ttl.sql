-- V5: six-day job TTL + expired-job tombstones.
--
-- Why: Jobx exists to get a user onto a company's own careers page BEFORE the
-- role reaches LinkedIn/Naukri. A posting a week old has already lost that
-- race, so storing it costs disk and dilutes the feed for no product value.
--
-- The naive version of this — "DELETE FROM jobs WHERE posted_at is old" — is a
-- trap, because FetchScheduler dedups on (company_id, external_id) against the
-- jobs table alone. A posting still live on the board would come back on the
-- very next poll as a BRAND NEW job: re-inserted with first_seen_at = now(),
-- re-scored, re-notified to every watcher, then swept again next day. A
-- flip-flop loop, and each round wiped the user's APPLIED state through the
-- matches.job_id ON DELETE CASCADE.
--
-- So expiry is two things, not one:
--   1. a TOMBSTONE (expired_jobs) that outlives the job row and keeps the
--      dedup decision — "we dropped this on purpose, do not re-add it"
--   2. matches.job_id becomes NULLABLE + ON DELETE SET NULL, so deleting a job
--      no longer cascades away the user's own history. The sweep deletes the
--      NEW/DISMISSED matches itself and lets SEEN/APPLIED survive with a null
--      job pointer — which is why the facts needed to render such a card
--      (title, apply URL, company) are denormalized onto the match below.

-- 1. Tombstones ---------------------------------------------------------------

CREATE TABLE expired_jobs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    external_id TEXT NOT NULL,
    -- the effective posting date the sweep judged, kept for debugging why a
    -- given posting was dropped when it was
    posted_at   TIMESTAMPTZ,
    expired_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (company_id, external_id)
);

CREATE INDEX idx_expired_jobs_company ON expired_jobs(company_id);

-- 2. Denormalize the job facts a surviving match needs to render --------------
--
-- company_id is a real FK rather than a copied name: the Company row outlives
-- the job (TTL never deletes companies), and it keeps unwatch cleanup working
-- for expired matches — MatchRepository.deleteByUserAndCompany could not reach
-- a match whose job_id is null if it had to route through jobs.

ALTER TABLE matches ADD COLUMN company_id     UUID REFERENCES companies(id) ON DELETE CASCADE;
ALTER TABLE matches ADD COLUMN job_title      TEXT;
ALTER TABLE matches ADD COLUMN apply_url      TEXT;
ALTER TABLE matches ADD COLUMN job_expired_at TIMESTAMPTZ;

-- Pre-V5 job_id was NOT NULL, so every existing match has a job to copy from
-- and the SET NOT NULL below is safe on any V4-shaped database.
UPDATE matches m
   SET company_id = j.company_id,
       job_title  = j.title,
       apply_url  = j.apply_url
  FROM jobs j
 WHERE m.job_id = j.id;

ALTER TABLE matches ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE matches ALTER COLUMN job_title  SET NOT NULL;
ALTER TABLE matches ALTER COLUMN apply_url  SET NOT NULL;

CREATE INDEX idx_matches_company ON matches(company_id);

-- 3. Deleting a job must no longer delete the user's history ------------------
--
-- ON DELETE SET NULL, not CASCADE. The sweep is responsible for removing the
-- NEW/DISMISSED matches explicitly BEFORE it deletes the job; whatever is left
-- (SEEN/APPLIED) is deliberately kept and simply loses its job pointer.
--
-- UNIQUE (user_id, job_id) survives this: Postgres treats NULLs as distinct,
-- so a user may hold any number of expired matches while still being unable to
-- hold two matches for the same live job.

ALTER TABLE matches DROP CONSTRAINT matches_job_id_fkey;
ALTER TABLE matches ALTER COLUMN job_id DROP NOT NULL;
ALTER TABLE matches ADD CONSTRAINT matches_job_id_fkey
    FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE SET NULL;

-- 4. The sweep scans on the effective posting date ----------------------------
--
-- platform_posted_at is the ATS's own date and is the honest TTL clock, but it
-- is nullable (not every board publishes one). first_seen_at is NOT NULL and is
-- the worst case "we know it is at least this old". COALESCE of two plain
-- columns is immutable, so it can be indexed directly.

CREATE INDEX idx_jobs_effective_posted_at
    ON jobs (COALESCE(platform_posted_at, first_seen_at));
