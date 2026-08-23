-- V4: real companies table — fix B for the per-watch-row jobs defect.
--
-- Pre-V4, jobs.company_id referenced watched_companies(id): a job belonged to
-- ONE USER'S WATCH ROW, while scoreForAllWatchers fanned each new job out to
-- every user watching the same (platform, token). With N users on one board
-- that meant N fetches per cycle, N stored copies of every posting, N^2 match
-- rows, duplicate feed cards — and one user deleting their watch cascaded away
-- other users' matches (including APPLIED state).
--
-- This migration:
--   1. creates companies keyed UNIQUE (ats_platform, board_token)
--   2. populates it from watched_companies (canonical display_name = the
--      EARLIEST watch row's name; fetch health = the LATEST attempt's)
--   3. points watched_companies at it (watch rows become pure join rows)
--   4. re-parents jobs onto companies, collapsing duplicate copies of the same
--      posting: survivor = earliest first_seen_at; each user's matches across
--      all copies collapse to ONE, keeping the highest-precedence status
--      (APPLIED > SEEN > DISMISSED > NEW) so nobody's applied-state is lost
--   5. drops the board-identity and fetch-health columns from watched_companies
--
-- The dev DB is empty as of the 2026-08-22 truncation, so steps 2 and 4 are
-- no-ops there — but they are written (and tested against a seeded duplicate
-- scenario, see db/seed/migration-collapse-fixture.sql) so this migration is
-- correct on ANY database shaped like V3, not just an empty one.

-- 1. The shared board table ---------------------------------------------------

CREATE TABLE companies (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ats_platform      TEXT NOT NULL,
    board_token       TEXT NOT NULL,
    display_name      TEXT NOT NULL,   -- canonical: first adder's name, shown to every watcher
    last_fetched_at   TIMESTAMPTZ,     -- moved from watched_companies: fetching is a board property
    last_fetch_status TEXT,
    last_fetch_error  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (ats_platform, board_token),
    CONSTRAINT companies_last_fetch_status_check
        CHECK (last_fetch_status IS NULL OR last_fetch_status IN ('SUCCESS', 'FAILED'))
);

-- 2. One company per distinct board -------------------------------------------

-- display_name / created_at from the EARLIEST watch row: first adder is canonical.
INSERT INTO companies (ats_platform, board_token, display_name, created_at)
SELECT DISTINCT ON (ats_platform, board_token)
       ats_platform, board_token, company_name, created_at
FROM watched_companies
ORDER BY ats_platform, board_token, created_at, id;

-- Fetch health from the LATEST attempt across the board's watch rows.
UPDATE companies c
SET last_fetched_at   = h.last_fetched_at,
    last_fetch_status = h.last_fetch_status,
    last_fetch_error  = h.last_fetch_error
FROM (
    SELECT DISTINCT ON (ats_platform, board_token)
           ats_platform, board_token, last_fetched_at, last_fetch_status, last_fetch_error
    FROM watched_companies
    WHERE last_fetched_at IS NOT NULL
    ORDER BY ats_platform, board_token, last_fetched_at DESC
) h
WHERE c.ats_platform = h.ats_platform AND c.board_token = h.board_token;

-- 3. Watch rows point at their company ----------------------------------------

ALTER TABLE watched_companies
    ADD COLUMN company_id UUID REFERENCES companies(id) ON DELETE CASCADE;

UPDATE watched_companies w
SET company_id = c.id
FROM companies c
WHERE c.ats_platform = w.ats_platform AND c.board_token = w.board_token;

ALTER TABLE watched_companies ALTER COLUMN company_id SET NOT NULL;

-- 4. Re-parent jobs, collapsing duplicates ------------------------------------

ALTER TABLE jobs
    ADD COLUMN new_company_id UUID REFERENCES companies(id) ON DELETE CASCADE;

UPDATE jobs j
SET new_company_id = w.company_id
FROM watched_companies w
WHERE j.company_id = w.id;

-- Survivor per posting: earliest first_seen_at wins (that copy's timestamps are
-- the honest "when Jobx first saw this role").
CREATE TEMP TABLE job_survivors ON COMMIT DROP AS
SELECT id AS old_id,
       first_value(id) OVER (PARTITION BY new_company_id, external_id
                             ORDER BY first_seen_at, created_at, id) AS keep_id
FROM jobs;

-- One winning match per (user, surviving job) across ALL copies of a posting:
-- highest status precedence first (APPLIED > SEEN > DISMISSED > NEW — a
-- migration must never demote applied-state), earliest created_at as tiebreak.
CREATE TEMP TABLE winning_matches ON COMMIT DROP AS
SELECT DISTINCT ON (m.user_id, s.keep_id)
       m.id AS match_id, s.keep_id
FROM matches m
JOIN job_survivors s ON m.job_id = s.old_id
ORDER BY m.user_id, s.keep_id,
         CASE m.status WHEN 'APPLIED' THEN 4 WHEN 'SEEN' THEN 3
                       WHEN 'DISMISSED' THEN 2 ELSE 1 END DESC,
         m.created_at, m.id;

-- Everything that didn't win is a duplicate view of the same posting: drop it.
DELETE FROM matches m
USING job_survivors s
WHERE m.job_id = s.old_id
  AND NOT EXISTS (SELECT 1 FROM winning_matches w WHERE w.match_id = m.id);

-- Point each winner at the surviving job row (no-op where it already was).
UPDATE matches m
SET job_id = w.keep_id
FROM winning_matches w
WHERE m.id = w.match_id AND m.job_id <> w.keep_id;

-- Duplicate job copies are now unreferenced: drop them.
DELETE FROM jobs j
USING job_survivors s
WHERE j.id = s.old_id AND s.old_id <> s.keep_id;

-- Swap the columns: dropping old company_id also drops its FK to
-- watched_companies, the old UNIQUE (company_id, external_id) and
-- idx_jobs_company_id.
ALTER TABLE jobs DROP COLUMN company_id;
ALTER TABLE jobs RENAME COLUMN new_company_id TO company_id;
ALTER TABLE jobs ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE jobs ADD CONSTRAINT jobs_company_id_external_id_key UNIQUE (company_id, external_id);
CREATE INDEX idx_jobs_company_id ON jobs(company_id);

-- 5. Slim watched_companies down to a join row --------------------------------

-- Dropping ats_platform/board_token drops the old
-- UNIQUE (user_id, board_token, ats_platform); dropping last_fetch_status
-- drops V3's check constraint.
ALTER TABLE watched_companies
    DROP COLUMN company_name,
    DROP COLUMN ats_platform,
    DROP COLUMN board_token,
    DROP COLUMN last_fetched_at,
    DROP COLUMN last_fetch_status,
    DROP COLUMN last_fetch_error;

ALTER TABLE watched_companies
    ADD CONSTRAINT watched_companies_user_id_company_id_key UNIQUE (user_id, company_id);
CREATE INDEX idx_watched_companies_company ON watched_companies(company_id);
