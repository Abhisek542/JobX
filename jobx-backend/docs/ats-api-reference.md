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

## JazzHR — VERIFIED (fetcher built 2026-10-04); HTML TIER, TWO-CALL DESIGN

No public JSON API, so `JazzHrFetcher` parses the board page with jsoup. Verified
against Brennan Center (`brennancenter`, 21 roles), Sciaky (`sciakyinc`, 2), MRA
(`mra`, 122), Illinois SoS (`ilsos`, 18) and Inflow (`getinflow`, a real board with
nothing open).

- **Token** is the subdomain: `{sub}.applytojob.com`. It is a hostname, so it goes
  through `BoardTokens.requireSubdomainLabel` before any request. Case-insensitive
  (`BrennanCenter` serves the same board).
- **List:** `GET https://{sub}.applytojob.com/apply` → server-rendered HTML. Each
  posting is `li.list-group-item > h3.list-group-item-heading > a[href]`, the link
  being `https://{sub}.applytojob.com/apply/{code}/{slug}`. The location is the
  `<li>` holding `i.fa-map-marker`. **The whole board is one page** — 122 cards on
  `mra`, no pagination. No dates, no descriptions. `externalId` = `{code}`
  (10 alphanumerics, e.g. `KJ32rbHkxr`). Only links on the board's own host count.
- **Detail:** `GET https://{sub}.applytojob.com/apply/{code}` (the slug is
  cosmetic; the bare code returns the same page). The page carries a JSON-LD
  `JobPosting`: `title`, `description` (HTML), `datePosted` (**date-only**,
  `2026-10-01`), `validThrough`, `employmentType`, `jobLocation.address`,
  `baseSalary`. The fetcher reads JSON-LD, not CSS — it is the part of the page
  that exists for Google and least likely to move.
- **Not every detail page has the JobPosting.** 2 of Brennan Center's 21 carry
  only the `Organization` block. Those fall back to the rendered
  `#job-description` text, with no date (the TTL then runs from `first_seen_at`).
  Skipping them would lose them for good *and* cost a detail call every cycle,
  because a skipped posting is never stored.
- **Board name:** the board page has a JSON-LD `Organization` block with `name`
  ("Brennan Center for Justice"); the `<title>` is "{name} - Career Page".
- **Dead-board signals — every one throws, none is an empty list:**
  - an unknown subdomain → **302 → `https://info.jazzhr.com/job-seekers.html`**.
    The target moved since the 2026-09-27 recon (it was `jazzhr.com/job-seekers`),
    so the fetcher treats **any 3xx** as dead, not one URL. The shared WebClient
    never follows redirects, so `HtmlPage` keeps the status and `Location`;
  - a cancelled account → **200** with `<title>JazzHR - Inactive Career Page`
    (seen live on the `jazzhr` subdomain itself);
  - a 200 page with zero cards and no empty-board marker → a layout change.
- **A real empty board** says "There are no open positions at this time." in an
  `h2.page-title` and has no `li.list-group-item`. That is the only zero-card page
  read as a quiet board. It previews as 0, so `validateBoard` rejects it at add
  time, like every platform.
- **No public feed:** `/apply/jobs/feed` is a 302.
- **TTL cost:** the list has no date, so a posting past the TTL costs one detail
  call the first time it is seen. `PastTtlMemo` remembers it so it never costs
  another (it is never stored or tombstoned, so nothing else would).

## Gusto — VERIFIED (fetcher built 2026-10-04); HTML TIER, CLOUDFLARE-FRONTED

Gusto Recruiting's hosted boards. Verified against Sage Veterinary Imaging
(14 postings) and Alexandria Electric (1).

- **Token** is the board slug: the company name plus a UUID,
  `sage-veterinary-imaging-07e81227-32b5-482d-9fc0-c99bc9ad2f96`. It can never be
  guessed, so Gusto is never probed. Validated as `[a-z0-9-]{1,150}` before it goes
  into the URL path.
- **List:** `GET https://jobs.gusto.com/boards/{slug}` → server-rendered HTML,
  `<title>Careers at {Company}`. Each posting is an `a[href^=/postings/]` with an
  `h3` title, then a `p` with the location (a second `p` has pay and type). The
  link is `/postings/{company}-{title}-{uuid}`; `externalId` is that trailing
  UUID. No dates, no descriptions. An unknown board is a **404**.
- **Detail:** `GET https://jobs.gusto.com/postings/{slug}` → JSON-LD `JobPosting`:
  `description` (HTML), `datePosted` (**full ISO with offset**,
  `2026-05-14T13:01:59.000-07:00`), `validThrough`, `employmentType`,
  `hiringOrganization.name`, `jobLocation.address` (street, locality, region,
  country). The detail URL is rebuilt on the fixed host from a slug that matched
  `^/postings/[a-z0-9-]*{uuid}$`; a scraped href is never requested as-is.
- **Not every posting has JSON-LD.** 3 of Sage's 14 — all contractor roles — have
  none. Those fall back to the rendered body (`div.max-w-prose > div.mt-8`), with
  no date, for the same reason as JazzHR.
- A card can list several locations, one per `<br>` line; they are joined as
  "Round Rock, TX / Spring, TX".
- **Empty board:** not seen live (none was found during recon). A `Careers at …`
  page with the `ul.divide-y` job list rendered and empty is read as quiet; any
  other zero-posting page throws.
- **`sitemap.xml`** lists ~1,200 postings and no boards — no use for discovery,
  though a posting page links back to its board (`href="/boards/…"`).
