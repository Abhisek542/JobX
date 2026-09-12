
# Jobx — project context for Claude Code

Handoff doc from design work in claude.ai. Captures *decisions*, not just ideas —
read before writing code, don't re-litigate items marked DECIDED unless explicitly
asked to revisit. Detailed per-ATS API field notes live in `jobx-backend/docs/ats-api-reference.md`
(not loaded by default) — check that file before touching Lever/Ashby/Workable code.

## Implementation status (updated 2026-08-22)

Read this first — reflects actual verified backend progress, not design intent.

**Built and confirmed working:**
- Spring Boot scaffolded (Maven, Postgres via Docker), Flyway migrations ran, all 6 tables created.
- JPA entities: `User`, `FilterProfile`, `WatchedCompany`, `Job`, `Match` — multi-tenant `user_id` present from day one.
- `GreenhouseFetcher` fully implemented and verified against live Razorpay + PhonePe boards — real jobs fetched and deduped correctly.
- `FetchScheduler` runs every 30 min via `@Scheduled`, dedupes by `company_id + external_id`.
- `MatchScorer` ported to a Spring `@Service`, confirmed correct in isolation (test job scored 79 correctly via a temporary `/dev/test-score` endpoint).
- Real test data drove every verification below (FilterProfile for user
  `e373e792-5d4a-458f-98c1-3183eac63366`, watching Razorpay + PhonePe). **The dev
  database was truncated 2026-08-22** (see "Dev database reset" below) — every row
  count quoted in this file is historical; the DB is now empty.
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
duplicate-shortcode list rows, dead Postman board) are in `jobx-backend/docs/ats-api-reference.md`.
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

**Step 5 (Angular dashboard) built and live-verified 2026-08-15** — lives in the
sibling `jobx-frontend/` module, committed as `implementing-frontend` (ee1bcfb).
Closes the P0 Angular items from `V1_IMPROVEMENTS.md` (empty/error states, feed
search/sort/filter, save-apply flow).

- **Stack**: Angular 21, standalone components, signals + `OnPush`, **no NgRx**.
  Routes lazy-load behind `authGuard`; `guestGuard` keeps a signed-in user off
  login/register. `authInterceptor` attaches the bearer token on the way out,
  `errorInterceptor` maps the `ApiError` shape on the way back.
- **Layout**: `core/` (api clients · DTO models · interceptors · guards · stores) ·
  `features/` (dashboard · matches · watchlist · profile · auth) · `shared/`
  (layout · feed · rail · overlays · ui) · `styles/` (token SCSS).
- **One source of truth for the feed**: a single `signal` holds it, while filtered,
  searched, sorted, paged and the pill counts are all `computed`
  (`features/dashboard/feed.store.ts` + `feed-logic.ts`), so no two views of the
  feed can drift. Fixed order of operations: filter → search → sort → paginate.
  Pill counts always reflect the whole feed, never the current page.
- **Theme mechanics — the two items flagged "unspecified" in UI reference are now
  settled**: `data-theme` on `<html>`, toggled from the action bar, persisted to
  `localStorage` under `jobx-theme`, defaulting to `prefers-color-scheme` until
  the user chooses. Device-local, no backend field. Every colour resolves through
  a custom property in `styles/_tokens.scss` — a hardcoded colour in a component
  is a bug, and that rule is what makes the toggle total.
- **Pagination is client-side**, 10/page, URL-backed (`/dashboard?page=3`), resets
  to page 1 on any search/filter/sort change, and clamps when the list shrinks so
  dismissing the last card never lands on an empty page. It has to be client-side:
  `GET /matches` takes no query parameters. Don't build UI implying the server
  paginates until `GET /matches?page=&size=` actually exists.
- **`SEEN` means "the user explicitly saved this"; opening a card does NOT mutate
  status.** This resolves the "`MatchStatus` has no `SAVED`" gap listed above —
  Save maps to `SEEN`, and auto-marking on open would quietly pack the "Saved"
  filter with every role merely glanced at, making that filter a lie. Revisit if
  the backend ever gains a real bookmark flag.
- **Tests**: 37 pure-logic unit tests under **Vitest** — not Karma, which is
  deprecated in Angular 21 and no longer the `ng new` default. No DOM tests.
- **Verified live against the running backend**, not mocked: register → set filter
  profile → watch four real boards → fetch → 99 real matches scored. Confirmed
  save/applied/dismissed persist across a reload · `?page=N` survives refresh and
  resets on filter change · manual "Check now" renders 200 / 429 / 502 distinctly ·
  a duplicate company surfaces the 409 · a brand-new account gets the onboarding
  state rather than an error from the `GET /profile/filter` 404. **Not** visually
  verified: the ≤1280px and ≤900px breakpoints — the automation browser window
  could not be resized, so those media queries are ported verbatim from the mockup
  and have never been seen rendered.
