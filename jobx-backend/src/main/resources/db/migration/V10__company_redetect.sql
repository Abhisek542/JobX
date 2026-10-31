-- V10: re-detecting a company that moved to another ATS (add-company-improvements PR D).
--
-- PhonePe went from 68 live Greenhouse jobs to a hard 404 in six days, because
-- they moved to SmartRecruiters. The board then sat FAILED until someone went
-- looking for the new one. These columns let BoardRedetector look instead, and
-- store what it finds as a PROPOSAL. Nothing switches until a user confirms it
-- through POST /watchlist/{id}/move.
--
--   careers_url       the careers page the board was found on, from URL mode.
--                     A hint for later sniffs. Always fetched through
--                     SafeUrlFetcher, so a bad value costs one guarded GET.
--   failing_since     set by the first FAILED fetch in a row, cleared by a
--                     successful one. Redetection waits until it is 12h old.
--   last_redetect_at  when BoardRedetector last looked (at most once a day).
--   moved_to_*        the proposed board, or all null. moved_to_reason is
--                     FAILING (the board broke) or NATIVE_AVAILABLE (a healthy
--                     crawled board gained a real ATS; written once PR E ships).
--
-- All nullable. Existing rows start with nothing known, which is accurate.
ALTER TABLE companies ADD COLUMN careers_url       TEXT;
ALTER TABLE companies ADD COLUMN failing_since     TIMESTAMPTZ;
ALTER TABLE companies ADD COLUMN last_redetect_at  TIMESTAMPTZ;
ALTER TABLE companies ADD COLUMN moved_to_platform TEXT;
ALTER TABLE companies ADD COLUMN moved_to_token    TEXT;
ALTER TABLE companies ADD COLUMN moved_to_reason   TEXT
    CHECK (moved_to_reason IS NULL OR moved_to_reason IN ('FAILING', 'NATIVE_AVAILABLE'));