- **BOT PROTECTION.** `jobs.gusto.com` is behind Cloudflare. It challenges some
  clients and not others:
  - curl's default user agent: **403 `Cf-Mitigated: challenge`**, "Just a
    moment…" (captured as `gusto-challenge.html`), every time;
  - a Chrome user agent: challenged on the first request;
  - this app's default client (`ReactorNetty/…`) and an honest `JobX/1.0` agent:
    **200**. Gate check: 20 of 20 board and posting requests, 1 s apart.

  The fetcher never sends a browser user agent, never retries around a challenge
  and never tries to solve one. A challenge (403/503 with a "Just a moment" or
  `challenge-platform` body) is a FAILED fetch, and a challenge during the detail
  calls stops the fetch instead of continuing to hit the site.
- **`robots.txt`** allows everything but `/login/*` and asks for **`Crawl-delay:
  1`**. Every detail call waits 1 s. Gusto boards are small businesses, so this
  costs seconds per new posting, and only for postings not already known.
- **TTL cost:** as on JazzHR, a stale posting costs one detail call ever, via
  `PastTtlMemo`.

## iCIMS — INVESTIGATED 2026-10-04, NOT BUILT (decision pending)

Two findings change the 2026-09-27 picture.

- **The CAPTCHA was caused by our own user agent.** With a Chrome user agent,
  every `{host}.icims.com/jobs/search?ss=1` — a made-up host included — answered
  **405 with `x-amzn-waf-action: captcha`** (an AWS WAF "Human Verification"
  page). With an honest agent (`Mozilla/5.0 (compatible; JobX/1.0)`) there was no
  challenge: guessed hosts (`careers-tesla`, `jobs-cvshealth`, a bogus one)
  returned a plain **404**, and a real one returned 200. Another reason never to
  send a browser user agent.
- **Customers are moving off the classic portal.** The real host found by
  following UCLA's careers page (`jobs.ucla.edu` links
  `careers-ucla.icims.com/jobs/login`) serves UCLA's new site, and the classic
  `in_iframe=1` search form returns only a script redirecting to
  `jobs.ucla.edu/jobs`. That new site is **iCIMS Career Sites** (formerly Jibe —
  the page is full of `jibe` references; iCIMS's own careers site is the same
  product). It has a JSON API on the **customer's own domain**:
  `GET https://jobs.ucla.edu/api/jobs?page=1&sortBy=posted_date&descending=true`
  → `{"jobs":[{"data":{slug, req_id, title, description (HTML), …}}]}`.

Why it isn't built: no live classic-portal board was found to build the planned
HTML fetcher against, and the Career Sites API needs a different design — the
token would be a whole customer domain taken from user input, which needs the
`SafeUrlFetcher`/`PrivateAddressGuard` treatment rather than `BoardTokens`. That
is a design call, not a recon gap. Until then `ICIMS` stays recognised but not
watchable: a pasted iCIMS link gets the honest "uses iCIMS, can't watch yet" answer.

## Test fixtures

Real captured responses live in `src/test/resources/fixtures/` and back the fetcher
unit tests: `ashby-aspora.json`, `lever-fampay.json`, `lever-sprinto.json`,
`workable-apna.json` (list), `workable-v2-job.json` (detail) — all 2026-08-02 — plus
`smartrecruiters-phonepe.json` (list) and `smartrecruiters-phonepe-detail.json`
(detail), captured 2026-08-29. HTML-tier pages captured 2026-10-04:
`jazzhr-brennancenter-list.html`, `jazzhr-brennancenter-detail.html`,
`jazzhr-getinflow-empty.html` (a real empty board), `jazzhr-inactive.html` (a
cancelled account), `gusto-sage-board.html`, `gusto-sage-posting.html` and
`gusto-challenge.html` (the Cloudflare page curl's user agent is served). If a
board's live shape drifts, re-capture with curl and update both fixture and mapping.

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
| JazzHR | `{token}.applytojob.com/apply`, `{token}.applytojob.com/apply/{code}/{slug}` |
| Gusto | `jobs.gusto.com/boards/{token}` (a posting link, `jobs.gusto.com/postings/…`, names only the platform) |

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
| Rippling | List (one call, whole board): `GET https://api.rippling.com/platform/api/ats/v1/board/{slug}/jobs` → `[{uuid, name, department, url, workLocation}]`. Paged alternative: `ats.rippling.com/api/v2/board/{slug}/jobs` (`page, pageSize=20, totalItems`). Detail: `GET ats.rippling.com/api/v2/board/{slug}/jobs/{uuid}` → `description{company, role}` (HTML), `createdOn` (ISO), `employmentType`, `companyName` | v2 → 404 `RESOURCE_NOT_FOUND` | Page URL: `ats.rippling.com/{slug}/jobs` |
| BambooHR | `GET https://{sub}.bamboohr.com/careers/list` → `{"meta":{"totalCount":N},"result":[…]}` | 302 → `www.bamboohr.com` | `andela` is real but has 0 jobs. Still need a live tenant with openings to capture the item and detail shapes. |
| Jobvite | `GET https://jobs.jobvite.com/{co}/jobs`: server-rendered HTML with `.jv-job-list-name`, `.jv-job-list-location` and links to `/{co}/job/{id}` | Redirects to `jobvite.com/support/…?invalid=1` | Live boards: `nutanix`, `egnyte`. Detail pages have no JSON-LD. |

JazzHR, Gusto and iCIMS have moved out of this table into their own sections above.
