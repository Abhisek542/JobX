-- Seed the add-company catalog with boards already verified live.
--
-- WHY THIS EXISTS
-- Since V4 the companies table doubles as a registry of real boards: every row
-- is one somebody successfully added and Jobx has fetched. The add-company
-- typeahead searches it, so a user who types "Groww" picks a verified board with
-- no network call, no careers URL to hunt down, and no guessing at all. That is
-- much the best path in the flow -- but it is empty on a fresh database, which
-- is precisely when a new user is least able to supply a board token themselves.
--
-- These are the tokens from docs/ats-test-data.md, each confirmed live against
-- its public API. Deliberately NOT included: Greenhouse "phonepe", which 404'd
-- six days after last working when PhonePe moved to SmartRecruiters. PhonePe is
-- seeded at its live SmartRecruiters board instead. Board tokens rot, so treat
-- this file as a snapshot, not a source of truth -- CompanyResolver already
-- demotes any row whose last fetch FAILED.
--
-- These rows have no watchers, which is a supported state: FetchScheduler only
-- polls companies with at least one ACTIVE watch, so an unwatched catalog entry
-- costs nothing until somebody adds it. last_fetch_* are left NULL because Jobx
-- genuinely has not checked them on this instance.
--
-- CASE MATTERS. "Sprinto", "Aspora" and "BoschGroup" are exact; Lever and Ashby
-- 404 on the wrong case.
--
-- Idempotent: safe to re-run, and safe to run on a database where users have
-- already added some of these boards themselves.
--
--   psql "$JOBX_DB_URL" -f src/main/resources/db/seed/company-catalog.sql

INSERT INTO companies (id, ats_platform, board_token, display_name, created_at)
VALUES
    -- GREENHOUSE
    (gen_random_uuid(), 'GREENHOUSE', 'razorpaysoftwareprivatelimited', 'Razorpay',  NOW()),
    (gen_random_uuid(), 'GREENHOUSE', 'groww',                         'Groww',     NOW()),
    (gen_random_uuid(), 'GREENHOUSE', 'mixpanel',                      'Mixpanel',  NOW()),
    (gen_random_uuid(), 'GREENHOUSE', 'gitlab',                        'GitLab',    NOW()),

    -- LEVER  (Sprinto is capital-S; lowercase 404s)
    (gen_random_uuid(), 'LEVER',      'fampay',                        'FamPay',     NOW()),
    (gen_random_uuid(), 'LEVER',      'Sprinto',                       'Sprinto',    NOW()),
    (gen_random_uuid(), 'LEVER',      'meesho',                        'Meesho',     NOW()),
    (gen_random_uuid(), 'LEVER',      'zeta',                          'Zeta',       NOW()),
    (gen_random_uuid(), 'LEVER',      'porter',                        'Porter',     NOW()),
    (gen_random_uuid(), 'LEVER',      'netomi',                        'Netomi',     NOW()),
    (gen_random_uuid(), 'LEVER',      'mindtickle',                    'MindTickle', NOW()),

    -- ASHBY  (Aspora is capital-A)
    (gen_random_uuid(), 'ASHBY',      'Aspora',                        'Aspora',  NOW()),
    (gen_random_uuid(), 'ASHBY',      'atlan',                         'Atlan',   NOW()),
    (gen_random_uuid(), 'ASHBY',      'linear',                        'Linear',  NOW()),
    (gen_random_uuid(), 'ASHBY',      'sardine',                       'Sardine', NOW()),
    (gen_random_uuid(), 'ASHBY',      'notion',                        'Notion',  NOW()),
    (gen_random_uuid(), 'ASHBY',      'ramp',                          'Ramp',    NOW()),

    -- WORKABLE
    (gen_random_uuid(), 'WORKABLE',   'epignosis',                     'Epignosis', NOW()),
    (gen_random_uuid(), 'WORKABLE',   'foodics',                       'Foodics',   NOW()),
    (gen_random_uuid(), 'WORKABLE',   'apna',                          'Apna',      NOW()),

    -- SMARTRECRUITERS  (PhonePe's live board since it left Greenhouse)
    (gen_random_uuid(), 'SMARTRECRUITERS', 'PHONEPELIMITED',           'PhonePe', NOW()),
    (gen_random_uuid(), 'SMARTRECRUITERS', 'BoschGroup',               'Bosch',   NOW())
ON CONFLICT (ats_platform, board_token) DO NOTHING;
