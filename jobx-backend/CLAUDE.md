
# Jobx — project context for Claude Code

Handoff doc from design work in claude.ai. Captures *decisions*, not just ideas —
read before writing code, don't re-litigate items marked DECIDED unless explicitly
asked to revisit. Detailed per-ATS API field notes live in `docs/ats-api-reference.md`
(not loaded by default) — check that file before touching Lever/Ashby/Workable code.

## Implementation status (updated 2026-08-15)

Read this first — reflects actual verified backend progress, not design intent.

**Built and confirmed working:**
- Spring Boot scaffolded (Maven, Postgres via Docker), Flyway migrations ran, all 6 tables created.
- JPA entities: `User`, `FilterProfile`, `WatchedCompany`, `Job`, `Match` — multi-tenant `user_id` present from day one.
- `GreenhouseFetcher` fully implemented and verified against live Razorpay + PhonePe boards — real jobs fetched and deduped correctly.
- `FetchScheduler` runs every 30 min via `@Scheduled`, dedupes by `company_id + external_id`.
- `MatchScorer` ported to a Spring `@Service`, confirmed correct in isolation (test job scored 79 correctly via a temporary `/dev/test-score` endpoint).
- Real test data in place: FilterProfile for user `e373e792-5d4a-458f-98c1-3183eac63366`, watching Razorpay + PhonePe.
- **Match-creation bug (below) fixed and verified 2026-07-18** — `/dev/backfill-matches` now correctly populates the `matches` table.
- **Phase 4 auth done and verified 2026-07-30** — JWT access-token-only (no refresh token, 7-day
  expiry), `POST /auth/register` + `POST /auth/login`, `BCryptPasswordEncoder`, no
  `UserDetailsService`/`AuthenticationManager` (deliberately skipped — login does a direct
  `passwordEncoder.matches()` check, and `JwtAuthenticationFilter` loads the `User` entity and
  sets it directly as the `Authentication` principal, so controllers bind it with
  `@AuthenticationPrincipal User user`). `users.role` column added (`USER`/`ADMIN`, migration
  `V2__add_user_role.sql`) but nothing branches on it yet — provisioned early to avoid a second
  migration later. `SecurityConfig` now locks down every route except `/auth/**`, `/dev/**`, and
  `/error` (`/dev/**` stays open on purpose — dev-only tooling, revisit before a real deploy).
  `WatchlistController`/`MatchController` no longer take `userId` as a query param — swapped for
  the real authenticated principal, closing the last item from step 4's build-order note below.
  **Gotcha hit and fixed during verification:** a `ResponseStatusException` (e.g. 409 on duplicate
  register) triggers an internal Spring forward to `/error`; without permitting that path too,
  `AuthorizationFilter` denies the forward for anonymous callers and the 401 entry point silently
  overwrites the real status/body. Any future custom `exceptionHandling()` work should keep
  `/error` in mind.

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

**Step 3 (multi-ATS fetchers) done and verified 2026-08-02** — user reversed the
2026-07-30 deferral and pulled step 3 back ahead of the Angular dashboard. All three
fetchers (`AshbyFetcher`, `LeverFetcher`, `WorkableFetcher`) implemented and verified
against live boards (Aspora 18 jobs / FamPay 14 / Sprinto 29 / Apna 96 after
shortcode dedup; 124 real matches scored for the `phase3-verify@jobx.dev` test user,
zero duplicates on re-fetch). Workable needs a two-call list+detail design — full
field notes and quirks (Lever's multi-field description assembly, Workable's
duplicate-shortcode list rows, dead Postman board) are in `docs/ats-api-reference.md`.
`parseExperience` was hoisted out of `GreenhouseFetcher` into the shared
`ExperienceParser`. First unit tests added: fixture-based mapping tests per fetcher
under `src/test/java/com/jobx/fetcher/`, real captured API responses in
`src/test/resources/fixtures/`.

