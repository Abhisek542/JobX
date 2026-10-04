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

- **A TOKEN THAT WAS NEVER THEIRS RETURNS 200 WITH THE RIGHT COMPANY NAME.**
  Verified live 2026-09-06: `apply.workable.com/api/v1/widget/accounts/razorpay`
  answers `{"name":"Razorpay","description":null,"jobs":[]}`. Razorpay is a
  Greenhouse customer. The same is true for `groww`, `atlan`, `meesho` and
  `sprinto` — none of them Workable customers. These are dormant or never-used
  accounts, and Workable is happy to name them.
  This is the SmartRecruiters empty-200 problem with a sting: the echoed name
  makes a wrong token look *confirmed*. A Workable board is therefore only
  believed when it has at least one live posting — `validateBoard` (inherited
  from `AtsFetcher`, driven by `previewBoard`) rejects a zero count at add time,
  and the add-company resolver applies the same rule before it will propose a
  board. Fixture: `workable-ghost-account.json`.

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

## Workday — VERIFIED (fetcher built + live-tested 2026-10-04); TWO-CALL DESIGN

Verified live against Salesforce (`salesforce/wd12/External_Career_Site`, 1,523
postings). NVIDIA (`nvidia/wd5/NVIDIAExternalCareerSite`) was spot-checked.

- **Token** is composite, `tenant/wdN/site`, because the board is addressed by
  three things that can't be derived from each other. It ends up in the **host**,
  so `WorkdayFetcher` runs it through `BoardTokens.WorkdayToken.parse` before any
  request and builds every URL from the validated parts. The site name is
  case-insensitive (`external_career_site` works too).
- **List:** `POST https://{tenant}.{wdN}.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs`,
  body `{"appliedFacets":{},"limit":20,"offset":N,"searchText":""}` →
  `{ total, jobPostings: [{title, externalPath, locationsText, postedOn, bulletFields:[reqId]}], facets }`.
  - **`Content-Type: application/json` is required.** Without it you get a 500.
  - **`limit` above 20 is a 400**, not a silent clamp. Pages are 20.
  - **`total` is only set at offset 0.** Every later page says `0`. NVIDIA's
    `total` is exactly 2000, which looks like a cap.
  - **Newest-first.** Sampled offsets 0 / 100 / 300 / 600 / 1000 / 1500 read
    "Posted Today" → "3 Days" → "6 Days" → "17 Days" → "30+ Days". The order jitters
    at day boundaries, e.g. "Posted Yesterday" after "Posted 2 Days Ago" on page 0.
    The fetcher stops after **two** consecutive pages that are entirely past the
    TTL, so on Salesforce it pages ~16 times, not 76. `jobx.fetch.workday.max-pages`
    (default 60) only bounds a board whose dates can't be read.
  - `postedOn` is **relative text**: "Posted Today", "Posted Yesterday", "Posted N
    Days Ago", "Posted 30+ Days Ago". `WorkdayPostedOn` reads it as that day's
    midnight UTC, the same clock as the stored `startDate`, and the fetcher skips
    too-old postings before the detail call. Unknown text (another locale) is never
    "too old".
  - `locationsText` is a place, or a count ("3 Locations") for multi-location
    postings. A count is never stored as a location.
