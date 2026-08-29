# ATS API reference (verified field-level notes)

Not loaded by Claude Code by default — read this when resuming Lever/Ashby/Workable
fetcher work, or when touching Greenhouse field-parsing logic. Summary decisions live
in `CLAUDE.md`; this file is the detailed backup.

## Greenhouse — VERIFIED (Phase 0 complete)

Confirmed against a real live board (Razorpay, token
`razorpaysoftwareprivatelimited` — board token must be read from the real careers page
URL, never guessed from company name) and cross-checked against
developers.greenhouse.io/job-board.html.

- **List endpoint** `GET /v1/boards/{token}/jobs` returns: `id`, `internal_job_id`,
  `title`, `updated_at` (ISO 8601 with tz offset, e.g. `2016-01-14T10:55:28-05:00` —
  usable directly for `posted_at`), `requisition_id`, `location.name`, `absolute_url`,
  `language`, `metadata`.
- **Add `?content=true`** for: `content` (full HTML description, entity-escaped),
  `departments[]`, `offices[]`.
- **`metadata` is per-company, not a fixed schema** — e.g. Razorpay uses `"Job
  Location"`, PhonePe uses `"Requisition Location"`. Don't design the data model
  assuming a consistent shape; treat as optional/inspect-per-company, or skip for v1 matching.
- **Prospect posts pollute the `jobs` array** — "register your interest" pages with
  `internal_job_id: null`. Filter with `WHERE internal_job_id IS NOT NULL`.
- **No pagination** — full response in one call, `meta.total` gives count.
- **`first_published` is a stable cross-company field**, good for `platform_posted_at`.
- Harvest API v1/v2 deprecation (Aug 31, 2026) does **not** affect this — that's a
  separate authenticated internal-recruiter API. The public Job Board API is unaffected.

## Lever — VERIFIED (fetcher built + live-tested 2026-08-02)

`GET api.lever.co/v0/postings/{company}?mode=json`. Verified live against FamPay
(`fampay`, 14 jobs) and Sprinto (`Sprinto`, 29 jobs); identical key sets on both.
**Postman (`postman`) is dead — 404 as of 2026-08-02, dropped as a test target.**

- **Root is a bare JSON array**, not `{jobs: []}`.
- Field shape: `id` (UUID), `text` (title), `categories.{team, department, location,
  allLocations, commitment}` (`commitment` per-board optional — null-safe access),
  `createdAt` (epoch **milliseconds**, needs conversion), `hostedUrl` (posting page →
  applyUrl), `applyUrl` (bare form), `descriptionPlain`, `lists[]`, `additionalPlain`,
  plus newer `openingPlain`/`descriptionBodyPlain`. **No `updated_at` field at all.**
- **`descriptionPlain` alone is only the intro** (~1.8k chars) — the requirement
  bullets ("Must Haves" incl. "3-5 years..." experience strings) are **HTML inside
  `lists[].content`**. The fetcher assembles: `descriptionPlain` + `lists[].text` +
  stripped `lists[].content` + `additionalPlain`. Don't "simplify" this back to
  `descriptionPlain` only — it silently guts keyword and experience matching.

## Ashby — VERIFIED (fetcher built + live-tested 2026-08-02)

`GET api.ashbyhq.com/posting-api/job-board/{token}` (public path — NOT the
`jobPosting.list` RPC, which needs an API key). Verified live against Aspora
(`Aspora`, 18 jobs). Root: `{jobs: [], apiVersion}`.

Field shape: `jobs[]` with `id` (UUID), `title`, `department`, `team`,
`employmentType`, `location`, `secondaryLocations[]`, `address`, `publishedAt`
(proper ISO with offset → `platform_posted_at`), `isListed` (**filter false out**),
`isRemote`, `workplaceType`, `descriptionHtml`/`descriptionPlain`, `jobUrl`
(posting page → applyUrl), `applyUrl` (bare form).

- **Use `descriptionPlain` directly** — no HTML stripping needed.
- **`location` has trailing whitespace live** ("Bangalore ") — trim it.
- Hasura's board token is likely dead (careers page redirects to `hasura.io/careers/`
  under PromptQL branding) — Aspora is the confirmed working target.

## Workable — VERIFIED (fetcher built + live-tested 2026-08-02); TWO-CALL DESIGN

Verified live against Apna. **The list endpoint carries no description at all**, so
the fetcher makes two kinds of calls:

- **List:** `GET apply.workable.com/api/v1/widget/accounts/{token}` — root
  `{name, description, jobs: []}`. Items: `shortcode` (**the external id**), `title`,
  `city`/`country`/`state`, `url` (→ applyUrl), `published_on` (**date-only**, parsed
  as midnight UTC fallback), `created_at`, `department`, `experience` (**a seniority
  label like "Associate", NOT years — ignore it**), `locations[]`.
  **The list repeats a job once per posting location with the same `shortcode`** —
  observed live: 128 rows, 96 unique. Fetcher dedupes within the batch.