**Backend P0 slice from `V1_IMPROVEMENTS.md` done and verified 2026-08-02** (that
file is the adopted improvement scope — read it alongside this one):
- **FilterProfile CRUD**: `GET/PUT/DELETE /profile/filter` (`FilterProfileController`).
  PUT upserts; lists trimmed/deduped case-insensitively via `TextLists`; ≥1 keyword
  required; `expMin <= expMax` enforced. This closed the "profiles only via manual
  SQL" gap.
- **Error contract**: every non-2xx response is `ApiError`
  `{status, code, detail, fieldErrors?}` via `GlobalExceptionHandler`
  (`@RestControllerAdvice`); `@Valid` on all request DTOs (manual null/blank checks
  in controllers removed). The 401 entry point and the 429 limiter hand-write the
  same JSON shape — keep them in sync if `ApiError` changes.
- **Prod safety**: `spring.profiles.default: dev`. Outside the `dev` profile,
  `JwtService` refuses to start on a missing/default `JOBX_JWT_SECRET`;
  `DevController` is `@Profile("dev")` and `/dev/**` is only permitAll in dev
  (verified live: 401 under prod). `RateLimitFilter` throttles `/auth/*` per IP
  (10/min default, `jobx.auth.rate-limit.*` props) with 429 + Retry-After.
  In-memory, single-instance; X-Forwarded-For handling is a TODO if deployed
  behind a proxy.

**Manual "Check now" backend done 2026-08-02** (first P1 item pulled forward, per user
request): `POST /watchlist/{id}/fetch` — same fetch/dedup/scoring flow as the
scheduler (`FetchScheduler.fetchCompany` is now public and returns
`FetchResult`; as of 2026-08-15 that record is
`(newJobs, newMatchesForOwner, failed)` and the endpoint returns 502 when the
board is unreachable), 404 for non-owned, 409 for non-ACTIVE,
429 during the per-company cooldown (rides on `last_fetched_at` — no new state;
`jobx.fetch.manual-cooldown-ms`, default 5 min). Response feeds the dashboard
button's "Checked just now; N new matches" / "No new roles" text. A global
"Refresh all" was deliberately NOT built — needs a stricter cooldown first because
Workable boards fan out into per-job detail requests.

**Backend hardening pass done and verified 2026-08-15** — full audit of backend
functionality; six defects found, five fixed, one left open (below). Test suite
went 34 → 77. Every fix verified live against the dev Postgres and real boards, not
just unit-tested. The theme across all of them: these failed *silently*, the same
shape as the 07-18 match-creation bug.

- **`MatchScorer` silently ignored symbol-edged keywords.** `containsWord` wrapped
  the keyword in `\b...\b`, and a `\b` can never be satisfied when the keyword's
  first or last character isn't a word char — so `C++`, `C#` and `.NET` matched
  **nothing**. A .NET or C++ user got an empty feed with no error anywhere. Fix:
  assert a boundary only on the side that actually ends in a word char
  (`(?<!\w)` / `(?!\w)` applied conditionally). Intended side effect: `.NET` now
  also matches `ASP.NET`, `C++` matches `C/C++` — what a job seeker means. Proven
  by reverting the fix: exactly the 5 symbol tests fail, the other 24 pass on both.
- **No HTTP timeouts on any ATS call.** `WebClientConfig` set only
  `maxInMemorySize`, and every fetcher `.block()`s. A board that accepted the TCP
  connection and never answered parked the scheduler thread forever and no later
  company was ever polled again. Now connect 10s / response 60s via `jobx.http.*`.