- **Honesty constraints are enforced in the UI** (`jobx-frontend/docs/uiux_plan.md`
  §7) — the dashboard never invents data the API cannot back: no location, no
  description excerpt, no employer posting date, no verified badge, because
  `MatchResponse` carries none of them. "Found 3h ago" is `createdAt`, i.e. when
  *Jobx* first saw the role, and is never labelled as the employer's posting date.
  Company logos are initials on a hue derived from the name — no logo service is
  called. A failed board reads "Refresh issue · last tried 12m ago", not "last
  worked", because `FetchScheduler.recordFailure` stamps `lastFetchedAt` on failed
  attempts too, making that timestamp the last *attempt*.

**Dev database reset 2026-08-22** — `TRUNCATE users CASCADE` wiped all five test
accounts (17 watches, 648 jobs, 566 matches) to re-run the app end-to-end from a
clean slate. The cascade behaved exactly as `V1__create_schema.sql` declares:
`users` → `filter_profiles` / `matches` / `watched_companies` → `jobs`.
`flyway_schema_history` was deliberately left alone (3 migrations), so the app
restarts against the existing schema with no re-migration. A `pg_dump` was taken
first, but it lives in a session scratchpad outside the repo — treat it as gone.
Every row count quoted anywhere in this file is now historical.

**Feed grouped by company done and live-verified 2026-09-12.** Replaces
`Sort by: Company`, which is **removed** from `SortMode`. As a flat sort it
only interleaved cards alphabetically: it clustered a company's roles without
separating them, let the 10/page slice cut through the middle of a company,
ranked by a letter nobody cares about, and still could not answer "show me only
this company" (the only workaround was typing the name into free-text search).
- **Backend, two lines**: `MatchResponse` gains `companyId` and
  `WatchedCompanyResponse` gains `companyId`. Both read through an association
  the DTO already dereferenced, so neither costs a query. The second one matters
  because `WatchedCompanyResponse.id` is the **watch row** id — without a
  company id on both, the feed's groups could only be joined to board health by
  display name. No new endpoint; `GET /matches` is still param-less.
- **Grouping keys on `companyId`, never `companyName`** — the name is
  `Company.displayName` (first-adder-wins) and two boards can share one.
- **The grouped view paginates COMPANIES (5/page), not roles.** That is what
  guarantees a company's postings are never split across a page boundary. It
  reuses the generic `pageSlice`/`clampPage`/`pageNumbers` helpers, so `?page=N`,
  the clamp and the ellipses behave identically; the range label reads
  "Showing 1–5 of 6 companies".
- Entry point is a **List / By company** toggle in the toolbar, deliberately not
  a sixth status pill: grouping is a view mode that composes with any pill,
  Dismissed included (groups then fill with compact archive rows). In grouped
  mode the sort dropdown orders the *groups* — Best match / Newest / **A–Z**.
  A–Z survives only here: useless for interleaving cards, but the natural way to
  find one company among a screen of headers.
- Group state (collapsed set, grouped on/off, group order) is **session-only**,
  matching how the status pill and sort already behave — only `page` is
  URL-backed. A refresh therefore returns to the flat view.
- Live-verified in the browser against a 6-board / 51-match feed: groups render
  with real counts and board health, Collapse all gives a clean company index,
  group pagination pages 1–5 then 6 of 6, A–Z reorders and resets to page 1,
  dismissing inside a group drops its header count live (11 → 10), and the
  Dismissed pill while grouped yields one group of archive rows with Restore.
  Both themes checked. **Not** verified: the ≤1280px / ≤900px breakpoints — the
  automation browser window still cannot be resized (same limitation as
  2026-08-15).
- Tests: frontend 37 → 46 (`groupByCompany` + group-pagination suites in
  `feed-logic.spec.ts`, including the two-boards-one-display-name case and an
  expired match). Backend suite unchanged at 203 green — every DTO call site
  goes through the static `from` factory, so nothing else needed touching.