- **Detail:** `GET https://{host}/wday/cxs/{tenant}/{site}{externalPath}` →
  `{ jobPostingInfo, hiringOrganization, similarJobs }`. From `jobPostingInfo`:
  - `jobDescription` (HTML, ~5–12k chars) → stripped → `description`;
  - `startDate` (date-only, `2026-10-03`) → `platform_posted_at` at midnight UTC.
    It is in the tenant's time zone, so an Australian posting can be dated
    "tomorrow" in UTC;
  - `location` plus `additionalLocations[]` → `location`, joined with `; `;
  - also present: `jobReqId`, `timeType`, `remoteType`, `externalUrl`, `endDate`.
  Only `jobPostingInfo` is kept as `raw_json`, because `similarJobs` is noise.
  `hiringOrganization.name` is a legal entity ("621 Salesforce.com India Private
  Limited Hyderabad Branch"), so it is **not** used as the display name.
- **`external_id` = `externalPath`** (e.g. `/job/India---Hyderabad/Performance-Engineer---Software-Engineering-SMTS_JR358291`).
  It's on the list, so the N+1 guard runs before the detail call. Paths that
  aren't a plain `/job/...` are dropped, because the path is appended to a URL on
  the board's host. The req id (`bulletFields[0]` / `jobReqId`) stays in `raw_json`.
- **Apply URL** = `https://{host}/{site}{externalPath}`, the public posting page.
  It's the same as the detail's `externalUrl`.
- **Dead board:** unknown site → **404** `{"errorCode":"S21","message":"not found:
  Job_Posting_Site_ID=…"}`, unknown tenant → **422**, unknown posting → 404. All of
  them throw out of `retrieve()`, so a dead board is a FAILED fetch and a 400 at add
  time, never an empty one.
- **Detail failure** skips the posting, which is retried next cycle. Same rule as
  Workable.
- **Never probed.** The token can't be guessed from a name, so Workday is not in
  `CompanyResolver.PROBEABLE`. A careers page that only mentions `myworkdayjobs.com`
  gets the platform hint and no probe. Boards are found from a pasted link or a
  sniffed board URL.

## Test fixtures

Real captured responses live in `src/test/resources/fixtures/` and back the fetcher
unit tests: `ashby-aspora.json`, `lever-fampay.json`, `lever-sprinto.json`,
`workable-apna.json` (list), `workable-v2-job.json` (detail) — all 2026-08-02 — plus
`smartrecruiters-phonepe.json` (list) and `smartrecruiters-phonepe-detail.json`
(detail), captured 2026-08-29 — plus `workday-salesforce-list.json` (page 0,
`total` 1523) and `workday-salesforce-detail.json`, captured 2026-10-04. If a board's live shape drifts, re-capture with curl
and update both fixture and mapping.

## Careers-page URL forms (for ATS detection, verified live 2026-09-06)

The add-company flow reads a board out of a careers page. These are the public
*page* hosts, distinct from the API hosts above, and `AtsUrlParser` matches both.

| Platform | Public board URL |
|---|---|
| Greenhouse | `job-boards.greenhouse.io/{token}`, `boards.greenhouse.io/{token}`, `boards.greenhouse.io/embed/job_board?for={token}` |
| Lever | `jobs.lever.co/{token}` |
| Ashby | `jobs.ashbyhq.com/{token}` |
| Workable | `apply.workable.com/{token}` |
| SmartRecruiters | `jobs.smartrecruiters.com/{token}` |
| Workday | `{tenant}.{wdN}.myworkdayjobs.com/{site}`, optionally with a locale (`/en-US/{site}`) → token `tenant/wdN/site` |

**REGIONAL HOSTS ARE NOT OPTIONAL.** Groww's careers page links
`job-boards.**eu**.greenhouse.io/groww`. A pattern anchored on
`boards.greenhouse.io` finds nothing on the second company anyone tests. Lever
has `jobs.eu.lever.co` likewise.

What sniffing a live careers page actually yields, measured across four boards:

| Page | Outcome |
|---|---|
| `razorpay.com/jobs` | token found — `razorpaysoftwareprivatelimited`, which no guess from the name reaches |
| `groww.in/careers` | token found, via the EU host |
| `atlan.com/careers` | **platform only** — `*.ashbyhq.com` appears solely in a CSP header; the board is client-rendered |
| `fampay.in/careers` | nothing — SPA, no ATS reference in server HTML |

So sniffing hits about half of careers pages, and slug probing covers exactly the
half it misses (atlan → `atlan`, fampay → `fampay`). Neither is sufficient alone.
Page fixtures for the first three are in `src/test/resources/fixtures/careers-*.html`.

## Candidate platforms — recon 2026-09-27 (NOT BUILT YET)

These are first-pass curl checks, not verified fetcher notes. Each platform's PR
must re-capture fixtures and replace its row below with a proper `## {Platform} —
VERIFIED` section. The build order and rules are in the backend CLAUDE.md, under
"ATS integration approach".

| Platform | Endpoint(s) seen | Dead-board signal | Notes |
|---|---|---|---|
| Rippling | List (one call, whole board): `GET https://api.rippling.com/platform/api/ats/v1/board/{slug}/jobs` → `[{uuid, name, department, url, workLocation}]`. Paged alternative: `ats.rippling.com/api/v2/board/{slug}/jobs` (`page, pageSize=20, totalItems`). Detail: `GET ats.rippling.com/api/v2/board/{slug}/jobs/{uuid}` → `description{company, role}` (HTML), `createdOn` (ISO), `employmentType`, `companyName` | v2 → 404 `RESOURCE_NOT_FOUND` | Page URL: `ats.rippling.com/{slug}/jobs` |
| BambooHR | `GET https://{sub}.bamboohr.com/careers/list` → `{"meta":{"totalCount":N},"result":[…]}` | 302 → `www.bamboohr.com` | `andela` is real but has 0 jobs. Still need a live tenant with openings to capture the item and detail shapes. |
| Jobvite | `GET https://jobs.jobvite.com/{co}/jobs`: server-rendered HTML with `.jv-job-list-name`, `.jv-job-list-location` and links to `/{co}/job/{id}` | Redirects to `jobvite.com/support/…?invalid=1` | Live boards: `nutanix`, `egnyte`. Detail pages have no JSON-LD. |
| JazzHR | `https://{sub}.applytojob.com/apply` (HTML) | Redirects to `jazzhr.com/job-seekers`, or `<title>` "Inactive Career Page" | Still need a live tenant |
| iCIMS | Guessed `https://{host}.icims.com/jobs/search?ss=1&in_iframe=1` | — | Every host tried returned 404. Find the real URL form from an actual customer's careers page. |
| Gusto | `jobs.gusto.com/boards/{slug}` | — | 403 Cloudflare "Just a moment…" challenge. Don't bypass it; stays UNSUPPORTED unless a public URL without the challenge exists. |