- **Fetch failure was indistinguishable from "no new jobs".** Every fetcher
  swallowed its own exception and returned an empty list while the scheduler still
  stamped `last_fetched_at` — a board 404ing for a week read as "checked just now,
  nothing new". Now: new `AtsFetchException`; `AtsFetcher.fetch` **throws** instead
  of swallowing (an empty list means the board genuinely is empty; a missing `jobs`
  array, or Lever's object-shaped error body, is a failure); migration
  `V3__add_fetch_health.sql` adds `last_fetch_status` + `last_fetch_error`;
  `WatchedCompanyResponse` exposes `lastFetchStatus`, with the raw cause kept
  server-side per `V1_IMPROVEMENTS.md`. This closes the P1 "Watchlist fetch health"
  item and is what the mockup's "Refresh issue" state renders from.
- **`fetchAllCompanies` wrapped every company's HTTP calls in one transaction.**
  Now non-transactional, calling `fetchCompany` through its own proxy
  (`ObjectProvider<FetchScheduler>` — self-invocation would skip `@Transactional`),
  so each company commits independently and a mid-cycle failure can't roll back
  companies already done. Satisfies the V1 acceptance item "a fetch error for one
  company never stops the next" (verified live: bogus board recorded FAILED while
  all six real boards recorded SUCCESS in the same cycle).
- **Workable: one transient detail-call failure poisoned a job permanently.** The
  job was persisted with a null description, and the N+1 guard then skipped it on
  every later cycle *because it now existed* — so it could never be repaired. Now
  the job is skipped on detail failure, stays absent, and the next cycle retries it.
  Deliberate trade-off: a posting whose detail endpoint is durably broken stays
  invisible rather than appearing with a wrong score.
- **No CORS configuration at all** — a hard step-5 blocker, since the Angular app is
  a separate origin. `SecurityConfig` now has an explicit allow-list
  (`jobx.cors.allowed-origins`, default `http://localhost:4200`, never `*`;
  `allowCredentials` false because auth is a bearer token the app attaches itself).
  `RateLimitFilter` now skips `OPTIONS` so a browser preflight can't spend a user's
  login budget.

**API changes the Angular build must handle:**
- `POST /watchlist/{id}/fetch` can now return **502** when the board is unreachable
  (it previously returned a cheerful `200 {newJobs: 0}`). 404/409/429 unchanged.
- `WatchedCompanyResponse` gained `lastFetchStatus`: `SUCCESS` / `FAILED` / `null`
  (never checked yet).
- `FetchScheduler.FetchResult` is now `(newJobs, newMatchesForOwner, failed)` —
  construct via `FetchResult.success(...)` / `FetchResult.failure()`.

**OPEN DEFECT — decide before a second user is onboarded.** `jobs.company_id`
references `watched_companies(id)`, so jobs are keyed per *watch row*, not per
company — while `scoreForAllWatchers` fans each new job out to every user watching
the same platform + token. With two users watching Razorpay, every posting is
stored twice and **each user gets two matches for it**. Invisible so far only
because all verification used a single user. Note this contradicts the "Job rows
are shared/global per company" line in the Data model section below: that line
describes the intent, the schema does something else. Two candidate fixes:
- **A (contained, ~10 lines, no migration):** drop the cross-user fan-out — each
  watch row scores only for its own owner. Fixes the duplicate feed completely;
  leaves N watchers = N stored copies of a board and N fetches per cycle.
- **B (structural):** a real `companies` table keyed by `(ats_platform,
  board_token)`, `jobs` hanging off it, `watched_companies` referencing it. Matches
  the documented model, also removes the redundant fetching and the cascade where
  one user deleting their watch wipes jobs another user's matches point at. Needs a
  migration that collapses existing duplicate jobs and repoints their matches.
Recommendation on the table is **B**; not started, pending Abhisek's call.

**Known gaps found 2026-08-15, deliberately not fixed** (recorded so they aren't
re-discovered): `MatchStatus` has no `SAVED`, but the mockup has a "Saved" filter
pill; `MatchResponse` still lacks `location`/`platformPostedAt`/description excerpt
(V1 P1 item); `GET /matches` returns everything including `DISMISSED`, unpaginated,
and relies on `open-in-view` for lazy `job.company` (N+1 per feed load);
`AuthController.login` skips bcrypt for unknown emails, so response timing still
distinguishes registered emails; `POST /watchlist` accepts
`atsPlatform: UNSUPPORTED` and sets it ACTIVE; fetchers use `asText("")` for the
NOT NULL `title`/`apply_url` columns rather than skipping malformed records.

