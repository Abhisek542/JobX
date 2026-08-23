-- Test watchlist seed data — run after V3 migration
-- Replace :test_user_id with your actual user UUID from the users table

-- Greenhouse
INSERT INTO watched_companies (id, user_id, company_name, ats_platform, board_token, status, created_at)
VALUES (gen_random_uuid(), :test_user_id, 'Razorpay', 'GREENHOUSE', 'razorpaysoftwareprivatelimited', 'ACTIVE', now());

INSERT INTO watched_companies (id, user_id, company_name, ats_platform, board_token, status, created_at)
VALUES (gen_random_uuid(), :test_user_id, 'PhonePe', 'GREENHOUSE', 'phonepe', 'ACTIVE', now());

-- Ashby
INSERT INTO watched_companies (id, user_id, company_name, ats_platform, board_token, status, created_at)
VALUES (gen_random_uuid(), :test_user_id, 'Aspora', 'ASHBY', 'Aspora', 'ACTIVE', now());

-- Lever
INSERT INTO watched_companies (id, user_id, company_name, ats_platform, board_token, status, created_at)
VALUES (gen_random_uuid(), :test_user_id, 'FamPay', 'LEVER', 'fampay', 'ACTIVE', now());

INSERT INTO watched_companies (id, user_id, company_name, ats_platform, board_token, status, created_at)
VALUES (gen_random_uuid(), :test_user_id, 'Sprinto', 'LEVER', 'Sprinto', 'ACTIVE', now());

-- Workable
INSERT INTO watched_companies (id, user_id, company_name, ats_platform, board_token, status, created_at)
VALUES (gen_random_uuid(), :test_user_id, 'Apna', 'WORKABLE', 'apna', 'ACTIVE', now());
