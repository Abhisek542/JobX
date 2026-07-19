# Jobx — project context for Claude Code

Handoff doc from design work in claude.ai. Captures *decisions*, not just ideas —
read before writing code, don't re-litigate items marked DECIDED unless explicitly
asked to revisit. Detailed per-ATS API field notes live in `docs/ats-api-reference.md`
(not loaded by default) — check that file before touching Lever/Ashby/Workable code.

## Implementation status (updated 2026-07-19)

Read this first — reflects actual verified backend progress, not design intent.

**Built and confirmed working:**
- Spring Boot scaffolded (Maven, Postgres via Docker), Flyway migrations ran, all 6 tables created.
- JPA entities: `User`, `FilterProfile`, `WatchedCompany`, `Job`, `Match` — multi-tenant `user_id` present from day one.
- `GreenhouseFetcher` fully implemented and verified against live Razorpay + PhonePe boards — real jobs fetched and deduped correctly.
- `FetchScheduler` runs every 30 min via `@Scheduled`, dedupes by `company_id + external_id`.
- `MatchScorer` ported to a Spring `@Service`, confirmed correct in isolation (test job scored 79 correctly via a temporary `/dev/test-score` endpoint).
- Real test data in place: FilterProfile for user `e373e792-5d4a-458f-98c1-3183eac63366`, watching Razorpay + PhonePe.
- **Match-creation bug (below) fixed and verified 2026-07-18** — `/dev/backfill-matches` now correctly populates the `matches` table.

**FIXED (2026-07-18) — was blocking Phase 2/3:**
`matches` stayed at 0 rows despite 73–93 real jobs and a valid FilterProfile. The
previous note here suspected `WatchedCompany.user` resolution (i.e. a `Job` failing to
resolve back to its watching user) — **that lead was wrong.** Checked directly against
the DB: `watched_companies.user_id` matched `filter_profiles.user_id` exactly, so that
link was never broken.

