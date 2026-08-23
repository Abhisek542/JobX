-- Seed for testing V4's duplicate-collapse logic. Runs against a V3-shaped
-- schema (BEFORE V4 is applied) and recreates the exact pathology the pre-V4
-- code produced: two users watching the same board, every posting stored once
-- per watch row, matches fanned out per copy — including a status collision
-- (user has APPLIED on one copy, NEW on the other) that the migration's
-- precedence rule must resolve without losing the APPLIED.
--
-- Use on a SCRATCH database only (see docs: create db, apply V1-V3, run this,
-- apply V4, check the expectations at the bottom). Never on the real dev DB —
-- Flyway would not know V4 already ran.

INSERT INTO users (id, email, password_hash, role) VALUES
  ('aaaaaaaa-0000-0000-0000-000000000001', 'usera@collapse.test', 'x', 'USER'),
  ('bbbbbbbb-0000-0000-0000-000000000002', 'userb@collapse.test', 'x', 'USER');

-- A added Razorpay first (canonical name "Razorpay"); B's later row calls it
-- "Razorpay India" — after V4 that name must be discarded. B's row also has the
-- most recent fetch attempt (FAILED), which is what companies must inherit.
INSERT INTO watched_companies (id, user_id, company_name, ats_platform, board_token, status, last_fetched_at, last_fetch_status, created_at) VALUES
  ('11111111-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0000-000000000001', 'Razorpay',       'GREENHOUSE', 'razorpay', 'ACTIVE', '2026-08-20T10:00:00Z', 'SUCCESS', '2026-08-01T00:00:00Z'),
  ('11111111-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0000-000000000002', 'Razorpay India', 'GREENHOUSE', 'razorpay', 'ACTIVE', '2026-08-20T11:00:00Z', 'FAILED',  '2026-08-10T00:00:00Z'),
  ('22222222-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0000-000000000001', 'PhonePe',        'GREENHOUSE', 'phonepe',  'ACTIVE', '2026-08-20T10:05:00Z', 'SUCCESS', '2026-08-02T00:00:00Z');

-- Postings 4001/4002 exist as TWO copies (one per Razorpay watch row);
-- 4003 exists only under A's row (B's row never re-fetched it); 5001 is a
-- control on an undisputed board. A's copies were seen first → survivors.
INSERT INTO jobs (id, company_id, external_id, ats_platform, title, description, apply_url, first_seen_at) VALUES
  ('91111111-0000-0000-0000-00000000400a', '11111111-0000-0000-0000-00000000000a', '4001', 'GREENHOUSE', 'Senior Backend Engineer (Java)', 'Java Spring', 'https://x/4001', '2026-08-11T00:00:00Z'),
  ('91111111-0000-0000-0000-00000000400b', '11111111-0000-0000-0000-00000000000b', '4001', 'GREENHOUSE', 'Senior Backend Engineer (Java)', 'Java Spring', 'https://x/4001', '2026-08-12T00:00:00Z'),
  ('92222222-0000-0000-0000-00000000400a', '11111111-0000-0000-0000-00000000000a', '4002', 'GREENHOUSE', 'Frontend Engineer (React)',      'React TS',    'https://x/4002', '2026-08-11T00:00:00Z'),
  ('92222222-0000-0000-0000-00000000400b', '11111111-0000-0000-0000-00000000000b', '4002', 'GREENHOUSE', 'Frontend Engineer (React)',      'React TS',    'https://x/4002', '2026-08-12T00:00:00Z'),
  ('93333333-0000-0000-0000-00000000400a', '11111111-0000-0000-0000-00000000000a', '4003', 'GREENHOUSE', 'Data Engineer',                  'SQL',         'https://x/4003', '2026-08-11T00:00:00Z'),
  ('95555555-0000-0000-0000-00000000500a', '22222222-0000-0000-0000-00000000000a', '5001', 'GREENHOUSE', 'SRE',                            'K8s',         'https://x/5001', '2026-08-11T00:00:00Z');

INSERT INTO matches (id, user_id, job_id, score, status, created_at) VALUES
  -- A on posting 4001: APPLIED on own copy, NEW on B's copy → must collapse to ONE APPLIED
  ('a0000000-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', '91111111-0000-0000-0000-00000000400a', 88, 'APPLIED',   '2026-08-11T01:00:00Z'),
  ('a0000000-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001', '91111111-0000-0000-0000-00000000400b', 88, 'NEW',       '2026-08-12T01:00:00Z'),
  -- B on posting 4001: SEEN, but only on the dying copy → must be REPOINTED, status kept
  ('b0000000-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002', '91111111-0000-0000-0000-00000000400b', 70, 'SEEN',      '2026-08-12T01:00:00Z'),
  -- A on posting 4002: NEW on survivor only
  ('a0000000-0000-0000-0000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000001', '92222222-0000-0000-0000-00000000400a', 65, 'NEW',       '2026-08-11T01:00:00Z'),
  -- B on posting 4002: APPLIED on the dying copy → repointed, APPLIED survives
  ('b0000000-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000002', '92222222-0000-0000-0000-00000000400b', 65, 'APPLIED',   '2026-08-12T01:00:00Z'),
  -- undisputed rows, must pass through untouched
  ('a0000000-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000001', '93333333-0000-0000-0000-00000000400a', 50, 'NEW',       '2026-08-11T01:00:00Z'),
  ('a0000000-0000-0000-0000-000000000005', 'aaaaaaaa-0000-0000-0000-000000000001', '95555555-0000-0000-0000-00000000500a', 40, 'DISMISSED', '2026-08-11T01:00:00Z');

-- Expected state AFTER applying V4 to this data:
--   companies:         2 rows; razorpay display_name = 'Razorpay' (A's, the
--                      earliest), last_fetch_status = 'FAILED' (B's, the latest)
--   jobs:              4 rows (4001, 4002, 4003, 5001) — the two ...400b copies gone
--   matches:           6 rows —
--                        A: 4001 APPLIED / 4002 NEW / 4003 NEW / 5001 DISMISSED
--                        B: 4001 SEEN    / 4002 APPLIED
--                      (A's duplicate NEW on 4001 deleted; both of B's repointed)
--   watched_companies: 3 rows, each with company_id set; name/token/health columns gone
