# Plan: Add-company improvements (two input modes, empty boards, richer job fields, ATS-move re-detection, smart crawler for custom careers pages)

Suggested order: A (two modes + one-hop) → B (empty boards) → C (job fields) →
E1 (static crawler + E-bench benchmark + "Crawled" badge) → E2 (Playwright,
benchmark extended to rendered pages) → D (move re-detection, which covers
native ↔ custom in both directions and reuses the E2 rendered SNIFF) → E3
(pagination + iframes, only if the benchmark shows it's needed).

## Context

A comparison with OpenJobRadar's integrations page found gaps in how JobX adds
and keeps companies:

1. Adding a company is one free-text box ("name, website or careers link"), and
   the resolver guesses which of the three it got. The user wants **two explicit
   options: by company name, or by the careers-page URL of the company**.
2. A careers page that doesn't link its ATS board directly (e.g. a `/careers`
   landing page with an "Open roles" button) yields nothing, because
   `SafeUrlFetcher` fetches only the exact URL.
3. Boards with **zero open roles are rejected**, even when the user pasted the
   board link itself. You can't watch a company "for when they start hiring".
4. When a company **switches ATS** (PhonePe: Greenhouse → SmartRecruiters), the
   board goes FAILED and stays that way. Someone has to go and find the new one.
5. Jobs store no **department, team or employment type**, although most ATS
   payloads already carry them.
6. Companies **without a standard ATS** (a careers page on their own site) can't
   be watched at all. OpenJobRadar handles them with a "smart crawler": a
   headless browser, job-listing pattern detection and filtering of false
   positives. JobX needs the same thing as the last resort in URL mode (PR E).

Out of scope, by decision: **email alerts** (not now), and **guessing a website
from a name** (the name path keeps catalog + slug probing). The crawler only
runs on a careers-page URL the user gives.

The project's core rule stays intact: JobX *proposes* a board, and a human
confirms it against real evidence. Nothing is watched or switched silently.

---

## PR A: Two add-company modes

**Backend**
- `dto/ResolveRequest.java`: add `@NotNull Mode mode` (`NAME | URL`), and change
  the validation messages to match the mode.
- `resolve/CompanyResolver.resolve(user, query)` → `resolve(user, mode, query)`:
  - **NAME**: step 1 CATALOG → step 4 PROBE (`SlugCandidates.from(name)`). Never
    fetches a page, even if the text contains a dot.
  - **URL**: the input must look like a URL (the existing `looksLikeUrl`, else 400
    "enter a careers page link"). Steps: 2 URL parse → 3 SNIFF (+ PR A2 below)
    → 4 PROBE on the domain label, narrowed by `platformHint` (existing
    behavior). Catalog is skipped, because the link is more specific than any
    name match.
- `controller/WatchlistController.resolve`: pass the mode through, and log it.
- The unsupported report (`UnsupportedBoardReportRequest` /
  `unsupported_board_requests`) records the mode too. Add a nullable `mode TEXT`
  column in the V8 migration (below), so demand can be split into "known name,
  no board" and "real careers page, unsupported ATS".
- Tests: `CompanyResolverTest`. NAME mode never calls `SafeUrlFetcher`; URL
  mode with a bare name is a 400; the existing URL/SNIFF/PROBE cases move under
  URL mode.

**PR A2: one-hop follow of careers links (URL mode only)**
- In `CompanyResolver`, when SNIFF finds no board on the pasted page, collect up
  to **3** candidate links from that HTML with jsoup (already a dependency):
  anchors whose text or href matches `careers|jobs|open (roles|positions)|join
  us|work with us|we'?re hiring`, **on the same registrable domain** (or a
  `careers.`/`jobs.` subdomain of it). Fetch each through
  `SafeUrlFetcher.fetch` (SSRF guard, redirect re-checks, 2 MB cap and timeouts
  all apply per hop) and run `AtsUrlParser.findAll` / `platformHint` on it.
- Bounded: at most 1 hop and 3 extra fetches per resolve. Keep it inside the
  existing `/watchlist/resolve` rate-limit budget. `SafeUrlFetcher` itself stays
  a single-URL fetcher; the (bounded) link-following logic lives in the
  resolver.
