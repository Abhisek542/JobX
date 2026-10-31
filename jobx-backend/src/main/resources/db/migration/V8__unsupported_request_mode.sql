-- V8: record which add-company mode a dead end happened in.
--
-- The add-company modal now asks the user to pick a mode: look a company up by
-- NAME (catalog, then slug probing) or paste its careers page URL (parse, sniff,
-- then probing on the domain label). The two dead ends mean different things:
--   NAME -- a company someone knows of, with no board Jobx could find;
--   URL  -- a real careers page, usually on an ATS Jobx can't read yet.
-- Keeping them apart is what makes this table useful for deciding what to build.
--
-- Nullable: rows recorded before V8 have no mode, and the report endpoint is
-- fire-and-forget, so a client that omits it still records the demand.

ALTER TABLE unsupported_board_requests ADD COLUMN mode TEXT;
