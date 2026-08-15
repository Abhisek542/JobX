-- Watchlist fetch health (V1_IMPROVEMENTS.md P1).
--
-- Until now a dead board and a board with genuinely no new roles were
-- indistinguishable: every fetcher swallowed its exception, returned an empty
-- list, and the scheduler still stamped last_fetched_at. The watchlist then
-- reported "checked 2 minutes ago" for a board that has been 404ing for a week,
-- and the dashboard's "Refresh issue" state had no field to render from.
--
-- last_fetch_error holds a short sanitized summary for operators — never a
-- stack trace, and not currently returned by the API.

ALTER TABLE watched_companies ADD COLUMN last_fetch_status TEXT;
ALTER TABLE watched_companies ADD COLUMN last_fetch_error  TEXT;

ALTER TABLE watched_companies ADD CONSTRAINT watched_companies_last_fetch_status_check
    CHECK (last_fetch_status IS NULL OR last_fetch_status IN ('SUCCESS', 'FAILED'));

-- Existing rows that have been fetched at least once predate this column.
-- They completed without an exception reaching the scheduler, so SUCCESS is the
-- accurate backfill; never-fetched rows stay NULL ("not checked yet").
UPDATE watched_companies SET last_fetch_status = 'SUCCESS' WHERE last_fetched_at IS NOT NULL;