**CURRENT FOCUS (2026-08-02): step 5, Angular dashboard** — next up now that step 3
and the backend P0 slice are closed. The remaining P0 items in `V1_IMPROVEMENTS.md`
(empty/error states, feed search/sort/filter, save-apply flow) are Angular work —
treat them as step 5's acceptance criteria. **Mockup ambiguity resolved 2026-08-02:**
the canonical UX target is now a mockup image (referred to as `MockUp`, not committed
to this repo — shared via claude.ai chat/project knowledge). It replaces both
`dashboard-mockup.html` and `v1-improvements-wireframes.html` as the visual/layout
reference. **Theme decision also made 2026-08-02:** build both a light and a dark
theme, user-toggleable — see "UI reference" below.

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

**All four platforms are now implemented and live-verified (2026-08-02).** Verified
field-level details (JSON shapes, date formats, Workable's two-call design, per-board
quirks, dead board tokens) are in `docs/ats-api-reference.md` — read that file before
touching any fetcher code, not this one.

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

**Fixed 2026-07-18:** matching uses word-boundary matching, not plain substring — "Java" no longer matches inside "JavaScript" (see Implementation status above for the full story, including why this same bug was zeroing out the `matches` table entirely via `excludeWords`).

**Amended 2026-08-15:** the 07-18 fix used `\bword\b`, which silently matched
*nothing* for any keyword whose first or last character isn't a word character —
`C++`, `C#`, `.NET`. `containsWord` now applies a boundary only on the side that
ends in a word char, so those work while "Java"/"JavaScript" stays correctly
separated. Full story in Implementation status above. `MatchScorerTest` (29 tests)
pins down every rule in this section — change the scoring rules and it will tell
you; that suite is the guard against a third silent-matching bug.

## Data model

