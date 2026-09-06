-- V6: record add-company attempts that could not be resolved.
--
-- The add-company flow now resolves a board from a company name, a website or a
-- careers link. When all four strategies come up empty -- usually a portal with
-- no public API (Workday, Rippling, BambooHR) -- the user is told so plainly.
-- This table keeps the one useful thing about that dead end: somebody wanted
-- that company. It is the evidence for which fetcher to build next.
--
-- user_id is ON DELETE SET NULL rather than CASCADE: the demand signal is still
-- true after the account that produced it is gone.

CREATE TABLE unsupported_board_requests (
    id            UUID PRIMARY KEY,
    user_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    query         TEXT NOT NULL,
    platform_hint TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The only two questions this table is ever asked: what was requested recently,
-- and what was requested most.
CREATE INDEX idx_unsupported_requests_created_at
    ON unsupported_board_requests (created_at DESC);
CREATE INDEX idx_unsupported_requests_query
    ON unsupported_board_requests (LOWER(query));