- Tests: an HTML fixture landing page linking to a fixture page that has the
  Greenhouse link (stub `SafeUrlFetcher`). A cross-domain "careers" link is
  ignored. The cap of 3 is enforced.

**Frontend** `jobx-frontend/src/app/shared/overlays/add-company-modal.ts`
- The input step gets a two-option segmented control: **"Company name"** |
  **"Careers page link"**.
  - Name: label "Company name", placeholder `Razorpay`, with the existing
    debounced catalog typeahead.
  - URL: label "Careers page link", placeholder `razorpay.com/careers ·
    jobs.lever.co/fampay`, no typeahead, and a client-side check that it looks
    like a URL.
- `api.resolve(query)` → `api.resolve({ mode, query })`, and update
  `ResolveRequest` in `core/models/watchlist.model.ts`.
- Dead-end copy per mode. Name: "We couldn't find a job board for "X". Try
  pasting their careers page link." (with a button that switches the mode). URL:
  the existing "uses {platform} / portal unsupported" copy.
- Keep the Advanced (manual platform + token) section, the four steps and the
  feed reload on success as they are.

---

## PR B: Watching boards with zero open roles

The zero-roles rule exists because **Workable and SmartRecruiters answer a
bogus token with a cheerful empty 200** (`docs/ats-api-reference.md`). On
platforms that 404 an unknown token, an empty board is provably real.

- `fetcher/AtsFetcher.java`: add `default boolean distinguishesMissingBoard() {
  return false; }`. Override it to `true` in `GreenhouseFetcher`, `LeverFetcher`
  and `AshbyFetcher`, **after a recon check** that each 404s a bogus token today
  (Greenhouse and Lever are documented; Ashby needs a curl check). Record the
  results in `ats-api-reference.md`.
- Default `validateBoard`: reject `jobCount == 0` only when
  `!distinguishesMissingBoard()`. POST /watchlist then accepts empty GH/Lever/
  Ashby boards, and the fetch path already treats empty as "nothing new".