- **Detail:** `GET apply.workable.com/api/v2/accounts/{token}/jobs/{shortcode}` —
  `description` + `requirements` + `benefits` (all HTML, stripped and concatenated),
  `published` (full ISO → `platform_posted_at`), `location`, `remote`, `workplace`.
  (`/api/v1/widget/accounts/{t}/jobs/{code}` and `/api/v3/...` both 404 — v2 is the
  only working public detail path.)
- **N+1 guard:** detail is fetched only for shortcodes not already in the DB
  (`JobRepository` check inside `WorkableFetcher`). First fetch of a board pays full
  price (~96 calls for Apna, ~50s); steady state is ~0 per cycle. If a detail call
  fails, the job is still emitted from list data (null description), not dropped.

Zerodha's careers page is custom-built, not Workable-hosted — Apna (`apna`) is the
confirmed working target.

## SmartRecruiters — VERIFIED (fetcher built + live-tested 2026-08-29); TWO-CALL DESIGN

Added because **PhonePe migrated here off Greenhouse**. Their old token `phonepe`
now 404s at both `boards-api.greenhouse.io` and `job-boards.greenhouse.io` (their
individual job pages 404 too, even ones still in Google's index), while their
careers feed at `phonepe.com/apollo/job-postings/latest.json` links every live
role to `jobs.smartrecruiters.com/PHONEPELIMITED/...`. Verified live against
PhonePe (`PHONEPELIMITED`, 6 postings) and Bosch (`BoschGroup`, 4,774 postings —
the pagination target).

- **List:** `GET api.smartrecruiters.com/v1/companies/{token}/postings?limit=100&offset=N`
  → `{ offset, limit, totalFound, content: [] }`. Items carry `id` → `external_id`,
  `name` → `title` (not `title`), `releasedDate` (full ISO with millis,
  `2026-08-28T11:20:45.290Z`) → `platform_posted_at`, `location.fullLocation` →
  `location` (falls back to `location.city`), plus `experienceLevel`, `function`,
  `typeOfEmployment`, `ref`.
- **PAGINATION IS MANDATORY — `limit` is silently capped at 100.** Asking for 500
  returns 100 rows with `"limit":100` echoed back, and no error. A single call
  against Bosch would quietly return the first 2% of the board and look completely
  successful. The fetcher pages on `offset` until a short or empty page arrives,
  rather than trusting `totalFound`, which can shift mid-pagination.
- **A BOGUS TOKEN RETURNS `200 {"totalFound":0,"content":[]}`, NOT 404.** Verified
  against a nonsense company id. This is unique among the five platforms and it
  breaks the `AtsFetcher` contract's assumption that an empty list means the board
  is genuinely empty — a typo'd token would look perfectly healthy and simply never
  produce a job, the same silent-empty-feed shape as the 07-18 and C++ bugs.
  Handled by `AtsFetcher.validateBoard` (default no-op, overridden here), called
  from `POST /watchlist` only when creating a company nobody watches yet; it
  rejects with **400** naming the token. Deliberately NOT enforced inside `fetch`,
  so a real board that has zero openings this week keeps working.
- **There is no company-metadata endpoint to validate against** —
  `GET /v1/companies/{token}` 404s for valid and invalid ids alike. The postings
  list is the only probe available.
- **Detail:** `GET api.smartrecruiters.com/v1/companies/{token}/postings/{id}` —
  needed because the list carries **neither a description nor an apply URL**.
  Gives `postingUrl` → `apply_url` (preferred; `applyUrl` is the same page with an
  `?oga=true` tracking param), and `jobAd.sections.{companyDescription,
  jobDescription, qualifications, additionalInformation}` — each an HTML blob with
  a `.text`, stripped and concatenated in that order, the way Lever's multi-field
  body is assembled.
- **N+1 guard:** as with Workable, detail is fetched only for ids not already in
  the DB. On Bosch that is the difference between ~2 calls and 4,774 per cycle. A
  failed detail call SKIPS the job, same reasoning as Workable — a row persisted
  without a description would never be revisited, because the guard keys off row
  existence.
- `experienceLevel` is a seniority label (`director`, `mid_senior_level`), NOT
  years — ignored, exactly like Workable's. Years come from description text via
  `ExperienceParser`.

## Test fixtures

Real captured responses live in `src/test/resources/fixtures/` and back the fetcher
unit tests: `ashby-aspora.json`, `lever-fampay.json`, `lever-sprinto.json`,
`workable-apna.json` (list), `workable-v2-job.json` (detail) — all 2026-08-02 — plus
`smartrecruiters-phonepe.json` (list) and `smartrecruiters-phonepe-detail.json`
(detail), captured 2026-08-29. If a board's live shape drifts, re-capture with curl
and update both fixture and mapping.
