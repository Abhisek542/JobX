# Plan: ATS coverage — 7 new fetchers (Workday, Rippling, BambooHR, Jobvite, JazzHR, iCIMS, Gusto)

## Context

OpenJobRadar (openjobradar.com/integrations) monitors 12+ ATSs; JobX supports 5
(Greenhouse, Lever, Ashby, Workable, SmartRecruiters). Everything else resolves
to `UNSUPPORTED`, and CLAUDE.md currently parks Workday/Rippling/BambooHR as
"no clean public API". Live recon on 2026-09-27 disproved that for several of
them. The goal is to add all seven platforms on their list, each as a normal
`AtsFetcher` that plugs into the existing resolve → confirm → watch → poll flow,
without breaking the project's rules: never guess a token, never report a dead
board as a quiet one, never bypass bot protection.

### What recon already established (2026-09-27, curl)

| Platform | Endpoint | Verdict |
|---|---|---|
| **Workday** | `POST https://{tenant}.{wdN}.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs` body `{"appliedFacets":{},"limit":20,"offset":0,"searchText":""}` → `{total, jobPostings:[{title, externalPath, locationsText, postedOn:"Posted Today", bulletFields:[reqId]}]}`. Detail `GET …/wday/cxs/{tenant}/{site}{externalPath}` → `jobPostingInfo{jobDescription(HTML), startDate:"2026-09-27", timeType, jobReqId, location}` | JSON API ✅ (Salesforce: 1522 jobs, 20/page) |
| **Rippling** | List `GET https://api.rippling.com/platform/api/ats/v1/board/{slug}/jobs` → full array in one call (`uuid,name,department,url,workLocation`). Paged alt: `ats.rippling.com/api/v2/board/{slug}/jobs` (`page,pageSize=20,totalItems`). Detail `GET ats.rippling.com/api/v2/board/{slug}/jobs/{uuid}` → `description{company,role}` HTML, `createdOn` ISO, `employmentType`, `companyName`. Bogus slug → **404** `RESOURCE_NOT_FOUND` | JSON API ✅, clean dead signal |
| **BambooHR** | `GET https://{sub}.bamboohr.com/careers/list` → `{"meta":{"totalCount":N},"result":[…]}`. Unknown sub → **302 → www.bamboohr.com** | JSON ✅; need a live tenant with jobs to capture item/detail shape |
| **Jobvite** | `GET https://jobs.jobvite.com/{co}/jobs` → server-rendered HTML, `.jv-job-list-name` / `.jv-job-list-location`, links `/{co}/job/{id}`. Unknown co → redirect to `jobvite.com/support/…?invalid=1`. No JSON-LD on detail pages | HTML (jsoup) ✅ (nutanix, egnyte live) |
| **JazzHR** | `https://{sub}.applytojob.com/apply` HTML. Unknown sub → redirect to `jazzhr.com/job-seekers`; dead board title "Inactive Career Page" | HTML; need a live tenant |
| **iCIMS** | `https://{host}.icims.com/jobs/search?ss=1&in_iframe=1` — every tenant tried returned 404 | ⚠ Unverified — recon gate |
| **Gusto** | `jobs.gusto.com/boards/{slug}` → **403 Cloudflare "Just a moment…" challenge** | ⚠ Blocked. We will **not** bypass bot detection — recon gate |

## Approach

One PR for shared groundwork, then **one PR per platform** (like the original
Ashby → Lever → Workable rollout), ordered by value and certainty. Each
platform PR starts with a **recon step** — capture live fixtures, write the
`docs/ats-api-reference.md` section — before any fetcher code, per CLAUDE.md
("read that file before touching any fetcher code").

Tiers, made explicit in code and docs:
- **API tier** (JSON): Workday, Rippling, BambooHR.
- **HTML tier** (jsoup, already in `pom.xml`): Jobvite, JazzHR, iCIMS, Gusto.
  More fragile; must fail loudly — a 200 page on which the selector finds zero
  job cards and no known "empty/inactive board" marker throws
  `AtsFetchException` (never returns an empty list) so a changed page layout
  shows up as FAILED health, not a silent empty feed.