- `CompanyResolver.previewCandidate`: allow a zero-count preview **only when
  source is URL or SNIFF** (the board came from a link the user gave, or from
  the company's own page) **and** the platform distinguishes missing boards.
  PROBE and CATALOG keep requiring live roles, because a guessed slug that
  exists but is empty may belong to a different company.
- `dto/ResolvedBoardResponse`: update the javadoc ("Never zero" becomes "zero
  only for a URL/SNIFF board on a platform that 404s unknown tokens").
- Frontend confirm card: when `jobCount === 0`, show "No open roles right now.
  We'll keep checking and new ones will show up in your feed." instead of the
  sample-title list, and keep the Watch button enabled.
- Tests: `validateBoard` for each platform class. The resolver accepts
  zero-count URL/SNIFF on Greenhouse, rejects it on Workable, and rejects it
  for PROBE on Greenhouse.

---

## PR C: Department, team and employment type on jobs

- Migration **`V8__add_company_fields.sql`**, one file for this plan (Flyway
  order):
  - `jobs`: `department TEXT`, `team TEXT`, `employment_type TEXT` (nullable).
  - `unsupported_board_requests`: `mode TEXT` (PR A).
  - `companies`: `careers_url TEXT`, `failing_since TIMESTAMPTZ`,
    `last_redetect_at TIMESTAMPTZ`, `moved_to_platform TEXT`,
    `moved_to_token TEXT`, `moved_to_reason TEXT` (`FAILING | NATIVE_AVAILABLE`)
    (PR D).
  - `companies`: `crawl_mode TEXT` (E2), and `crawl_pages INT` (E3, only if E3
    ships).
  - If the PRs ship separately, split this into V8/V9/V10 in merge order.
    Whichever number is free at the time wins, and the ATS-coverage plan needs
    no migrations.
- `entity/Job.java`: add the three fields. Display only; **`MatchScorer` is not
  changed** (CLAUDE.md: "port this logic, don't redesign it").
- Fetcher mapping (fields confirmed in docs and fixtures; still check against
  them when implementing):
  - Lever: `categories.department`, `categories.team`, `categories.commitment`.
  - Ashby: `department`, `team`, `employmentType`.
  - Greenhouse (`?content=true`): `departments[0].name`. Greenhouse has no
    employment type field.
  - SmartRecruiters detail: `department.label`, `typeOfEmployment.label`.
  - Workable v2 detail: `department`, `employment_type` (verify against
    `workable-v2-job.json`).
- Extend each fetcher's fixture test to assert the new fields.
- `dto/MatchResponse.java`: add `department`, `team`, `employmentType`, read from
  `match.getJob()` when it's non-null. They are null after TTL expiry. That's
  acceptable, because an expired card already shows less.
- Frontend: add the fields to `MatchResponse` in the core models. Show a small
  meta line ("Engineering · Full-time") on the feed card and in
  `match-detail-drawer.ts`, and leave it out when the values are null.
- No backfill: existing rows stay null and fill in as new postings arrive
  (the 6-day TTL turns the table over within a week anyway).

---

## PR D: Re-detecting a company that moved to another ATS

Goal: turn "FAILED for days" into "looks like they moved to Lever. Switch?"

1. **Remember where the board came from.**
   - `WatchedCompanyRequest` gets an optional `careersUrl` (max 2000). The modal
     sends the URL-mode input whenever the chosen candidate's source is
     URL/SNIFF.
   - `getOrCreateCompany` stores it on a new company row, and fills it in
     only-if-null on an existing row. It's a hint for later SNIFF runs, always
     fetched through `SafeUrlFetcher`, so a bad value costs at most one guarded
     GET.
2. **Track how long a board has been failing.**
   - `FetchScheduler.markFailed` sets `failing_since` if it is null.
   - `persistFetched` (success) clears `failing_since` and the `moved_to_*`
     fields.
3. **`resolve/BoardRedetector.java`** (new `@Component`), called at the end of
   `fetchAllCompanies` for companies where `failing_since < now - 12h` and
   (`last_redetect_at` is null or `< now - 24h`), capped at N companies per
   cycle (`jobx.redetect.max-per-cycle: 5`):
   - If `careers_url` is set: SNIFF it (plus the PR A2 one-hop follow, and the
     E2 rendered SNIFF when the page is JS-driven) and preview every native
     board found other than the current `(platform, token)`.
   - Otherwise: `BoardProbe.probe(PROBEABLE minus current platform,
     SlugCandidates.from(displayName))`.
   - **Native → custom:** if no native board turns up but `careers_url` is set,
     run `CareerPageExtractor` on it (static, then rendered). If that finds
     live jobs, propose a `CUSTOM` board. This covers a company that leaves an
     ATS for an in-house careers page.
   - **A failing CUSTOM board:** its `board_token` *is* its careers URL, so
     re-SNIFF that. The company may have adopted an ATS since, or moved the page
     (the one-hop follow finds the new location).
   - The first candidate with **live roles** (never zero, since this is a
     guess) is stored in `moved_to_platform`/`moved_to_token`, with
     `moved_to_reason = FAILING`. Set `last_redetect_at` either way.
3b. **Custom → native upgrade (for healthy boards too).** A `CUSTOM` board that
   is working fine can still gain a real ATS, and native API data is always
   better than crawled data. So `BoardRedetector` also picks up healthy
   `CUSTOM` boards whose `last_redetect_at` is null or older than **7 days**
   (they share the same per-cycle cap). It re-SNIFFs the page, and if a native
   board with live roles is linked, it stores it with
   `moved_to_reason = NATIVE_AVAILABLE`. Unlike `FAILING` proposals, these
   are **not** cleared by a successful fetch, because the old board still
   works. They're cleared by switching, or when a later redetect no longer
   finds the native board.
   - **It never switches on its own.** This is a proposal, and the user confirms
     it (the never-guess rule).
4. **API**
   - `WatchedCompanyResponse` gets `movedTo: {atsPlatform, boardToken, boardUrl}
     | null`.
   - New `POST /watchlist/{id}/move`: re-previews the proposed board, then
     `getOrCreateCompany` for it (reusing `validateBoard`), re-points this
     user's watch row with `watchAndBackfill`, and deletes the user's matches on
     the old board (same as `remove`). The old company row stays; the scheduler
     skips it once nobody watches it.
5. **Frontend**: a watchlist row with `movedTo` shows a banner with **Switch**
   and **Dismiss** buttons. Dismiss is client-side only for now. The copy
   depends on `movedTo.reason`:
   - `FAILING` (on a FAILED row): "{Company} seems to have moved to
     {Platform}".
   - `NATIVE_AVAILABLE` (on a healthy CUSTOM row): "{Company}'s careers page now
     uses {Platform}. Switch to the direct feed for more complete data?"
   - A proposal *to* `CUSTOM` says "moved to their own careers page", and the
     switched board then carries the "Crawled" badge.
   `WatchedCompanyResponse.movedTo` gains `reason`.
6. Tests: `BoardRedetectorTest`:
   - a careers page that now links Lever proposes Lever, and the current board
     is excluded;
   - a zero-role candidate is not proposed;
   - native → CUSTOM when only the extractor finds jobs;
   - a failing CUSTOM board re-SNIFFs its own URL;
   - a healthy CUSTOM board whose page now links Greenhouse gets a
     `NATIVE_AVAILABLE` proposal, and a successful fetch doesn't clear it;
   - the 12h/24h/7-day gates and the per-cycle cap hold.

   A `FetchSchedulerHealthTest` case covers `failing_since` being set and
   cleared, and a controller test covers `/move` in both directions (native ↔
   CUSTOM).

---

## PR E: Smart crawler for custom careers pages (platform `CUSTOM`)

The last resort in **URL mode**. It runs only when parse, SNIFF (+ one-hop),
rendered SNIFF and PROBE all find no native board, because a native API always
beats crawling. It is split into **E1, static extraction** (jsoup, no new
infrastructure) and **E2, a headless browser** (Playwright), so E1 can ship and
prove itself first.

### E1: Static extraction

**Model**
- `AtsPlatform.CUSTOM`. `board_token` = the **canonical careers URL**: https,
  lowercase host, no fragment, no tracking params (`utm_*`, `gclid`, `ref`),
  trailing slash dropped. `companies` keeps UNIQUE (platform, token), so two users
  adding the same page share one board. Never probeable, and not offered in
  the Advanced manual form.
- **Security, the most important part:** the scheduler will fetch a
  *user-supplied URL* on every cycle, forever. Every CUSTOM request (list page,
  detail pages, robots.txt) goes through `SafeUrlFetcher`: http/https only,
  every resolved address public (`PrivateAddressGuard`), redirects re-checked
  per hop, 2 MB cap and short timeouts. Nothing uses the shared ATS `WebClient`.
  The DNS-rebinding residual already accepted in CLAUDE.md now applies to a
  recurring fetch, so write that down explicitly.

**`crawler/CareerPageExtractor.java`** (new package `com.jobx.crawler`): a pure
function `(pageUrl, html) → List<ExtractedJob{title, url, location, department,
employmentType, postedAt, description}>`, applying these strategies in order and
stopping at the first that yields jobs:
1. **JSON-LD `JobPosting`** (schema.org). Many in-house careers pages embed it
   for Google Jobs. Handle a single object, arrays, `@graph` and
   `ItemList.itemListElement`. Map `title`, `url`, `jobLocation.address`,
   `datePosted`, `employmentType`, `description` (HTML → text). This is the
   most reliable source.
2. **Heuristic listing detection** (jsoup):
   - Collect anchors on the same site (or a `careers.`/`jobs.` subdomain)
     whose href looks like a job detail page: path contains
     `job|jobs|career|careers|position|positions|opening|openings|vacanc|role|apply`
     followed by a further segment, or has an id-like query param.
   - **Repeated-structure check:** group candidates by their parent's DOM
     signature (tag + class path, 2–3 levels up). Keep the largest group with
     **≥ 2** members; a real listing repeats, and nav/footer links don't.
   - **Title validation:** 3–120 chars, ≤ 15 words, not a stop-phrase (`about us`,
     `blog`, `login`, `privacy`, `see all jobs`, `apply now`, `learn more`,
     `benefits`, `life at`, …), and not the page's own title. Pull location and
     department from short sibling text within the same card when present.
   - **URL filters:** drop `/blog/`, `/news/`, `/press/`, `/events/`, `/tag/`,
     `/category/`, pagination (`?page=`), anchors, and file downloads.
3. Nothing found → empty result, and the caller decides (see "fail loudly").

**`fetcher/custom/CustomPageFetcher.java` implements `AtsFetcher`**, modelled
on the Workable two-call design:
- `fetch`: fetch the page via `SafeUrlFetcher` → extractor.
  - `externalId` = SHA-256 (hex, 32 chars) of the canonical job URL. Without a
    URL (JSON-LD only), hash title + location.
  - Skip `filter.isKnown(id)` and `filter.isTooOld(postedAt)` before any detail
    fetch.
  - Description: from JSON-LD if present. Otherwise fetch the detail page for
    **new ids only**, capped at `jobx.crawler.max-detail-fetches: 25` per cycle.
    Take text from JSON-LD on the detail page, else `<main>`/`<article>`/the
    largest text block. Then `ExperienceParser.parse`.
  - A detail failure skips the job so it retries next cycle (the same rule as
    Workable).
- **Fail loudly:** a page that loads but yields zero jobs throws
  `AtsFetchException("no job listings recognised on …")`, unless it matches a
  "no openings" phrase (`no open (positions|roles)`, `no current openings`,
  `not hiring`, `check back`, …). A redesigned page therefore shows as FAILED,
  not as a silently empty feed.
- **Bot protection:** a Cloudflare/Akamai challenge page (`Just a moment…`,
  `cf-chl`, `captcha`) → `AtsFetchException("careers page is behind bot
  protection")`. **No bypass.**
- **robots.txt:** honour `Disallow` for our User-Agent on the careers path
  (fetched via `SafeUrlFetcher` and cached 24h per host). A disallowed page is
  refused at resolve time with a clear message.
- `previewBoard` = one page fetch + extraction → count + first 3 titles.
  `displayName` comes from `og:site_name` or `<title>`.
- `validateBoard`: at least one job, or a matched "no openings" phrase.

**Resolver (URL mode)**
- New `Source.CRAWL`. In `CompanyResolver.resolve`, after PROBE, run the
  extractor on the pasted page (whose HTML is already fetched by SNIFF, so no
  extra request) and then on the one-hop careers links. Offer a CUSTOM
  candidate whose `boardToken` is the canonical URL of the page the jobs were
  found on.
- Confirm card: label it "Read from their careers page". Show the sample
  titles, and state the caveat "Crawled, so some roles may be missed". The user
  confirms as usual.

**Frontend: a visible "crawled" label, carried through to the jobs**
- `PLATFORM_LABEL.CUSTOM = 'Careers page'`. The watchlist row shows the host
  instead of a token.
- `dto/MatchResponse.java` gets `atsPlatform` (from `match.getCompany()`, so it
  survives job expiry). The frontend `MatchResponse` model gets the same field.
- The feed card (under `shared/feed/`) and `match-detail-drawer.ts` show a small
  **"Crawled"** badge when `atsPlatform === 'CUSTOM'`. Its tooltip reads "Read
  from the company's careers page. Details may be incomplete." Native-ATS
  cards are unchanged.
- The badge uses the existing theme tokens, so light and dark mode both work
  (per the frontend CLAUDE.md).

**Tests** (fixtures under `src/test/resources/fixtures/custom/`, captured from
real pages during recon):
- a JSON-LD page, a static list page, and a page with the listing in `@graph`;
- a noise page (blog + nav + "Apply now" links), which must yield **0**;
- a "no openings" page, a challenge page, and a robots-disallowed page;
- canonical URL and `externalId` stability;
- fetch-filter cost (known ids → 0 detail fetches);
- SSRF: a CUSTOM token pointing at `127.0.0.1` or a private host is refused at
  add time *and* at fetch time.

### E2: Headless rendering (Playwright) for JavaScript-heavy pages

- Dependency `com.microsoft.playwright:playwright` (Java 17 OK). Chromium is
  installed once with `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI
  -Dexec.args="install chromium"`. Document this in CLAUDE.md dev setup.
  Controlled by `jobx.crawler.render.enabled` (default `true` locally, `false`
  in tests).
- **`crawler/BrowserRenderer.java`**, `@Component`:
  - **Threading rule:** Playwright's Java objects are **not thread-safe**. A
    `Playwright` instance and its `Browser`/`BrowserContext`/`Page` must be
    created and used on one thread. So callers never touch Playwright; they
    submit a URL and receive a `Future<String>` of rendered HTML.
  - **Two dedicated render lanes**, each a single-thread executor that owns its
    own lazily created `Playwright` + headless Chromium:

    | Lane | Config (default) | Used by | When busy |
    |---|---|---|---|
    | `interactive` | `jobx.crawler.render.interactive-threads: 1` | `/watchlist/resolve` (a user is waiting) | Short queue; give up after `jobx.crawler.render.interactive-wait-ms: 25000` and tell the user |
    | `background` | `jobx.crawler.render.background-threads: 1` | Scheduler, `RENDER` boards only | Queue; slowness is harmless |

    Why two lanes and not one shared pool: a render blocks its thread for up
    to 20s, so a scheduler cycle over several JS-heavy boards must never make a
    user in the add-company modal wait behind it. Why only one thread each:
    each thread owns a Chromium process (~100–200 MB), and JobX's volume doesn't
    justify more. Raise `background-threads` if `RENDER` boards grow past about
    50; no code change is needed.
  - A **fresh private-mode `BrowserContext` per render**, closed afterwards, so
    no cookies or storage carry between sites and memory stays flat.
  - Navigate with `DOMCONTENTLOADED`, then `waitForLoadState(NETWORKIDLE)`
    capped at 8s (chatty sites never go idle, so use what's there). The
    overall page budget is 20s. Rendered HTML is truncated to the same 2 MB cap
    as `SafeUrlFetcher`.
  - Block images, media and fonts. Downloads are off; dialogs are
    auto-dismissed.
  - Crash handling: on a `PlaywrightException` the lane discards its browser
    and relaunches on the next job, and that render fails as the board's
    FAILED health.
  - `@PreDestroy` shuts down both executors and closes every browser and
    `Playwright` instance, so no orphan Chromium processes are left.
  - Detail pages (job descriptions) never use the browser; they use the cheap
    plain fetch. That caps browser use at one render per board per cycle.
  - Deployment: a Linux server also needs `playwright install-deps chromium`
    for system libraries. Add it to the CLAUDE.md dev setup next to the install
    command.
- **SSRF inside the browser:** a rendered page loads sub-resources and makes
  XHRs of its own. Install `context.route("**/*", …)` so **every** request
  (document, script, XHR, redirect) is checked with `PrivateAddressGuard` and
  http/https-only, and anything else is aborted. Without this, a page could
  make our server's browser call internal addresses.
- Wiring:
  - **Rendered SNIFF:** in URL mode, when static SNIFF finds no ATS link and the
    page looks JS-driven (little visible text, an SPA root such as
    `#root`/`#__next`/`app-root`, many scripts), render it and re-run
    `AtsUrlParser.findAll` on the rendered DOM. This recovers native boards
    that are loaded in JavaScript (the Atlan/FamPay case), *before* probing and
    crawling.
  - **Rendered crawl:** when the extractor finds nothing in static HTML, run it
    again on the rendered DOM.
  - A new column `companies.crawl_mode TEXT` (`STATIC | RENDER`) is set at add
    time from whichever pass found the jobs. The scheduler renders only
    `RENDER` boards, so the expensive path is never paid by pages that don't
    need it. Add it to the V8 migration list.
- Tests: a `BrowserRendererIT` gated on an env var (`JOBX_PLAYWRIGHT_IT=1`)
  renders a local static HTML fixture that builds its job list in JS, and
  checks that a private-address sub-request is aborted.

### E-bench: Crawler accuracy benchmark (ships with E1, extended in E2)

Fixture tests prove the code does what we wrote; they don't show whether the
crawler is any good on real sites. OpenJobRadar claims ~95%. We should
**measure** ours and never quietly regress.

- **Corpus:** `src/test/resources/crawler-bench/{site}/` for **15–20 real
  in-house careers pages**, picked for variety:
  - JSON-LD pages; static lists; listings grouped by department; tables; card
    grids;
  - noise-heavy pages (big nav, blog teasers, "Apply now" buttons);
  - a "no openings" page;
  - from E2 on, JS-rendered pages.

  Each site has:
  - `page.html`: a snapshot. For JS pages it's the *rendered* DOM, captured
    once with `BrowserRenderer`, so the test runs offline with no browser;
  - `expected.json`: the true job list (title + canonical URL), **labelled
    by hand** from the live page and cross-checked against any job count the
    page itself shows;
  - `source.txt`: the URL and capture date.
- **`crawler/CrawlerBenchmarkTest.java`** runs in the normal `./mvnw test` (it is
  offline and fast):
  - It runs `CareerPageExtractor` on each snapshot and matches results to
    `expected.json` by canonical URL, falling back to normalized title.
  - It computes **precision** (share of extracted items that are real jobs) and
    **recall** (share of real jobs found), per site and overall, and prints a
    table.
  - **Floors:** it fails if overall precision drops below
    `bench.min-precision` or recall below `bench.min-recall`. Both are set to
    the **first measured baseline** when E1 lands. They may be raised, never
    lowered without an explanation in the PR. Target: precision ≥ 0.95
    (false jobs in a feed are worse than missed ones), recall ≥ 0.85.
  - Any site with precision < 0.8 is listed explicitly, so one bad site isn't
    hidden by a good overall number.
- **Re-capture tool:** `CrawlerBenchCapture` (a `main` in test sources, not a
  test) re-fetches or re-renders a site's snapshot when its markup changes.
  Re-labelling `expected.json` is still manual, on purpose.
- **Process:** any change to the extractor heuristics must keep the benchmark
  green. A newly broken real site gets added to the corpus, like a
  regression test.
- The results also decide whether E3 is needed: if recall is lost mainly to
  pagination or iframes, that justifies E3.

### E3 (later phase, gated by the benchmark): Pagination and iframes

Build this only when the E-bench corpus (or real FAILED/low-count boards) shows
recall lost to these cases. Both add request volume and complexity.

- **Pagination, static:** follow `rel="next"`, or a "Next"/page-number link on
  the same path (`?page=N`, `/page/N`). Cap `jobx.crawler.max-pages: 5`. Every
  page goes through `SafeUrlFetcher`, results are deduped by `externalId`, and
  it stops when a page adds nothing new.
- **Pagination, rendered (E2 lanes):**
  - click a visible "Load more" / "Show more" / "See all jobs" button, or
    scroll to the bottom for infinite scroll, up to 5 times;
  - stop when the count of recognised jobs stops growing;
  - the whole thing stays within one render's page budget (raised to 30s for
    these boards).
  - Stored per board as `companies.crawl_pages` (int, nullable), so only
    boards that need it pay for it.
- **Iframes:**
  - Static: for each `<iframe src>` on the page (cap 3) that isn't already an
    ATS link `AtsUrlParser` recognises, fetch it via `SafeUrlFetcher` and
    run the extractor on it. This counts toward the one-hop budget.
  - Rendered: walk `page.frames()` (the browser's request guard already
    covers them), take each frame's `content()`, and run `AtsUrlParser` and
    the extractor on it.
  - Jobs found in a frame use the frame's URL as the base for their links.
- Tests: paginated and iframe fixtures, plus benchmark sites that need them.
  The benchmark floors must still hold.

---

## CLAUDE.md and docs updates (with each PR)

- `jobx-backend/CLAUDE.md`, add-company section (~line 425–490):
  - The four-strategy description becomes **two modes**: NAME = catalog →
    probe, URL = parse → sniff (+ one-hop follow) → probe.
  - `SafeUrlFetcher` rule 5, "Only the exact URL given is fetched. There is no
    crawling.", gets amended: the resolver may follow **at most 3 same-site
    careers links, one hop**, each through `SafeUrlFetcher`. Update the class
    javadoc to match.
  - The zero-roles rule becomes: allowed only for URL/SNIFF boards on platforms
    that 404 unknown tokens.
  - A new "Board moves" paragraph (failing_since → redetect → proposal → user
    confirms via `/move`). This replaces "Treat a sudden FAILED board as 'check
    where the company's careers page points now'" (~line 794) with a pointer
    to `BoardRedetector`.
  - Job fields: department/team/employment type are display-only, not scored.
  - "ATS integration approach": the old "mark portal unsupported rather than
    faking support" becomes "native API first; otherwise the **CUSTOM smart
    crawler** (PR E), clearly labelled as crawled". Add these rules:
    - every CUSTOM request goes through `SafeUrlFetcher`, and every browser
      request through the `PrivateAddressGuard` route;
    - fail loudly on zero recognised jobs;
    - honour robots.txt;
    - never bypass bot protection;
    - render only `crawl_mode = RENDER` boards.
  - Dev setup: the Playwright Chromium install command and
    `jobx.crawler.render.enabled`.
  - `new-ats-add.md` cross-note: a JS-only iCIMS/Gusto-style page that fails
    its recon gate can still fall through to CUSTOM (never past a bot
    challenge).
- `jobx-frontend/CLAUDE.md`: the mirrored add-company paragraphs (two-mode modal,
  empty-board confirm card, moved-board banner).
- `jobx-backend/docs/ats-api-reference.md`: the bogus-token 404 results per
  platform, and the department/type field paths per platform.
- Save this plan as `jobx-backend/add-company-improvements.md`, and link it from
  CLAUDE.md next to `new-ats-add.md`.

## Critical files

- Backend: `dto/ResolveRequest.java`, `resolve/CompanyResolver.java`,
  `resolve/SafeUrlFetcher.java` (javadoc only), new `resolve/BoardRedetector.java`,
  `fetcher/AtsFetcher.java` + the five fetchers, `entity/{Job,Company}.java`,
  `dto/{MatchResponse,WatchedCompanyRequest,WatchedCompanyResponse,ResolvedBoardResponse}.java`,
  `controller/WatchlistController.java`, `scheduler/FetchScheduler.java`,
  `db/migration/V8__add_company_fields.sql` (+ `companies.crawl_mode`),
  `application.yml` (`jobx.crawler.*`), `pom.xml` (Playwright), new
  `crawler/{CareerPageExtractor,BrowserRenderer,RobotsTxt}.java`, new
  `fetcher/custom/CustomPageFetcher.java`, `enums/AtsPlatform.java` (`CUSTOM`).
- Frontend: `shared/overlays/add-company-modal.ts`, `core/models/watchlist.model.ts`,
  the feed card under `shared/feed/`, `shared/overlays/match-detail-drawer.ts`.

Reuse: `SafeUrlFetcher.fetch`, `AtsUrlParser.findAll/platformHint/boardUrl`,
`BoardProbe.probe`, `SlugCandidates.from`, `CompanyResolver.previewCandidate/toResponse`,
`WatchlistService.watchAndBackfill/createCompany`,
`WatchlistController.getOrCreateCompany/validateBoard`, `FixtureSupport`, and the
existing careers-page fixtures (`careers-*.html`).

## Verification

1. `./mvnw test`: the new and updated tests listed per PR, plus the whole
   existing suite (the resolver, watchlist and scheduler tests must stay
   green).
2. `ng test` / `ng build` for the frontend.
3. Live, in the local app (`run` skill):
   - Name mode: "Groww" is found via catalog or probe. "Razorpay" by name hits
     the dead end, which offers the switch to URL mode.
   - URL mode: `razorpay.com/careers` resolves via SNIFF to
     `razorpaysoftwareprivatelimited`. A landing page whose board sits one link
     away resolves via the one-hop follow.
   - Empty board: paste a Greenhouse/Lever board URL with 0 roles. The confirm
     card shows the "no open roles" state, and watching succeeds. A Workable
     ghost account (`workable-ghost-account` case) is still rejected.
   - Fields: after a fetch, feed cards show department and employment type for
     Lever/Ashby boards.
   - Crawler: paste a careers page on the company's own site (recon picks 3
     real ones: one with JSON-LD, one static list, one JS-rendered). Each
     resolves to a CUSTOM candidate with correct titles; Watch → Check now
     stores jobs with working apply links; a second check adds 0 duplicates. A
     page with no jobs listed but no "no openings" text shows FAILED, and a
     page behind a challenge gives the bot-protection message. Their feed
     cards show the "Crawled" badge.
   - Benchmark: `./mvnw test -Dtest=CrawlerBenchmarkTest` prints per-site
     precision/recall and passes the floors.
   - Move: in the dev DB, point a watched company at a dead token with
     `careers_url` set and backdate `failing_since` by 13h. After the next cycle
     the row shows the "moved to" banner, Switch re-points the watch, and new
     matches arrive. Also check both custom directions:
     - native → CUSTOM: a dead token whose `careers_url` is a plain in-house
       page;
     - CUSTOM → native: a CUSTOM board whose page links a Greenhouse board,
       with `last_redetect_at` backdated 8 days, shows the "switch to the direct
       feed" banner while it is still healthy.