The real cause: `MatchScorer` used plain `.contains()` substring matching for
`excludeWords`, not word-boundary matching. Razorpay/PhonePe job descriptions all share
boilerplate "About us" text ("one of india's **leading** full-stack...", "this is where
**interns** question CXOs...") that contains `"lead"` and `"intern"` as substrings —
two of the exclude words in the test FilterProfile. Every single fetched job matched at
least one exclude word via this boilerplate and got hard-excluded before keyword
scoring even ran, silently zeroing the `matches` table with no exception (exclusion is
working-as-coded, not an error).

Fix: `MatchScorer.score()` now matches `excludeWords` and `keywords` via a
word-boundary regex helper (`containsWord`, `\bword\b`) instead of `.contains()`. Also
resolves the "Java matches inside JavaScript" bug noted below in the same fix, since
it's the same substring-matching root cause on the keyword side. Verified: backfill
went from 0 → 4 real matches against the live 73-job dataset, all word-boundary-correct.

Two temp debug endpoints on `DevController` support this, delete once the feature has
real CRUD endpoints: `POST /dev/backfill-matches`, `POST /dev/test-score?jobId=&userId=`.

**CURRENT FOCUS (2026-07-19): real CRUD endpoints** (`/watchlist`, `/matches`) — the
one open item left in step 2 of the build order below. `SecurityConfig` still
`permitAll()`s everything (step 4, not started); Lever/Ashby/Workable fetchers remain
stubs — their live API verification is no longer a separate upfront step, it's now
bundled into step 3 (verify each platform as its fetcher gets built, not before).

## What this is

India-first job search tool, two features on purpose: (1) **Discovery** — watch
company career portals (Greenhouse/Lever/Ashby/Workable), surface new postings with a
direct apply link before they hit LinkedIn/Naukri. (2) **Tailoring** (fast-follow,
after discovery works) — honest resume match against a posting: score, real keyword
gaps, copy-ready bullet rewrites, never fabricates skills.

**Explicitly NOT in v1:** autofill (deferred indefinitely), full resume editor, pgvector/semantic matching.

Competitive context: alert tools (Scoutify, HiddenJobs) and tailoring tools
(Jobscan, Teal, Huntr) already exist and are mostly free. The defensible angle isn't
"we have alerts/tailoring" — it's honesty as a brand position and an India +
startup/remote niche incumbents don't serve. Don't build generic features to compete
head-on with funded free tools.

## Tech stack (DECIDED)

- **Backend**: Spring Boot
- **DB**: Plain Postgres, no pgvector in v1 — matching is rule-based
- **Frontend**: Angular for the logged-in app. Separate Astro site for public/SEO
  pages comes later — don't build now, but keep public job data servable without
  auth and URL slugs stable.
- **AI**: Spring AI + LLM, but only for tailoring (after discovery works). No AI in discovery/matching.
- **Auth**: Required from the start, shared multi-tenant app. Bake `user_id` into schema from day one.

## ATS integration approach (DECIDED)

Detect ATS from careers URL, hit that platform's public job API directly:
- Greenhouse: `boards-api.greenhouse.io/v1/boards/{token}/jobs`
- Lever: `api.lever.co/v0/postings/{company}?mode=json`
- Ashby: `api.ashbyhq.com/posting-api/job-board/{token}` (public, no-auth path — not `jobPosting.list`)
- Workable: `apply.workable.com/api/v1/widget/accounts/{token}` (embed-widget endpoint)
- Trickier/later: Rippling, Recruitee, BambooHR, Workday — no clean public API, mark "portal unsupported" rather than faking support

**CURRENT FOCUS: CRUD endpoints for the Greenhouse-only loop (step 2), not fetchers.**
Greenhouse itself is done end-to-end. Lever/Ashby/Workable fetcher work — including
their live API verification — is deliberately deferred until step 2's CRUD endpoints
are done; all four are still in scope for v1, this is a sequencing change, not a scope
cut. Verified field-level details for all four platforms (JSON shapes, date formats,
known-stale board tokens like Hasura/Zerodha) are in `docs/ats-api-reference.md` — read
that file when resuming this work, not this one.

## The matching engine — VERIFIED, port this logic, don't redesign it

Built and run standalone (`MatchScorer.java`), confirmed correct against 5 test
profiles, and re-confirmed after the Spring port (see Implementation status above).
Each user has their own `keywords`, `excludeWords`, `expMin`, `expMax`.

1. **Hard exclude**: any exclude-word anywhere in title/description → job dropped entirely.
2. **Keyword match is OR, not AND**: needs ≥1 keyword match to appear. Title hits count
   double vs. description-only. `keywordScore = round(70 * min(1, actualWeight / (keywords.size * 2)))`,
   `actualWeight = titleHits*2 + descOnlyHits*1`.
3. **Experience is a SOFT filter** (explicit decision, do not make hard): overlapping
   range = full 30 points, else `experienceScore = max(0, 30 - distanceInYears * 10)`.
   Never excludes on experience mismatch.
4. Total = `keywordScore + experienceScore`, 0–100, sort feed descending.

**Fixed 2026-07-18:** matching now uses word-boundary regex, not plain substring — "Java" no longer matches inside "JavaScript" (see Implementation status above for the full story, including why this same bug was zeroing out the `matches` table entirely via `excludeWords`).

## Data model

```
User            (id, email, password_hash, created_at)
FilterProfile   (id, user_id, keywords[], exclude_words[], exp_min, exp_max)
WatchedCompany  (id, user_id, company_name, ats_platform, board_token/url, status)
Job             (id, company_id, external_id, title, description, location,
                 exp_min, exp_max, apply_url, posted_at, ats_platform)
Match           (id, user_id, job_id, score, matched_keywords[], created_at,
                 status: new/seen/applied/dismissed)
```

Actual implemented `jobs` schema differs slightly: `platform_posted_at` (ATS's own
timestamp, display only) vs `first_seen_at` (when Jobx first observed the job — the
real sort/alert field). `raw_json` jsonb escape hatch. `Job.company` is a real
`@ManyToOne` to `WatchedCompany` (this link was double-checked against the DB while
fixing the match-creation bug above — it's correct).

`Job` rows are shared/global per company; `Match` rows are the per-user scored view,
recomputed by running each user's `FilterProfile` against new `Job` rows after each poll.

Given per-company `metadata` inconsistency on Greenhouse, don't add strongly-typed
columns for ATS-specific fields — store as unstructured `raw_metadata` JSON if kept at
all, and don't feed it into `MatchScorer` (which only needs title, description,
location, experience range).

## UI reference

Working, tested HTML/JS mockup exists (`dashboard-mockup.html`) — dark theme, sidebar,
watchlist panel, alert feed with match-score rings, filter-edit modal, "Check my
resume" drawer stub. Its `<script>` is a proven JS port of `MatchScorer.java`. Treat as
the UX target for the Angular build; match its color tokens unless told otherwise.

## Build order

Original order paired Greenhouse + Lever as step 2's first milestone; now deliberately
narrowed to Greenhouse-only first (see ATS section above) — re-confirm with user before
resuming multi-ATS work.

1. Verify real ATS API responses by hand — **Greenhouse: done, scope closed.**
   Lever/Ashby/Workable verification is no longer a separate step here — moved into
   step 3, verified live as each fetcher is built instead of upfront.
2. Core entities + Greenhouse fetcher + MatchScorer as a Spring service, no auth yet.
   **Fetch + matching loop done — working end-to-end.** Match-creation bug fixed
   2026-07-18; backfill verified against live data.
   **🎯 CURRENT FOCUS — remaining:** real CRUD endpoints (`/watchlist`, `/matches`);
   retire temp `/dev/*` endpoints once they exist.
3. More fetchers: Ashby, Workable, Lever, then harder ones if time allows. **Now also
   includes live API verification for Lever/Ashby/Workable (moved from step 1)** —
   verify each platform against actual watchlist companies as its fetcher is built.
4. Auth (Spring Security) + multi-tenant data, before handing app to other test users.
5. Angular dashboard wired to the live backend, matching the mockup.
6. Tailoring feature (Spring AI + LLM), fast-follow after discovery validated.
7. Astro layer for SEO/public pages, only after product validated with real users.

## Open/unvalidated assumptions

- **Monetization: confirmed as of 2026-07-12** — user has verified PMF, Indian job
  seekers will pay. (Previously flagged as unvalidated; update if this changes.)
- ATS API shapes: Greenhouse verified. Lever, Ashby, Workable documented and partially
  spot-checked but not verified against this project's actual watchlist companies —
  verification for these now happens per-fetcher in step 3, not as a standalone step.
- Shared app with logins means real auth security work — no skipping password hashing, no plaintext secrets.