```
User            (id, email, password_hash, role, created_at)
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

**Intent:** `Job` rows are shared/global per company; `Match` rows are the per-user
scored view, recomputed by running each user's `FilterProfile` against new `Job`
rows after each poll.

**⚠️ The implementation does NOT do this (found 2026-08-15, still open).**
`jobs.company_id` references `watched_companies(id)`, so a Job belongs to one
user's watch row, not to a company — while `FetchScheduler.scoreForAllWatchers`
still fans each new job out to every user watching the same platform + token. Two
users watching the same board therefore get duplicate Job rows and duplicate
Matches. Don't trust the "shared/global" line above when reading fetch/scoring
code until this is reconciled; see the OPEN DEFECT entry in Implementation status
for the two candidate fixes.

Given per-company `metadata` inconsistency on Greenhouse, don't add strongly-typed
columns for ATS-specific fields — store as unstructured `raw_metadata` JSON if kept at
all, and don't feed it into `MatchScorer` (which only needs title, description,
location, experience range).

## UI reference (updated 2026-08-02 — supersedes prior mockup references, DECIDED)

**Canonical mockup is now a single static image, not code.** Shared directly in
chat/project knowledge, filename `MockUp`, not checked into this repo. It shows the
light-theme version and depicts:

- Left sidebar: `jobx` wordmark, nav items Dashboard / Matches / Watchlist / Profile,
  collapse control.
- Header: "Good morning, {name}" greeting + subhead, "Add company" primary button,
  user avatar menu.
- Search/filter bar: free-text search, view-mode pills (All matches / New / Saved /
  Applied), sort dropdown ("Newest").
- "Top matches" feed: cards per job with company logo, job title, company name +
  verified badge, location/work-mode tag, skill-tag chips, a circular match-% ring
  (color shifts green→yellow as score drops, e.g. 94/89/82), a "Matched key skills"
  line, "View details" link, and a Save / Mark applied / Dismiss action row.
- Right rail: "Your search preferences" panel (Roles, Keywords, Experience) with an
  Edit affordance; a profile-completeness ring; a "Watchlist health" panel listing
  watched companies with last-checked status (including a "Refresh issue" warning
  state); an "Add more companies" CTA card.

**Theme: DECIDED 2026-08-02 — ship both light and dark, user-toggleable.** The
`MockUp` image is the light-theme spec for the layout/components above.
`dashboard-mockup.html` (dark theme; also the file whose `<script>` has the proven JS
port of `MatchScorer.java` — that scoring logic is still verified and should still be
ported regardless of theme) and `jobx-dashboard.html` in project knowledge supply the
dark-theme color tokens for the same components. Practical implications for the
Angular build:
- Structure components/CSS around theme-able tokens (CSS custom properties or
  Angular's theming approach) from the start — don't hardcode light-theme colors and
  retrofit dark later.
- Toggle mechanism (header icon vs. profile-menu setting vs. system-preference
  detection) is not yet specified — pick a default approach when building step 5 and
  flag it here if it needs revisiting.
- Persisting the user's theme choice (e.g. per-user preference vs. local device only)
  is also unspecified — reasonable default is local device (localStorage), no backend
  field needed yet.

## Build order

Original order paired Greenhouse + Lever as step 2's first milestone; now deliberately
narrowed to Greenhouse-only first (see ATS section above) — re-confirm with user before
resuming multi-ATS work.

1. Verify real ATS API responses by hand — **Greenhouse: done, scope closed.**
   Lever/Ashby/Workable verification is no longer a separate step here — moved into
   step 3, verified live as each fetcher is built instead of upfront.
2. Core entities + Greenhouse fetcher + MatchScorer as a Spring service, no auth yet.
   **Done** — fetch + matching loop, real CRUD (`/watchlist`, `/matches`); temp `/dev/*`
   endpoints retired.
3. More fetchers: Ashby, Workable, Lever, then harder ones if time allows. **Done and
   verified 2026-08-02** (order actually built: Ashby → Lever → Workable) — each
   verified against a live board as built, per-platform notes in
   `docs/ats-api-reference.md`. Harder platforms (Rippling, Recruitee, Workday) still
   out of scope.
4. Auth (Spring Security) + multi-tenant data, before handing app to other test users.
   **Done and verified 2026-07-30** — see Implementation status above for the full
   design (JWT access-token-only, `role` column added early, `/dev/**` intentionally
   still permitAll).
5. Angular dashboard wired to the live backend, matching the mockup, with both light
   and dark themes (see UI reference above). **🎯 CURRENT FOCUS.**
6. Tailoring feature (Spring AI + LLM), fast-follow after discovery validated.
7. Astro layer for SEO/public pages, only after product validated with real users.

## Open/unvalidated assumptions

- **Monetization: confirmed as of 2026-07-12** — user has verified PMF, Indian job
  seekers will pay. (Previously flagged as unvalidated; update if this changes.)
- ATS API shapes: Greenhouse verified. Lever, Ashby, Workable documented and partially
  spot-checked but not verified against this project's actual watchlist companies —
  verification for these now happens per-fetcher in step 3, not as a standalone step.
- **Shared app with logins means real auth security work — done as of 2026-07-30**: BCrypt
  hashing (never plaintext), JWT secret sourced from `JOBX_JWT_SECRET` env var (never
  committed), no server-side session state. No refresh-token revocation yet — accepted
  tradeoff for v1, revisit if a logout/revocation need shows up.
- **Theme toggle mechanics** (how the user switches, where the choice persists) are
  unspecified beyond the DECIDED light+dark requirement above — resolve during step 5
  implementation, not blocking.