**Recon gates (iCIMS, Gusto):** if no public, un-challenged page or endpoint
exists, the platform stays `UNSUPPORTED` — we add it to `HOST_HINTS` only so the
resolver can name it honestly and `unsupported_board_requests` records demand.
No headless-browser or challenge-solving workarounds.

---

## PR 0 — Groundwork (no new fetchers yet) — DONE 2026-10-04

Built as planned below, with these differences:
- **Token hints:** `TOKEN_HINTS` was **not** extended. Each platform's hint lands
  in its own PR with a recon-verified real token.
- **Workday site check:** a Workday site is checked against `wday` and a locale
  only, not against `RESERVED`. Real site names include `Careers`.
- **Added beyond the plan:**
  - `CompanyResolver` returns at once, naming the platform as `platformHint`,
    when a pasted URL is on a platform with no fetcher.
  - A sniffed board on such a platform becomes the hint.
  - `POST /watchlist` returns 400 for any platform without a fetcher.
  - The modal's dead-end copy now has a branch for these platforms.

1. **`enums/AtsPlatform.java`** — add `WORKDAY, RIPPLING, BAMBOOHR, JOBVITE,
   JAZZHR, ICIMS, GUSTO`; update the `UNSUPPORTED` comment. `ats_platform` is
   `TEXT` with no CHECK constraint (V1/V4), so **no migration** is needed.
   `FetcherRegistry` auto-discovers beans — no change there.

2. **Host-safe tokens (SSRF).** Existing fetchers put the token in a URL *path*
   on a fixed host. BambooHR, JazzHR, iCIMS and Workday put it in the *host*,
   and `POST /watchlist` accepts `boardToken` straight from the user. Add
   `fetcher/BoardTokens.java` with:
   - `requireSubdomainLabel(token)` — `^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`
     (case-insensitive), throws `AtsFetchException`;
   - `WorkdayToken.parse(token)` → record `(tenant, shard, site)` with
     `shard` matching `^wd\d{1,3}$` and `site` `^[A-Za-z0-9_-]{1,100}$`.
   Every host-building fetcher validates before the first request, and the
   hosts are always built as `label + ".fixedsuffix.com"` — never
   concatenated from free text. Unit-test hostile tokens
   (`evil.com#`, `a/../b`, `x@y`, `a.b`).

3. **`resolve/AtsUrlParser.java`** — the `TOKEN` group can't carry Workday's
   three parts. Change `PATTERNS` from `Map<AtsPlatform, List<Pattern>>` to a
   small `Rule(Pattern, Function<Matcher,String> token)` list per platform;
   existing platforms keep `m -> cleanToken(m.group(1))`. New rules:
   - Workday: `([a-z0-9-]+)\.(wd\d+)\.myworkdayjobs\.com/(?:wday/cxs/[a-z0-9-]+/)?(?:[a-z]{2}-[A-Z]{2}/)?([A-Za-z0-9_-]+)` → `tenant/wdN/site` (skip locale segment and `wday`, `job`).
   - Rippling: `ats\.rippling\.com/(?:api/v\d+/board/)?TOKEN`, `api\.rippling\.com/platform/api/ats/v\d+/board/TOKEN`.
   - BambooHR: `TOKEN\.bamboohr\.com/(?:careers|jobs)`.
   - Jobvite: `jobs\.jobvite\.com/TOKEN`.
   - JazzHR: `TOKEN\.applytojob\.com`.
   - iCIMS: `([a-z0-9-]+)\.icims\.com/jobs` (token = full label, e.g. `careers-acme`).
   - Gusto: `jobs\.gusto\.com/boards/TOKEN`.
   Add the new hosts to `RESERVED` where they'd collide (`www`, `app`, `api`).
   Extend `HOST_HINTS` with narrow patterns (`myworkdayjobs\.com`,
   `ats\.rippling\.com`, `bamboohr\.com/careers`, `jobs\.jobvite\.com`,
   `applytojob\.com`, `icims\.com/jobs`, `jobs\.gusto\.com`) — narrow so a page
   that merely uses Rippling payroll isn't mis-hinted. `HOST_HINTS` is a
   `Map.of` (unordered) — switch to `LinkedHashMap` so hint order is stable.
   `boardUrl()` gets a case per platform (Workday: `https://{tenant}.{wdN}.myworkdayjobs.com/{site}`).
   Tests in `AtsUrlParserTest` for each form, including a careers-page HTML
   excerpt fixture per platform when recon yields one.

