
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
- **V4 (2026-08-23):** response JSON shapes are unchanged, but `POST
  /watchlist/{id}/fetch` inside the (now board-wide) cooldown returns **200
  `{newJobs: 0, newMatches: 0}`** after a recent successful check instead of 429
  (429 still fires after a recent FAILED attempt) — the dashboard's existing
  "no new roles" rendering already covers it. `companyName` everywhere is the
  canonical shared name (first adder's), not necessarily what this user typed.
  Adding a watch on a board another user already watches yields matches
  immediately (backfill) — no fetch needed first.

**FIXED (2026-08-23) — the shared-companies rework (fix B), migration `V4__companies_table.sql`.**
The former OPEN DEFECT: `jobs.company_id` referenced `watched_companies(id)`, so
jobs were keyed per *watch row* while `scoreForAllWatchers` fanned each new job
out to every user watching the same platform + token — N watchers meant N fetches
per cycle, N stored copies of every posting, N² matches, and one user's unwatch
cascading away other users' matches (APPLIED state included). Abhisek chose
**fix B (structural)** over fix A, with three sub-decisions: canonical company
display name (first adder's name wins, later names discarded), manual "Check now"
during a shared cooldown returns **200 with zeros** after a recent SUCCESS (429
only after a recent FAILED), and an **additive V4** migration rather than a V1
rewrite. What changed:
- New `Company` entity/table keyed `UNIQUE (ats_platform, board_token)`; `jobs`
  re-parented onto it; `watched_companies` slimmed to a pure join row
  (user, company, status) with `UNIQUE (user_id, company_id)`. Fetch health
  (`last_fetched_at`/`last_fetch_status`/`last_fetch_error`) moved to `companies` —
  fetching is a board property now, shared by all watchers.
- `FetchScheduler` iterates `CompanyRepository.findAllWithActiveWatchers()` (one
  fetch per board per cycle) and `fetchCompany(Company, User requester)` counts
  owner matches for the manual endpoint. Scoring extracted to **`MatchingService`**
  (`scoreForActiveWatchers` / `backfillForWatcher` / `scoreAndSave`) — the only
  place Match rows are created. The watcher query is ACTIVE-only, which finally
  makes PAUSED actually pause (pre-V4 the `findAll()` fan-out ignored status).
- `POST /watchlist` get-or-creates the shared Company and **backfills** the new
  watcher against jobs the board already has (pre-V4 that happened by accident via
  per-row re-fetch; without explicit backfill a second watcher's feed stays empty).
  `DELETE /watchlist/{id}` deletes only the caller's matches explicitly
  (`MatchRepository.deleteByUserAndCompany`) — nothing cascades from a watch row
  any more. Orphaned companies (all watchers gone) are deliberately kept: the
  scheduler skips them, and re-adding starts with a warm job history.
- V4's collapse logic dedupes existing job copies (survivor = earliest
  `first_seen_at`) and collapses each user's matches across copies keeping the
  highest-precedence status (APPLIED > SEEN > DISMISSED > NEW). The migration
  needs a transaction (ON COMMIT DROP temp tables) — Flyway provides one; use
  `psql -1` when applying by hand.
- **Verified three ways**: (1) dry-run against a scratch copy of the real dev DB —
  the defect was live (2 users on the Razorpay board, all 25 postings stored
  twice): 643 → 618 jobs, all 158 matches survived, zero orphans; (2) the seeded
  collision fixture `db/seed/migration-collapse-fixture.sql` (APPLIED-vs-NEW on
  duplicate copies) — every expectation in that file held; (3) live E2E on the
  migrated dev DB: cycle logs "13 companies" for 14 watch rows, a third user
  watching Razorpay under a different typed name got the canonical name + an
  instantly backfilled feed with zero re-fetch, "Check now" inside the shared
  cooldown returned 200-with-zeros, and their unwatch removed only their own
  matches (other users' 158 intact, jobs still 618). Pre-migration `pg_dump` is in
  a session scratchpad (`jobx-pre-v4-backup.sql`) — treat as ephemeral.
- Test suite 77 → 93 (`FetchSchedulerSharedJobsTest`, `MatchingServiceTest`,
  `WatchlistControllerSharedCompanyTest`, reworked health/fetch tests). Gotcha
  worth remembering: Hibernate rejected `@Query` enum literals written as
  `com.jobx.entity.WatchedCompany.CompanyStatus.ACTIVE` at *startup*, not in unit
  tests (mocked repos never validate JPQL) — status enums are now bound as
  parameters behind `default` methods. JPQL mistakes only surface on app start.

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

**Profile-save rescore done and live-verified 2026-08-23** — closes the
"PUT /profile/filter doesn't rescore" gap, found live the same day: user
`test1@pilot.com` had 352 jobs, a valid profile and **0 matches**, because the
profile was saved 3 minutes *after* Razorpay/Phonepe were first fetched and
matching only ever ran on new jobs and on watch-add backfill (the third
silent-zero-feed bug, same shape as 07-18 and the C++ one). Two parts:
- `MatchingService.rescoreForWatcher(user, profile)` (`@Transactional`), called
  from `FilterProfileController.upsert` after every save. Per job of each
  **ACTIVE** watch: passes+no match → create NEW; passes+match → refresh
  `score`/`matchedKeywords` keeping status and `createdAt`; fails+NEW → delete;
  fails+SEEN/APPLIED/DISMISSED → **kept untouched** (user history, stale score
  and all). PAUSED watches skipped, same rule as the fetch fan-out. New derived
  query `MatchRepository.findByUserAndJob_Company`. PUT's response shape is
  unchanged — counts are only logged.
- Live-verified on 8081 against the dev DB: fresh user → watch Razorpay (0
  matches, bug reproduced) → PUT profile → **4 matches instantly**, no fetch;
  narrowed the profile → 3 stale NEW matches deleted, survivor's score
  refreshed 65 → 100. Test suite 93 → 102 (6 rescore tests in
  `MatchingServiceTest`, new `FilterProfileControllerTest`).
- The same investigation showed the exclude words `lead`/`sales`/`support`
  hard-excluded **345 of 352** real jobs ("support" alone appears in 326
  descriptions) — working as designed, but generic English excludes gut the
  feed; a UX warning for high-kill excludes may be worth a future P2.

**FIXED (2026-08-29) — matches created but never rendered.** Reported as "new
user adds a company and the matching jobs never show up". The backend was
correct all along: the DB proved `POST /watchlist` backfilled 6 matches **90 ms
after** the watch row was created. The frontend never reloaded the feed.
`FeedStore` is a root singleton whose `load()` no-ops once `loaded` is true, and
the only forced reloads were unwatch, the error-retry button, and `checkNow`
*when `newMatches > 0`* — but a backfill is not a fetch and never counts toward
`newMatches`, so for a board another user already watches (`200` with zeros from
the shared cooldown) nothing ever refreshed. Fixes:
- `WatchlistStore.add()` now reloads the feed. This is the actual bug.
- `checkNow(company, {quietWhenNothingNew})` suppresses only the "no new roles"
  toast for the auto-check fired right after an add — it otherwise contradicts
  the backfilled matches landing in the feed at that same moment.
- The add-company modal branches on `lastFetchStatus === null` so it only
  promises "running the first check now" for a board Jobx has genuinely never
  checked.
- `FilterProfileStore.save()` reloads too — `PUT /profile/filter` runs
  `rescoreForWatcher` server-side and no frontend path was picking that up.
- **Also fixed, uncommitted in the working tree at the time:** the manual-fetch
  cooldown in `WatchlistController.fetchNow` had `if (sinceMs < manualCooldownMs)`
  commented out with its inner branches left live, so every already-fetched board
  returned 200-with-zeros *forever* (never re-fetching) and every FAILED board
  threw 429 with a negative retry time. `WatchlistControllerFetchTest` was
  catching this — it errored with the broken version and passes 7/7 with the
  guard restored.

**PhonePe left Greenhouse (found 2026-08-29).** Token `phonepe` 404s at the API
and on the board pages; it genuinely worked on 2026-08-23 (68 jobs stored with
`job-boards.greenhouse.io/phonepe` apply URLs). They are now on **SmartRecruiters
as `PHONEPELIMITED`**. The dead `GREENHOUSE/phonepe` company row was deleted;
re-add PhonePe through the UI as SMARTRECRUITERS. A board dying is normal
attrition — the 502 + "Refresh issue" health state handled it exactly right.

**Dev database jobs truncated 2026-08-29** on Abhisek's instruction: `TRUNCATE
jobs CASCADE` (393 jobs → 0, cascading 133 matches → 0), the dead PhonePe row
deleted (9 → 6 watch rows), and every company's fetch health cleared so boards
refill from a clean slate. Users, profiles and watches were kept. A `pg_dump`
was taken to a session scratchpad first — treat it as gone.

**Job TTL done and live-verified 2026-08-29 (migration `V5__job_ttl.sql`).**
Rationale is the product thesis: Jobx exists to get a user onto the careers page
before the aggregators, so a posting past the window has already lost that race
and storing it is waste. **Six days, clocked on the ATS's own posting date**
(`platform_posted_at`, falling back to the NOT NULL `first_seen_at`).
`jobx.retention.job-ttl-days: 6`, swept daily by `JobRetentionSweeper`.

The naive version of this is a trap, and the design is mostly about avoiding it.
Dedup is `existsByCompanyAndExternalId` against the jobs table, so deleting a
posting that is **still listed on the board** means the next poll re-inserts it
as brand new — re-scored, re-notified, swept again the next night, forever, and
each round wiped APPLIED state through `matches.job_id ON DELETE CASCADE`. So
expiry is two things:
- an **`expired_jobs` tombstone** (company + external_id + posted_at, nothing
  heavy) that outlives the job row and carries the dedup decision. `FetchScheduler`
  loads the set once per board — not per job; Bosch is 4,774 postings — and skips
  tombstoned ids.
- `matches.job_id` is now **NULLABLE with ON DELETE SET NULL**. Abhisek's call:
  NEW and DISMISSED matches are deleted with the posting; **SEEN (saved) and
  APPLIED are kept** as the user's own history, losing only their job pointer.
  That is why `matches` now denormalizes `company_id` (a real FK — companies
  outlive jobs, and unwatch cleanup must still reach expired rows), `job_title`
  and `apply_url`, and stamps `job_expired_at`. `MatchResponse` gained
  `expiredAt` and its `jobId` can now be null; the card renders "No longer
  listed · closed 2d ago" rather than presenting a dead posting as live.
  **Anything reading `match.getJob()` must null-check it.**
- Live-verified end to end: 6 jobs aged to 9 days → sweep logged "6 jobs deleted,
  4 matches dropped, 2 kept as saved/applied history", 6 tombstones written, the
  APPLIED and SEEN rows survived with `job_id` null and `job_expired_at` set —
  and **re-fetching the board, all 6 postings still live on it, returned
  `newJobs: 0`**. That last check is the one that matters.

**SmartRecruiters fetcher done and live-verified 2026-08-29.** Fifth platform;
`AtsPlatform.SMARTRECRUITERS`, registry-routed like the rest. Two-call design
(list has neither description nor apply URL). Verified against PhonePe
(6 postings, descriptions 2.4k–5.4k chars, experience parsed, clean
`postingUrl`s) and Bosch (4,774, the pagination case). Two quirks drove the
design and are written up in `docs/ats-api-reference.md`:
- **`limit` is silently capped at 100** — ask for 500, get 100 and a cheerful
  `"limit":100`. One call against Bosch would return 2% of the board and look
  successful. Pages on `offset`.
- **A bogus token returns `200 {"totalFound":0,"content":[]}`, never 404** —
  alone among the five platforms, which breaks `AtsFetcher`'s "empty means
  genuinely empty" contract. New `AtsFetcher.validateBoard` (default no-op,
  overridden only here) runs from `POST /watchlist` when creating a company
  nobody watches yet and rejects a typo with **400**; it is deliberately NOT
  enforced in `fetch`, so a real board with no current openings keeps working.
  Verified: `PHONEPELIMTED` → 400 with no junk company row left behind,
  `PHONEPELIMITED` → 201 then 6 jobs / 6 matches.

Test suite 102 → 120 (`JobRetentionSweeperTest`, `SmartRecruitersFetcherTest`,
two new tombstone cases in `FetchSchedulerSharedJobsTest`).

**Add-company resolution done and live-verified 2026-09-06 (migration
`V6__unsupported_board_requests.sql`).** The form asked for `companyName`,
`atsPlatform` and `boardToken`; a user knows the first. "ATS platform" means
nothing to a job seeker, and the token is opaque, **case-sensitive**, and often
not the company name (`razorpaysoftwareprivatelimited`, `Sprinto`,
`PHONEPELIMITED`). Now one field takes a name, a website or a careers link, and
`CompanyResolver` (new `com.jobx.resolve` package) works the board out through
four strategies, cheapest first, stopping at the first that yields anything:

1. **CATALOG** — `companies` has been a registry of proven boards since V4, so a
   typeahead over it (`GET /companies/search`) needs no network and no guessing.
   Seeded from the verified pairs in `docs/ats-test-data.md` via
   `db/seed/company-catalog.sql` (orphan rows: the scheduler skips boards with no
   ACTIVE watcher, so they cost nothing until someone adds one).
2. **URL** — the input is itself an ATS link; `AtsUrlParser` reads the token out.
3. **SNIFF** — `SafeUrlFetcher` GETs the careers page and the same patterns run
   over the HTML.
4. **PROBE** — `SlugCandidates` derives tokens from the domain label or name (in
   both cases, since Lever/Ashby 404 on the wrong one), and `BoardProbe` asks the
   APIs in parallel which is real.

**3 and 4 are complementary, not redundant** — measured live, each alone resolved
about half the companies tried, together all of them. Sniffing is the only thing
that recovers Razorpay's token; probing is the only thing that finds Atlan and
FamPay, whose careers pages render the board in JavaScript. Atlan's page names
Ashby only in a CSP header, which is kept as a **platform hint** and narrows the
probe from five platforms to one. **Regional hosts are not optional**: Groww
links `job-boards.eu.greenhouse.io/groww`.

- **The rule that makes guessing safe: a board counts only if it has ≥1 live
  role.** Verified live, `apply.workable.com/api/v1/widget/accounts/razorpay`
  returns `{"name":"Razorpay","jobs":[]}` — and the same for groww, atlan, meesho
  and sprinto, none of them Workable customers. The echoed *name* makes a wrong
  token look confirmed, so only a live posting is trusted. That still cannot tell
  a "porter" board from a different Porter, which is why every candidate is shown
  with **real job titles** and confirmed by a human before anything is written.
  Resolution is read-only; `POST /watchlist` is **unchanged** and is what watches
  a board, so the manual path, its tests and the `docs/ats-test-data.md` curl
  workflow all still work.
- **`AtsFetcher` gained `previewBoard`** (one cheap list call → display name where
  the API gives one, job count, sample titles), implemented by all five fetchers.
  `validateBoard` is now a default that rejects a zero count — so **every**
  platform validates at add time instead of only SmartRecruiters. Two
  silent-acceptance bugs closed, both verified as live 400s: `WORKABLE`/`razorpay`
  (the ghost account) and any bogus Greenhouse/Lever/Ashby token, all of which
  used to sit on the watchlist looking healthy and never produce a job.
- **SSRF**: `SafeUrlFetcher` fetches only the pasted URL (no crawling), http/https
  only, rejects every non-public address for **every** resolved IP, and follows
  redirects **by hand** re-checking each hop — leaving Netty's own redirect
  handling on would let a public URL bounce into 127.0.0.1 or a metadata
  endpoint. `PrivateAddressGuard` covers what Java's predicates miss (IPv6
  `fc00::/7`, `0.0.0.0/8`, CGNAT). DNS rebinding is a known, accepted residual.
  `RateLimitFilter` now carries per-prefix budgets and throttles
  `/watchlist/resolve` separately from `/auth/*`.
- **Dead ends are recorded**, not just refused: `POST /watchlist/unsupported`
  writes to `unsupported_board_requests` so "which ATS next" is answered by demand
  (Workday/Rippling/BambooHR still get the honest "unsupported" state). The modal
  keeps manual entry behind a collapsed **Advanced** section.
- Frontend: `add-company-modal.ts` is a four-step rewrite (input → resolving →
  confirm → dead-end) with a debounced catalog typeahead. The 2026-08-29
  feed-reload behaviour on success is preserved verbatim.
- **Live-verified in the browser end to end**: paste `razorpay.com/jobs` → card
  shows "Greenhouse · 24 open roles" with three real titles and "Found on their
  careers page" → Watch → feed reloads with 7 scored matches. Also confirmed:
  typeahead on "Atl", `Sprinto` resolving to the capital-S token (30 roles),
  Atlan's hint-narrowed probe, the Zoho dead end recording a row, and 400s for
  `127.0.0.1`, `169.254.169.254` and `file://`. Two defects found *by* that live
  pass and fixed: duplicate candidates when a board answers to two spellings
  (Ashby serves Atlan at `atlan` and `Atlan`), and catalog rows with no stored
  jobs showing "0 open roles" — they now fall back to a live preview.
- Test suite 120 → 203. New: `AtsUrlParserTest` (real careers-page HTML fixtures
  for Razorpay/Groww/Atlan), `SlugCandidatesTest`, `SafeUrlFetcherTest`,
  `BoardProbeTest`, `CompanyResolverTest`, `GreenhouseFetcherTest`, plus
  `previewBoard` cases on every fetcher.
- Also fixed in passing: `getOrCreateCompany` now falls back to a
  case-insensitive token lookup, so a user typing the other casing joins the
  existing board instead of creating a second `companies` row for it.

**CURRENT FOCUS (2026-09-06): nothing is mid-flight.** Add-company resolution is
done and live-verified (above), as are the feed-reload fix, the six-day job TTL
and the SmartRecruiters fetcher.
What remains are backend items the dashboard currently works around. None are
started; all are pending Abhisek's call:
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
- SmartRecruiters: `api.smartrecruiters.com/v1/companies/{id}/postings` (public, two-call;
  list is capped at 100/page and a bogus id returns an empty 200, not a 404)
- Trickier/later: Rippling, Recruitee, BambooHR, Workday — no clean public API, mark "portal unsupported" rather than faking support

**All five platforms are implemented and live-verified** (the first four 2026-08-02,
SmartRecruiters 2026-08-29). Verified field-level details (JSON shapes, date formats,
the two-call designs, per-board quirks, dead board tokens) are in
`jobx-backend/docs/ats-api-reference.md` — read that file before touching any fetcher
code, not this one.

Board tokens rot: PhonePe's Greenhouse board went from 68 live jobs to a hard 404
in six days when they moved to SmartRecruiters. Treat a sudden FAILED board as
"check where the company's careers page points now", not as a bug in the fetcher.

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
Company         (id, ats_platform, board_token, display_name,        ← since V4
                 last_fetched_at, last_fetch_status, last_fetch_error)
WatchedCompany  (id, user_id, company_id, status)   ← join row since V4
Job             (id, company_id → companies, external_id, title, description,
                 location, exp_min, exp_max, apply_url, posted_at, ats_platform)
ExpiredJob      (id, company_id, external_id, posted_at, expired_at)  ← since V5
                 tombstone: a job the TTL dropped, so a fetch can't re-add it
Match           (id, user_id, job_id?, company_id, job_title, apply_url,
                 score, matched_keywords[], created_at, job_expired_at?,
                 status: new/seen/applied/dismissed)      ← job_id NULLABLE since V5
```

Actual implemented `jobs` schema differs slightly: `platform_posted_at` (ATS's own
timestamp, display only) vs `first_seen_at` (when Jobx first observed the job — the
real sort/alert field). `raw_json` jsonb escape hatch. `Job.company` is a real
`@ManyToOne` — to `Company` since V4 (pre-V4 it pointed at `WatchedCompany`).

Since V5, `platform_posted_at` has a second job: it is the **clock for the six-day
retention TTL** (falling back to `first_seen_at` when the board publishes no date).
It is still never used for feed sorting.

`Job` rows are shared/global per company (`companies` keyed
`(ats_platform, board_token)`); `Match` rows are the per-user scored view,
created by `MatchingService` when a poll finds a new job (scored for every
ACTIVE watcher) or when a user adds a watch on an already-populated board
(backfill). Since 2026-08-23 the implementation actually does this — the
per-watch-row jobs defect found 2026-08-15 was fixed by the V4 shared-companies
rework; see the FIXED 2026-08-23 entry in Implementation status for the full
story and verification.

A `Match` can now **outlive its `Job`** (V5 TTL): `job_id` is nullable, and a
SEEN/APPLIED match kept past its posting's expiry has `job_id = null` plus
`job_expired_at` set. That is why `job_title`, `apply_url` and `company_id` are
denormalized onto `matches` — null-check `match.getJob()` anywhere you touch it.

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
