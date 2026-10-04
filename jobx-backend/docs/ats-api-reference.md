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

## Rippling — VERIFIED (fetcher built + live-tested 2026-10-04); TWO-CALL DESIGN

Verified against Rippling's own board (`rippling`, 331 jobs).

- **List:** `GET api.rippling.com/platform/api/ats/v1/board/{slug}/jobs` → a bare
  JSON array, the whole board in one call (no paging). Items: `uuid` (**the
  external id**), `name` → title, `url` → apply URL
  (`ats.rippling.com/{slug}/jobs/{uuid}`), `department{id,label}`,
  `workLocation{label,id}`. **No date and no description.**
- **THE LIST REPEATS A JOB ONCE PER LOCATION** under the same `uuid`: 651 rows,
  331 jobs. The fetcher groups rows by uuid and joins their location labels
  (three shown, then "+N more"). The preview counts uuids, not rows.
- **SLUGS ARE CASE-SENSITIVE.** `rippling` is a board; `Rippling` is a 404.
- **Dead board:** an unknown slug is `404 {"error_code":"RESOURCE_NOT_FOUND",
  "message":"Job Board not found"}` on v1 (and on v2). That is a clean dead
  signal, so Rippling is in `CompanyResolver.PROBEABLE`.
- **Detail:** `GET ats.rippling.com/api/v2/board/{slug}/jobs/{uuid}` →
  `description.role` + `description.company` (HTML, stripped and joined, role
  first), `createdOn` (`2026-08-13T08:54:48.317000-07:00`, full ISO with offset)
  → `platform_posted_at`, `workLocations[]` (strings), `employmentType`,
  `companyName`. `activeJobApplication` (the application form) is dropped
  from `raw_json`. An unknown uuid is a 404.
- **Age gate runs after the detail call**, because only the detail has a date. It
  saves storing a stale posting, not the request. Known ids are still filtered
  before it.
- A paged list also exists (`ats.rippling.com/api/v2/board/{slug}/jobs`,
  `page, pageSize=20, totalItems`). It isn't used, because v1 returns everything
  in one call.

## BambooHR — VERIFIED (fetcher built + live-tested 2026-10-04); TWO-CALL DESIGN

Verified against Off Duty Management (`offdutymanagement`, 2 jobs).

- **The token is a subdomain** (`{sub}.bamboohr.com`). It goes through
  `BoardTokens.requireSubdomainLabel` before any request. The host is built only
  as `label + ".bamboohr.com"`.
- **List:** `GET {sub}.bamboohr.com/careers/list` →
  `{"meta":{"totalCount":N},"result":[…]}`. Items: `id` (**the external id**,
  a numeric string), `jobOpeningName` → title, `departmentLabel`,
  `employmentStatusLabel`, `location{city,state}`, `atsLocation{country,state,
  province,city}`, `isRemote`, `locationType`. **No date and no description.**
  A posting fills `location` *or* `atsLocation`; live, one board used both. The
  fetcher reads `location` first, then `atsLocation`.
- **Detail:** `GET {sub}.bamboohr.com/careers/{id}/detail` (JSON) →
  `result.jobOpening{description (HTML), datePosted ("2026-07-16", DATE-ONLY),
  jobOpeningShareUrl, location, atsLocation, compensation, minimumExperience}`
  plus `result.formFields` (the application form, not stored).
  `minimumExperience` is a label ("Mid-level"), NOT years, so it is ignored.
  `/careers/{id}` without `/detail` is the HTML page (used as the apply URL).
  An unknown id is a 404.
- **DEAD BOARD = 302 → `https://www.bamboohr.com/`.** Every one of ~30 random
  labels did this. WebClient doesn't follow redirects, and `retrieve()` treats a
  3xx as success, so the fetcher reads `toEntity` and checks the status itself.
- **DORMANT ACCOUNTS ANSWER 200 WITH ZERO JOBS**, like Workable's ghosts: `andela`,
  `zapier`, `toggl`, `asana`, `vercel` and `netlify` all return
  `{"meta":{"totalCount":0},"result":[]}`. `validateBoard` rejects them at add
  time, and the probe ignores them. Fixture: `bamboohr-andela-empty.json`.
- `datePosted` is judged by the **end** of its day for the TTL, as with Workable.

## Jobvite — VERIFIED (fetcher built + live-tested 2026-10-04); HTML TIER

Verified against Egnyte (`egnyte`, 26 jobs) and Nutanix (`nutanix`, 266 jobs).
**This is the first HTML-tier fetcher.** There is no public JSON API, so the
server-rendered career site is parsed with jsoup.