4. **`resolve/CompanyResolver.java` `PROBEABLE`** — add only platforms that are
   guessable from a name, cheap (one request), and have an unambiguous dead
   signal: `RIPPLING`, `BAMBOOHR` (added in their own PRs, not here). Workday
   and iCIMS are never probeable (shard/site/host prefix can't be guessed);
   Jobvite/JazzHR/Gusto stay URL/SNIFF-only to keep the probe fan-out bounded
   (`max-slug-candidates: 4` × platforms). If `platformHint` names a
   non-probeable platform, the resolver returns the existing honest empty
   answer with the hint — already the behavior, just add a test.

5. **Frontend** `jobx-frontend/src/app/core/models/watchlist.model.ts` — extend
   the `AtsPlatform` union, `PLATFORM_LABEL`, `TOKEN_HINTS` (Workday hint shows
   `tenant/wd5/Site` format). `SUPPORTED_PLATFORMS` and
   `shared/ui/auth-hero.ts` `platforms` get each platform **only in the PR that
   ships its fetcher**, so the UI never offers a platform with no fetcher.

---

## PR 1 — Workday (API tier, highest value) — DONE 2026-10-04

Built as planned below, with these differences, all from recon:
- **Paging stops early.** The list is newest-first, so paging ends after two
  consecutive pages that are entirely past the TTL (~16 pages on Salesforce, not
  76). `max-pages` only bounds boards whose dates can't be read.
- **`postedOn` is read as that day's midnight UTC, not generously.** The stored
  `startDate` uses the same clock, and so does the scheduler's re-check. A
  generous reading cost a repeated detail call every cycle for each last-day
  posting.
- **Resolver:** probing runs only when the platform hint is in `PROBEABLE`.
- `limit` > 20 is a 400. `Content-Type` is required. An unknown site is a 404 and
  an unknown tenant a 422.

- **Recon**: capture `workday-salesforce-list.json` (page 0, limit 20) and
  `workday-salesforce-detail.json`; confirm (a) whether `total` is only
  populated on the first page (reported Workday quirk — if so, read it from
  offset 0 only), (b) max `limit` (expect 20), (c) dead-board response for a
  bad site / bad tenant, (d) whether results are newest-first by default.
- **Token**: `tenant/wdN/site`, e.g. `salesforce/wd12/External_Career_Site`.
- **`fetcher/workday/WorkdayFetcher.java`**, two-call design modelled on
  `fetcher/workable/WorkableFetcher.java`:
  - Page list with POST, `limit=20`, until `offset >= total` or a configurable
    cap `jobx.fetch.workday.max-pages` (default 60 → 1,200 jobs) with a WARN
    when hit.
  - `externalId` = `externalPath` (known from the list, so `FetchFilter.isKnown`
    runs **before** the detail call).
  - Parse `postedOn` ("Posted Today" / "Posted Yesterday" / "Posted N Days
    Ago" / "Posted 30+ Days Ago") into an approximate instant, judged
    generously (end of day, like Workable's date-only handling) and skip
    `filter.isTooOld(...)` before paying for detail. With the 6-day TTL this is
    the main cost saver on big boards.
  - Detail: HTML `jobDescription` → `Jsoup.parse(...).text()` →
    `ExperienceParser.parse`; `startDate` (date-only) → `platformPostedAt`;
    location from `location`. Detail failure → skip, retry next cycle (same
    rule and comment as Workable).
  - `applyUrl` = `https://{tenant}.{wdN}.myworkdayjobs.com/{site}{externalPath}`.
  - `previewBoard`: single POST page 0 → `total` + first 3 titles; displayName
    null (fall back to catalog/domain name).
  - Package-private `parseList` / `applyDetail` / `parsePreview` seams.
- **Tests** `WorkdayFetcherTest` (fixtures, `FetchFilter.none()`), a filter test
  asserting known/too-old postings cost zero detail calls (copy
  `WorkableFetchFilterTest`), detail-failure test (copy
  `WorkableDetailFailureTest`), `postedOn` parser table test.
- Add `WORKDAY` to `SUPPORTED_PLATFORMS` / auth-hero.

## PR 2 — Rippling (API tier)

Detailed plan: `pr-2-rippling.md`.

- **Recon**: capture `rippling-rippling-v1-list.json` and a v2 detail; confirm
  v1 list has no dates (hence detail), and whether v1 is also 404 for bogus.
- **`fetcher/rippling/RipplingFetcher.java`**: list via v1 (one call, whole
  board), `externalId` = `uuid`, filter known ids, detail via v2 for
  description (`description.company` + `description.role`, HTML-stripped),
  `createdOn` → `platformPostedAt`, `workLocation.label` → location,
  `url` → applyUrl. `previewBoard`: one v1 call → count + titles; displayName
  from nothing (v1 has none) → null.
- Add to `PROBEABLE` (404 = clean dead signal). Tests as PR 1.

## PR 3 — BambooHR (API tier)

Detailed plan: `pr-3-bamboohr.md`.

- **Recon**: find ≥1 live tenant with open jobs (search the web for
  `"bamboohr.com/careers"`); capture `careers/list` and the per-job detail
  (expected `/careers/{id}/detail` — verify). Confirm what empty-but-real
  looks like (`andela`: `totalCount 0`).
- WebClient does not follow redirects by default: treat any **3xx** as "board
  does not exist" → `AtsFetchException` with a clear message (don't rely on
  the empty-body path).
- **`fetcher/bamboohr/BambooHrFetcher.java`**: subdomain token validated via
  `BoardTokens.requireSubdomainLabel`; list → filter → detail for description.
- Add to `PROBEABLE` only if recon confirms the wildcard-302 dead signal is
  reliable. Tests as PR 1.

## PR 4 — Jobvite (HTML tier)

Detailed plan: `pr-4-jobvite.md`.

- **Recon**: capture `jobvite-egnyte-list.html` and one detail page; find the
  description selector and whether the list paginates (`?p=` / "next").
- **`fetcher/jobvite/JobviteFetcher.java`**: jsoup list parse, `externalId` = the
  `/job/{id}` segment, redirect-to-support (3xx or final URL containing
  `/support/`) → dead. Detail page for description; no date available →
  leave `platformPostedAt` null (TTL falls back to `first_seen_at`, already
  supported).
- "Zero cards on a 200 page" → `AtsFetchException` unless the page shows
  Jobvite's no-openings marker (capture it in recon).

## PR 5 — JazzHR (HTML tier)

- **Recon**: find a live `{sub}.applytojob.com/apply` tenant with jobs; capture
  list + detail (`/apply/{id}/{slug}`). Dead signals: redirect to
  `jazzhr.com/job-seekers`, or `<title>` "Inactive Career Page".
- **`fetcher/jazzhr/JazzHrFetcher.java`** with the same HTML-tier rules.

## PR 6 — iCIMS (recon-gated)

- Recon: find the real public search URL form for a known iCIMS customer's
  careers link (follow an actual company careers page, don't guess the host).
  If it serves job listings without a bot challenge → HTML-tier fetcher like
  PR 4 (token = full host label, validated). If it's challenge-protected or
  iframe/JS-only → **stop**: keep `UNSUPPORTED`, keep the `HOST_HINTS` entry,
  document the finding in `ats-api-reference.md`.

## PR 7 — Gusto (recon-gated)

- Recon showed a Cloudflare challenge on `jobs.gusto.com`. Look for a
  legitimate, un-challenged public feed (e.g. an embed/widget or JSON endpoint
  linked from real Gusto-hosted careers pages). If none → same **stop** outcome
  as iCIMS. Solving or evading the challenge is out of scope.

---

## Docs and CLAUDE.md updates (in each PR, for the platform it ships)

- **`jobx-backend/docs/ats-api-reference.md`** — a `## {Platform} — VERIFIED (…date…)`
  section per platform (endpoints, JSON/HTML shape, date format, dead-board
  signal, quirks), new fixtures in "## Test fixtures", new rows in the
  "Careers-page URL forms" table. Gated platforms get an "INVESTIGATED — not
  supported, because …" section.
- **`jobx-backend/docs/ats-test-data.md`** — a `### {PLATFORM}` block with the
  live company + token used.
- **`jobx-backend/CLAUDE.md`** (and the mirrored passages in
  `jobx-frontend/CLAUDE.md`):
  - "ATS integration approach (DECIDED)" (~line 779): add the new endpoints;
    replace "Trickier/later: Rippling, Recruitee, BambooHR, Workday — no clean
    public API…" with the tier model (API tier / HTML tier / not supported and
    why), and state the two new rules: **host-built tokens must go through
    `BoardTokens`** and **HTML-tier fetchers must throw on zero cards without
    an explicit empty-board marker**, and **never bypass bot protection**.
  - "All five platforms are implemented…" → updated count/list.
  - Roadmap step 3 (~line 958) "Harder platforms … still out of scope" →
    updated status.
  - `WatchedCompanyRequest` / add-company section: note Workday's composite
    `tenant/wdN/site` token.

## Critical files

- `jobx-backend/src/main/java/com/jobx/enums/AtsPlatform.java`
- `jobx-backend/src/main/java/com/jobx/resolve/AtsUrlParser.java` (+ `AtsUrlParserTest`)
- `jobx-backend/src/main/java/com/jobx/resolve/CompanyResolver.java` (`PROBEABLE`)
- new `jobx-backend/src/main/java/com/jobx/fetcher/BoardTokens.java`
- new `fetcher/{workday,rippling,bamboohr,jobvite,jazzhr,icims,gusto}/*Fetcher.java`
- `jobx-backend/src/main/resources/application.yml` (`jobx.fetch.workday.max-pages`)
- `jobx-frontend/src/app/core/models/watchlist.model.ts`, `shared/ui/auth-hero.ts`
- `jobx-backend/docs/ats-api-reference.md`, `docs/ats-test-data.md`, both `CLAUDE.md`s

Reuse, don't reinvent: `AtsFetcher` contract + default `validateBoard`,
`BoardPreview.SAMPLE_SIZE`, `FetchFilter.isKnown/isTooOld`, `ExperienceParser`,
Jsoup text-stripping, `AtsFetchException`, `FixtureSupport.fixture/company`,
the Workable two-call/skip-on-detail-failure pattern and its tests.

## Verification (per platform PR)

1. `./mvnw test` — fixture tests for list/detail/preview mapping, filter test
   (known + too-old → 0 detail calls), detail-failure test, dead-board test,
   `AtsUrlParserTest` URL forms, `BoardTokens` hostile-input tests,
   `CompanyResolverTest` for the new probe/hint behavior.
2. Frontend: `npm test` / `ng build` for the model changes.
3. Live, local app (`run` skill): add the company via the add-company modal by
   pasting its real careers URL → confirmation card shows real titles → Watch
   → trigger manual fetch (`WatchlistControllerFetchTest` path) → jobs appear
   in the feed with descriptions, locations, dates; `last_fetch_status =
   SUCCESS`.
4. Dead-board check: add a bogus token → 400 naming the token; break a watched
   board's token in the DB → next poll marks it FAILED (not SUCCESS with 0).
5. Workday cost check: log line shows detail calls ≈ new postings only on the
   second poll of the same board.
