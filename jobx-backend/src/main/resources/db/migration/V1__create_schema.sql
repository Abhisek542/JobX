-- Jobx Phase 1 schema
-- Multi-tenant from day one (user_id on all relevant tables) per CLAUDE.md

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE watched_companies (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    company_name  TEXT NOT NULL,
    ats_platform  TEXT NOT NULL,  -- GREENHOUSE | LEVER | ASHBY | WORKABLE | UNSUPPORTED
    board_token   TEXT NOT NULL,  -- extracted from careers page URL, never guessed
    status        TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | PAUSED | UNSUPPORTED
    last_fetched_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, board_token, ats_platform)
);

CREATE TABLE jobs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id         UUID NOT NULL REFERENCES watched_companies(id) ON DELETE CASCADE,
    external_id        TEXT NOT NULL,   -- ATS's own job ID (for dedup)
    ats_platform       TEXT NOT NULL,
    title              TEXT NOT NULL,
    description        TEXT,            -- plain text, HTML stripped by fetcher
    location           TEXT,            -- display only, not used in scoring
    exp_min            INTEGER,         -- nullable — best-effort parse from JD text
    exp_max            INTEGER,         -- nullable — MatchScorer handles null as distance=0
    apply_url          TEXT NOT NULL,
    platform_posted_at TIMESTAMPTZ,    -- ATS's "posted" timestamp, display only
    first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),  -- Jobx's own observation time
    raw_json           JSONB,           -- full ATS response, escape hatch
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (company_id, external_id)
);

CREATE TABLE filter_profiles (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE UNIQUE,
    keywords      TEXT[],              -- OR match, title=2x weight, desc=1x
    exclude_words TEXT[],              -- hard exclude — any match drops the job
    exp_min       INTEGER,             -- soft range — distance penalty, never hard exclude
    exp_max       INTEGER,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE matches (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id           UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    score            INTEGER NOT NULL,  -- 0-100
    matched_keywords TEXT[],
    status           TEXT NOT NULL DEFAULT 'NEW',  -- NEW | SEEN | APPLIED | DISMISSED
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, job_id)
);

-- Indexes for common query patterns
CREATE INDEX idx_jobs_company_id        ON jobs(company_id);
CREATE INDEX idx_jobs_first_seen_at     ON jobs(first_seen_at DESC);
CREATE INDEX idx_matches_user_id        ON matches(user_id);
CREATE INDEX idx_matches_status         ON matches(status);
CREATE INDEX idx_matches_created_at     ON matches(created_at DESC);
CREATE INDEX idx_watched_companies_user ON watched_companies(user_id);
CREATE INDEX idx_watched_companies_status ON watched_companies(status);