- **List: `GET jobs.jobvite.com/{co}/search?p={N}`, NOT `/{co}/jobs`.** The
  `/jobs` page groups jobs by category and cuts long categories off behind a
  "Show More" link. On Nutanix it showed 117 of 266 jobs. The search pages list
  everything, 50 per page:
  - cards: `td.jv-job-list-name a[href=/{co}/job/{id}]` (title and **external
    id**) and `td.jv-job-list-location` (text; a multi-site job reads
    "2 Locations");
  - `.jv-pagination-text`: "1-50 of 266";
  - `a.jv-pagination-next` is present on every page but the last.

  Pages are capped at `jobx.fetch.jobvite.max-pages` (default 20, so 1,000
  jobs), with a WARN when the cap is hit.
- **FAIL LOUDLY.** A 200 page with zero cards throws `AtsFetchException`, unless
  `.jv-page-body` contains "No results found" (Jobvite's empty-state text). That
  marker was captured from a search that matched nothing
  (`jobvite-egnyte-noresults.html`); no live board with zero openings could be
  found. Treat it as the best available signal, not a verified one.
- **Detail:** `GET jobs.jobvite.com/{co}/job/{id}` →
  `.jv-job-detail-description` (HTML; its "Description" heading is dropped)
  and **a JSON-LD `JobPosting` block** (the 2026-09-27 recon said there was none;
  there is now). From it come `datePosted` ("2026-07-14", **date-only**) →
  `platform_posted_at`, and `jobLocation[].address{addressLocality,
  addressRegion}` → location. That replaces the list's "N Locations" text.
  `raw_json` stores the JSON-LD. A page with no description is a detail
  failure: the job is skipped and retried.
- **Dead board:** an unknown company is `302 → http://search.jobvite.com/?invalid=1`
  (no longer the `/support/` URL the first recon saw). A removed job is
  `303 → /careers/{co}/jobs?error=404`. Any 3xx is treated as gone.
- **Display name:** from `<title>{Name} Careers</title>`, not the page header.
  Nutanix replaces the standard header with its own.
- **The preview count** comes from the pagination text, so it covers the whole
  board from one page.
- Not probeable: found only from a pasted URL or a sniffed careers page.

## Test fixtures

Real captured responses live in `src/test/resources/fixtures/` and back the fetcher
unit tests: `ashby-aspora.json`, `lever-fampay.json`, `lever-sprinto.json`,
`workable-apna.json` (list), `workable-v2-job.json` (detail) — all 2026-08-02 — plus
`smartrecruiters-phonepe.json` (list) and `smartrecruiters-phonepe-detail.json`
(detail), captured 2026-08-29. Captured 2026-10-04:
- **Rippling:** `rippling-rippling-v1-list.json`, which is the first five jobs of
  the live list with every per-location row kept (15 rows), and
  `rippling-rippling-v2-detail.json`.
- **BambooHR:** `bamboohr-offdutymanagement-list.json`,
  `bamboohr-offdutymanagement-detail.json` and `bamboohr-andela-empty.json`.
- **Jobvite:** `jobvite-egnyte-search.html`, `jobvite-nutanix-search-p0.html`
  and `jobvite-nutanix-search-p5.html` (the last page), plus
  `jobvite-egnyte-detail.html` and `jobvite-egnyte-noresults.html`.

If a board's live shape drifts, re-capture with curl and update both fixture
and mapping.

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
| Rippling | `ats.rippling.com/{token}/jobs` (token is case-sensitive) |
| BambooHR | `{token}.bamboohr.com/careers` |
| Jobvite | `jobs.jobvite.com/{token}/jobs` (also `/{token}/search`, `/{token}/job/{id}`) |

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
| Workday | List: `POST https://{tenant}.{wdN}.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs`, body `{"appliedFacets":{},"limit":20,"offset":0,"searchText":""}` → `{total, jobPostings[{title, externalPath, locationsText, postedOn, bulletFields}]}`. Detail: `GET …/wday/cxs/{tenant}/{site}{externalPath}` → `jobPostingInfo{jobDescription (HTML), startDate (date-only), timeType, jobReqId, location}` | TBD | Token is composite `tenant/wdN/site`, e.g. `salesforce/wd12/External_Career_Site` (1,522 jobs, 20 per page). `postedOn` is relative text ("Posted Today", "Posted 30+ Days Ago"). Still to check: is `total` only set on page 0? Is the list newest-first? |
| JazzHR | `https://{sub}.applytojob.com/apply` (HTML) | Redirects to `jazzhr.com/job-seekers`, or `<title>` "Inactive Career Page" | Still need a live tenant |
| iCIMS | Guessed `https://{host}.icims.com/jobs/search?ss=1&in_iframe=1` | — | Every host tried returned 404. Find the real URL form from an actual customer's careers page. |
| Gusto | `jobs.gusto.com/boards/{slug}` | — | 403 Cloudflare "Just a moment…" challenge. Don't bypass it; stays UNSUPPORTED unless a public URL without the challenge exists. |