**Sign-out resets every per-user store, done 2026-09-12** (BUG_REPORT.md #1). Before
this, user A signing out and user B signing in on the same tab without a reload showed
B the feed, watchlist and filter profile of A: the stores are root singletons whose
`load()` is a no-op once `loaded`, and `AuthStore.clear()` only dropped the token.
- `AuthStore.clear()` is the single chokepoint for both sign-out paths (sidebar button
  and the 401 handler in `errorInterceptor`). It now calls `reset()` on `FeedStore`,
  `WatchlistStore`, `FilterProfileStore` and `UiStore` (overlays only), and
  `ToastService.clear()`. The collapsed-sidebar preference survives, like the theme.
- **Epoch guard, a rule for new store code:** each store holds a private `epoch` that
  `reset()` bumps. Every async `subscribe` callback captures it before the request and
  returns early if it changed. Without this, a response sent before sign-out lands after
  the reset and sets `loaded` again, recreating the bug. Any new store method that
  subscribes must follow the same pattern.
- Tests: frontend suite at 53 green. `core/services/session-reset.spec.ts` is the first TestBed
  suite (still no DOM), driving the real interceptors through `HttpTestingController`.
  Also fixed a stale helper in `util.spec.ts` that lacked `companyId` since the grouping
  change, which had stopped the whole frontend suite from compiling.

**CURRENT FOCUS (2026-08-22): step 5 is built and verified, and nothing is
mid-flight.** What remains are backend items the dashboard currently works around.
None are started; all are pending Abhisek's call:
- **The OPEN DEFECT above (fix A vs fix B)** — the one item with a deadline
  attached: it has to be settled *before* a second user is onboarded, and the
  08-22 truncation just reset the app to zero users, so that window is open now.
- Backend gaps the frontend deliberately papers over, each recorded in
  `jobx-frontend/docs/uiux_plan.md` §12: no `GET /matches/{id}` detail endpoint
  (the drawer admits this on screen), no `location`/`platformPostedAt` on
  `MatchResponse`, `GET /matches` returning `DISMISSED` rows unpaginated, and no
  real `SAVED` status.
- Step 6 (tailoring) is still the next *feature* in the build order.

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
quirks, dead board tokens) are in `jobx-backend/docs/ats-api-reference.md` — read that file before
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

## UI reference (updated 2026-08-22 — supersedes the 2026-08-02 image reference, DECIDED)

**The canonical mockup is committed code again, not a static image.** The approved
visual spec is `jobx-frontend/docs/jobx-focused-feed-mockup.html`, and it is
**frozen** — do not edit it to match the app. It supersedes the `MockUp` image this
section pointed at on 2026-08-02, which had in turn superseded
`dashboard-mockup.html` and `v1-improvements-wireframes.html`.

The build plan derived from it is `jobx-frontend/docs/uiux_plan.md` — read that
before touching dashboard code. It holds the locked design decisions, the verified
API contract, the empty/error-state matrix and the honesty constraints.
`jobx-frontend/README.md` records what was actually built and where it deviates
from the plan.

**Direction: focused feed**, not the Command Center v3 exploration.
`jobx-backend/docs/UIUX_guide.md` is **stale** in §2, §3, §5, §8 and §10 — it still
describes Command Center v3. Where it disagrees with `uiux_plan.md`, the plan wins.

**Three approved deltas from the frozen mockup** (`uiux_plan.md` §0). The app is
right and the mockup is knowingly out of date in exactly these places — if you
find yourself "fixing" the app back toward the mockup here, stop:

| # | Mockup shows | App does |
|---|---|---|
| 1 | Three rail panels, last an "Add more companies" CTA | **Two** panels: Search preferences, Watchlist health |
| 2 | "Good evening, {name}" greeting + subhead + demo chip | **No greeting** — a slim right-aligned action bar (theme toggle + "Add company") |
| 3 | Every match in one unbroken list | **Numbered pagination**, 10/page |

Everything else in the mockup — layout, spacing, tokens, card anatomy, score ring,
drawer, modals, empty states, toasts — is the spec, and its `visible()`, `band()`,
`logoStyle()` and `relTime()` functions were ported rather than reinvented.

**What the dashboard actually renders today**: a left sidebar (`Jobx` wordmark —
dark text, blue `x`, on a white `--panel` surface in light mode) with Dashboard /
Matches / Watchlist / Profile · the slim action bar · a search + status-pill + sort
toolbar · the match feed (initials logo, job title, company, matched-skill chips, a
circular score ring shifting green→yellow as the score drops, a Save / Mark applied
/ Dismiss row, and "View details" opening a drawer) · and a two-panel right rail
(Search preferences with an Edit affordance; Watchlist health listing each watched
company's last-checked status, including the "Refresh issue" state). No metric
cards — the pill counts and the rail carry the numbers.

**Theme: light + dark, DECIDED 2026-08-02; mechanics settled 2026-08-15** —
`data-theme` on `<html>`, toggle in the action bar, persisted to `localStorage`
(`jobx-theme`), defaulting to `prefers-color-scheme`. Device-local, no backend
field needed. Every colour, radius and shadow resolves through a custom property
in `jobx-frontend/src/styles/_tokens.scss`.

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
   `jobx-backend/docs/ats-api-reference.md`. Harder platforms (Rippling, Recruitee, Workday) still
   out of scope.
4. Auth (Spring Security) + multi-tenant data, before handing app to other test users.
   **Done and verified 2026-07-30** — see Implementation status above for the full
   design (JWT access-token-only, `role` column added early, `/dev/**` intentionally
   still permitAll).
5. Angular dashboard wired to the live backend, matching the mockup, with both light
   and dark themes (see UI reference above). **Done and verified 2026-08-15** — see
   Implementation status above; `jobx-frontend/docs/uiux_plan.md` is the build plan
   and records the three approved deltas from the frozen mockup.
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
