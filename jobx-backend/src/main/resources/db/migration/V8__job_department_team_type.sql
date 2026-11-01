-- V8: department, team and employment type on jobs (add-company-improvements PR C).
--
-- Most ATS payloads already carry these; until now they lived only in raw_json.
-- Display only: MatchScorer does not read them. All nullable, and there is no
-- backfill: existing rows stay null and the six-day TTL turns the table over.
--
-- The plan proposed one V8 for every column in add-company-improvements.md.
-- PR C ships first and alone, so this migration holds only its own columns;
-- PRs A, D and E take the next free numbers.
ALTER TABLE jobs ADD COLUMN department      TEXT;
ALTER TABLE jobs ADD COLUMN team            TEXT;
ALTER TABLE jobs ADD COLUMN employment_type TEXT;
