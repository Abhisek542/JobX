-- V11: the smart crawler for custom careers pages (add-company-improvements PR E).
--
-- A company without a standard ATS is watched as platform CUSTOM, whose
-- board_token is the canonical careers-page URL. ats_platform is TEXT with no
-- CHECK, so CUSTOM itself needs no migration.
--
--   crawl_mode  how the listing page has to be read: STATIC (a plain GET) or
--               RENDER (headless Chromium, for pages that build the list in
--               JavaScript). Set at add time from whichever pass found the jobs,
--               so the scheduler only pays for a browser on boards that need
--               one. NULL for every native-ATS board, and read as STATIC on a
--               CUSTOM row that somehow has none.
ALTER TABLE companies ADD COLUMN crawl_mode TEXT
    CHECK (crawl_mode IS NULL OR crawl_mode IN ('STATIC', 'RENDER'));
