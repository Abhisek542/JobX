# Plan: PR 5 (JazzHR), PR 6 (iCIMS), PR 7 (Gusto)

## Status (2026-10-04, implemented)

- **PR 5 JazzHR: built.** `fetcher/jazzhr/JazzHrFetcher.java`, 20 tests.
- **PR 7 Gusto: built.** The gate check passed: 20/20 requests answered 200 with
  the app's default client. `fetcher/gusto/GustoFetcher.java`, 18 tests.
- Both were run once against the live sites through the real `WebClient` config
  (a throwaway test, deleted afterwards). That run found postings with no JSON-LD
  on both platforms (2/21 and 3/14), now handled by a page-body fallback.
- **PR 6 iCIMS: NOT built, and the recon below was wrong.** The CAPTCHA came from
  the Chrome user agent used for recon; an honest agent gets plain 404s for
  unknown hosts and a 200 for a real one. The real finding: customers such as
  UCLA have moved to **iCIMS Career Sites** (ex-Jibe), which serves JSON at
  `https://{customer-domain}/api/jobs`. Building that needs a decision on
  custom-domain tokens. See the iCIMS section of `docs/ats-api-reference.md`.
- **Deviations from the plan:**
  - Shared new files rather than per-fetcher copies: `HtmlPage` (status +
    Location), `JsonLd` (JobPosting extraction) and `PastTtlMemo`. They are new
    files, so they don't conflict with the Jobvite branch.
  - **`PastTtlMemo` was not in the plan.** Neither list carries dates, and
    FetchScheduler never stores or tombstones a too-old posting. Without the memo,
    every stale posting still listed would cost a detail call every cycle (on Gusto,
    plus 1 s of crawl delay each).
  - Gusto's empty board was never seen live. A "Careers at …" page with the job
    list rendered and empty is read as quiet; anything else with zero postings throws.
  - The resolver was not changed: a pasted Gusto *posting* link still yields only
    the platform hint (option (b) below).

The original plan follows.

Follows `new-ats-add.md`. PR 0 (groundwork) is merged. The enum values, `BoardTokens`,
the `AtsUrlParser` rules and `HOST_HINTS`, `boardUrl()`, the frontend `AtsPlatform`
union and `PLATFORM_LABEL` are all already in place for these three platforms.
Branch: `task/jazzHr-icmr-gusto`.

PR 1 (Workday, in `task/workday`) and PRs 2–4 (Rippling/BambooHR/Jobvite, in
`task/rippling-bamboHr-jobvite`) are being built **in parallel**, so this branch
can't copy the Jobvite fetcher as its HTML-tier template. See
"Merge-conflict hotspots" at the end.

## Recon already done (2026-10-04, curl, honest user agents only)

| Platform | Finding |
|---|---|
| **JazzHR** | Live board: `brennancenter.applytojob.com/apply` (21 jobs). Small live board: `sciakyinc` (2). Live empty board: `getinflow` (0 jobs, text "There are no open positions at this time.", no `list-group-item`). The list is server-rendered: `li.list-group-item > h3.list-group-item-heading > a[href=https://{sub}.applytojob.com/apply/{code}/{slug}]`, then `ul.list-group-item-text li` holding the location (`fa-map-marker`). The list's JSON-LD is only `@type: Organization` with a `name`, which gives the displayName. The detail page `/apply/{code}/{slug}` has JSON-LD `JobPosting` with `title, description, datePosted ("2026-10-01", date-only), validThrough, employmentType, jobLocation`. **Dead signals:** an unknown sub returns **302 → `https://info.jazzhr.com/job-seekers.html`**. The doc's old `jazzhr.com/job-seekers` target has moved, so match **any 3xx**, not a URL. A cancelled account answers **200** with `<title>JazzHR - Inactive Career Page`, seen live on `jazzhr.applytojob.com`. `/apply/jobs/feed` is a 302, so there is no public feed. |
| **iCIMS** | Every `{host}.icims.com/jobs/search?ss=1` tried (tesla, cvshealth, ibm, amazon, unitedhealthgroup **and a bogus host**) returns **405** with `x-amzn-waf-action: captcha` and the page `<title>Human Verification`, an AWS WAF CAPTCHA served from CloudFront. That is bot protection on every tenant, so the recon gate **fails**. |
| **Gusto** | `jobs.gusto.com` is behind Cloudflare, but the challenge applies to some clients only. The `curl/8` UA got 403 five times out of five, and the first Chrome-UA request got 403. The default `ReactorNetty/1.1.22` UA, an honest `JobX/1.0` UA and later requests all got **200**, with no challenge to solve. `robots.txt` allows everything except `/login/*` and asks for **`Crawl-delay: 1`**. Board: `GET /boards/{slug}` (e.g. `alexandria-electric-llc-7ca8ffc5-3b8d-421b-a0ed-c7e3dfc7303d`, a name slug plus a UUID) is HTML with `<title>Careers at {Company}` and `a[href^=/postings/]` links whose text is the job title. Posting: `GET /postings/{slug}-{uuid}` has JSON-LD `JobPosting` with `title, description, datePosted (full ISO with offset), validThrough, employmentType, hiringOrganization, jobLocation`. A bogus board returns **404**. `sitemap.xml` lists about 1,200 postings and no boards, so it is no use for discovery. |

So the outcomes are: **PR 5 builds a fetcher**, **PR 6 stops at the gate (docs and
tests only)**, and **PR 7 builds a fetcher** with a strict "challenge → FAILED,
never work around it" rule. This changes `new-ats-add.md`, which expected Gusto
to stop at the gate; update that section in PR 7.

---

## Shared rules for the two HTML-tier fetchers (PR 5, PR 7)

These come from `new-ats-add.md` and CLAUDE.md. Each fetcher implements them
itself, because a shared helper would collide with the Jobvite PR (see the end):

1. **Don't follow redirects; inspect them.** The `WebClientConfig` client keeps
   reactor-netty's default `followRedirect(false)`, and `.retrieve()` treats a
   3xx as success with an empty body. Read pages with
   `exchangeToMono(resp -> …)` and keep the status, the `Location` header and
   the body. Any **3xx means the board does not exist** and throws
   `AtsFetchException("… board '{token}' does not exist (redirected to …)")`.
   A 4xx or 5xx also throws.
2. **Zero cards on a 200 page throws**, unless the page shows the platform's
   known *empty-board marker*. Then it is a real, quiet board: `fetch` returns
   `List.of()` and `previewBoard` returns jobCount 0. So a changed layout
   surfaces as FAILED health and never as a silent empty feed.
3. **Only follow links we built.** The externalId is taken from an href by a
   strict regex. The detail URL is rebuilt from a fixed host plus that id;
   the scraped href itself is never requested. JazzHR: `/apply/([A-Za-z0-9]{6,20})/`
   on the board's own host. Gusto: `^/postings/([a-z0-9-]{1,200})$`.
4. **List → `FetchFilter.isKnown` → detail**, with a skip-on-detail-failure
   pattern copied from `WorkableFetcher`, including its comment explaining why
   a job is skipped rather than emitted without a description.
5. **Detail data comes from JSON-LD `JobPosting`, not CSS selectors.** Parse
   `script[type=application/ld+json]` with Jackson and pick the node whose
   `@type` is `JobPosting`, which may sit inside an array or `@graph`. The
   `description` HTML goes through `Jsoup.parse(..).text()` and then
   `ExperienceParser.parse`. This is the least fragile part of an HTML page.
6. **Never send a browser user agent and never retry around a challenge.**
   Keep the WebClient's honest default UA.
7. Package-private seams for fixture tests: `parseList`, `applyDetail`,
   `parsePreview`, and `fetchDetail` (so tests can count calls), the same as
   Workable.

---

## PR 5 — JazzHR (HTML tier)

### Recon to finish (first commit)
- Capture these fixtures in `src/test/resources/fixtures/`:
  - `jazzhr-brennancenter-list.html`
  - `jazzhr-brennancenter-detail.html` (one posting)
  - `jazzhr-getinflow-empty.html` (the real empty board)
  - `jazzhr-inactive.html` (the `jazzhr` sub's "Inactive Career Page")
- Check whether a big board paginates: find one with more than 50 jobs and look
  for `?page=` or "next". If it does, page the same way Workday does, with a
  cap in `jobx.fetch.jazzhr.max-pages`.
- Check that a token with upper case or a `-` resolves the same as lower case.
- Write the `## JazzHR — VERIFIED (2026-10-xx); HTML TIER, TWO-CALL` section in
  `docs/ats-api-reference.md` and replace the JazzHR row of the candidate table.

### Code
**`fetcher/jazzhr/JazzHrFetcher.java`** (`@Component`, so `FetcherRegistry` picks it up)
- `String sub = BoardTokens.requireSubdomainLabel(token)`. The host is always
  `"https://" + sub + ".applytojob.com"`.
- `fetch`: `GET {host}/apply`, then:
  - 3xx → dead;
  - 200 with a title containing `Inactive Career Page` → dead
    (`AtsFetchException`, "JazzHR career page for '{sub}' is inactive");
  - otherwise parse `li.list-group-item`.
- Map each card to a `Job`:
  - `externalId` = the code from the href;
  - title = anchor text, trimmed;
  - location = the `li` holding `i.fa-map-marker` (may be absent);
  - `applyUrl` = `{host}/apply/{code}`.
- Zero cards: return `List.of()` when the body contains
  `There are no open positions at this time`; otherwise throw.
- Dedupe codes within the batch.
- Detail, only for unknown ids: `GET {host}/apply/{code}` (the slug is
  cosmetic, so confirm in recon that the bare code works). From the JSON-LD
  `JobPosting`, read `description` and `datePosted`. `datePosted` is date-only,
  so store the start of the day UTC, and judge `isTooOld` against the **end of
  that day** (the Workable rule). The list has no date, so a too-old posting
  costs one detail call the first time it is seen and is then dropped. That
  is acceptable; note it in the javadoc. `jobLocation` overrides the card
  location when the card's is blank. `rawJson` = the JobPosting node.
- `previewBoard`: one GET. Count cards and take `BoardPreview.SAMPLE_SIZE`
  titles. displayName = the JSON-LD `Organization.name`, else the `<title>`
  before " - Career Page", else null. An empty-marker board gives count 0, so
  the inherited `validateBoard` rejects it at add time, which is the existing
  accepted trade-off.

**`resolve/CompanyResolver.java`**: JazzHR is **not** added to `PROBEABLE`, per
the original plan (it stays URL/SNIFF only and keeps the probe fan-out bounded).

### Tests
- `JazzHrFetcherTest` (fixtures, `FetchFilter.none()`):
  - list mapping: count, ids, titles, locations, apply URLs;
  - detail mapping: description, experience, `platformPostedAt`;
  - preview count, titles and displayName;
  - the empty board returns an empty list and a preview count of 0;
  - an inactive page throws;
  - a 200 page with no cards and no marker throws (use a trimmed fixture);
  - the hostile tokens `evil.com#`, `a.b` and `x@y` throw before any request.
- `JazzHrFetchFilterTest` (copy `WorkableFetchFilterTest`): known ids cost 0
  detail calls; a too-old detail is dropped.
- `JazzHrDetailFailureTest` (copy `WorkableDetailFailureTest`).
- A redirect test: drive `fetch`/`previewBoard` through a stubbed `WebClient`
  returning `302 Location: https://info.jazzhr.com/...` and assert
  `AtsFetchException`. The existing tests (`WorkableDetailFailureTest`) mock
  `WebClient.Builder` with `RETURNS_DEEP_STUBS`, which works for `.retrieve()`
  chains but not for status-aware `exchangeToMono`. Here, use a real
  `WebClient.builder().exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.FOUND)
  .header("Location", …).build()))`. It needs no socket, which keeps the suite's
  no-MockWebServer rule.
- `AtsUrlParserTest` already covers the URL forms. Add one careers-page HTML
  excerpt fixture that links an `applytojob.com` board, if one is found during
  recon.

### Frontend and docs
- `watchlist.model.ts`: add `'JAZZHR'` to `SUPPORTED_PLATFORMS`, plus a
  `TOKEN_HINTS.JAZZHR` entry (`{ placeholder: 'brennancenter', url: '', token: 'brennancenter' }`,
  shown as `brennancenter.applytojob.com`). Check how the hint renders for a
  subdomain token; the existing hints are all prefix URLs, so a suffix field
  may be needed.
- `auth-hero.ts` `platforms`: add `'JazzHR'`.
- `docs/ats-test-data.md`: a `### JAZZHR` block with the token `brennancenter`.
- Both `CLAUDE.md`s: update the platform list and count, add the HTML-tier rule
  (zero cards without a marker → throw), and add JazzHR to the ATS approach section.

---

## PR 6 — iCIMS (recon gate FAILED → stays UNSUPPORTED)

No fetcher. Recon found an AWS WAF CAPTCHA on every tenant. Solving or
working around it is out of scope under CLAUDE.md, so this PR only records
the finding and locks the behaviour in.

### Recon to finish (a handful of requests at most; stop at the first CAPTCHA)
- Follow **one real** customer careers page through to its iCIMS link, to
  confirm the public URL form (e.g. `careers-{co}.icims.com/jobs/{id}/{slug}/job`)
  and check that it is behind the same WAF. Many large iCIMS customers front it
  with their own domain or a Jibe/Google careers site. Those are a different
  platform and stay out of scope.
- Check whether any **documented public** iCIMS endpoint exists (not a partner
  feed that needs a contract). If one does, stop and re-plan PR 6 as a fetcher.
  Don't guess undocumented URLs.

### Code (small)
- `AtsPlatform` / `CompanyResolver` / `WatchlistController`: no change.
  `ICIMS` has no fetcher, so `POST /watchlist` already returns 400, and the
  resolver already returns the honest "on iCIMS, which Jobx can't watch yet"
  answer with `platformHint=ICIMS`.
- Add a test that locks this in, if PR 0's tests don't already cover iCIMS
  specifically:
  - `CompanyResolverTest`: a pasted `careers-acme.icims.com/jobs/...` URL gives
    an empty candidate list plus `platformHint = ICIMS`, and no probe call;
  - `WatchlistController` test: an `ICIMS` POST gets 400.
- Check that `HOST_HINTS` `icims\.com/jobs` also matches the
  `/jobs/{id}/.../job` form found above. Add a parser test with the real URL
  shape.
- Frontend: check that the add-company modal's dead-end copy reads well for
  iCIMS. Leave it out of `SUPPORTED_PLATFORMS` and `auth-hero`.

### Docs
- `docs/ats-api-reference.md`: add `## iCIMS — INVESTIGATED, NOT SUPPORTED (2026-10-xx)`
  covering:
  - the 405 response with `x-amzn-waf-action: captcha`;
  - that it covers every host, a bogus one included;
  - the URL forms seen;
  - what would change the verdict.

  Remove the iCIMS row from the candidate table.
- Both `CLAUDE.md`s: iCIMS goes in the "not supported, and why" tier.
- Optionally, record in the dev DB or notes how often `unsupported_board_requests`
  sees iCIMS. That demand signal is what would justify revisiting it.

---

## PR 7 — Gusto (HTML tier, conditional pass)

### Gate check (first commit, before any fetcher code)
The Cloudflare 403s were intermittent. Before building, measure honestly:
- About 20 board and posting GETs with the default `ReactorNetty` UA, spaced at
  least 1s apart (`Crawl-delay: 1`). Record how many get a 403 with
  `cf-mitigated: challenge` or a "Just a moment…" page.
- **Pass:** 200s with only rare 403s. Build the fetcher; a challenge just makes
  that cycle FAILED, and it recovers next poll.
- **Fail:** the challenge is frequent or blocks the default client. Stop, and
  take PR 6's path: docs, a test that GUSTO stays unwatchable, and an
  "INVESTIGATED" section.

### Recon to finish
- Fixtures: `gusto-alexandria-board.html`, `gusto-alexandria-posting.html`, a
  board with several jobs (find one through a posting in `sitemap.xml`; its
  page links back to `/boards/{slug}`), and `gusto-challenge.html` (one 403
  body).
- Find out what an existing board with **zero** postings looks like: a 200
  with a marker, or a 404. This decides rule 2.
- Check whether board cards carry a location or date (posting-page JSON-LD has
  both).
- Check that the UUID suffix alone does not resolve, which confirms that the
  token is the full `name-uuid` slug.

### Code
**`fetcher/gusto/GustoFetcher.java`**
- Token check: `^[a-z0-9-]{1,150}$` (case-insensitive). It goes in a URL
  **path** on the fixed host `https://jobs.gusto.com/boards/`, so this only
  stops `/ ? # %` reaching the path. Do this in the fetcher, or add
  `BoardTokens.requirePathSlug` if one is wanted. BoardTokens is shared, so
  keep that change tiny.
- Status handling:
  - **403 that is a Cloudflare challenge**: header `cf-mitigated: challenge`,
    `server: cloudflare` with a "Just a moment" body, or the `curl` UA case.
    Throw `AtsFetchException("Gusto board '{token}' is behind a Cloudflare
    challenge — not retried")`. No retry and no UA change. Log WARN once per
    cycle.
  - 404 → dead board.
  - 3xx → dead board.
- List: `a[href^=/postings/]`. `externalId` = the trailing UUID
  (`[0-9a-f]{8}-…-[0-9a-f]{12}$`) of the posting slug, which is stable even if
  the title slug changes. Dedupe. Title = link text. `applyUrl` =
  `https://jobs.gusto.com/postings/{slug}`.
- Detail, only for unknown ids. Requests go **one at a time with ≥1s between
  them** (`Crawl-delay`), because the scheduler runs on one thread and Gusto
  boards are small businesses with a handful of roles. Read the JSON-LD
  `JobPosting`:
  - `description` (HTML) → text → `ExperienceParser`;
  - `datePosted` (ISO with offset) → `OffsetDateTime.parse(...).toInstant()`,
    then `isTooOld`;
  - `jobLocation.address` (locality, region, country) → location;
  - `validThrough` in the past → skip the posting (it has expired).
- If a challenge hits **during** detail calls, stop the detail loop and throw,
  so the cycle shows FAILED and doesn't hammer the site. Postings already
  mapped are lost for this cycle and retried next time.
- `previewBoard`: one GET. Count links and take sample titles. displayName =
  `<title>` with the leading "Careers at " removed.
- Not `PROBEABLE`: the token contains a UUID, so it can never be guessed.

**Resolver nicety (optional, small):** a user is likely to paste a *posting* URL
(`jobs.gusto.com/postings/...`). `AtsUrlParser` only recognises `/boards/`, so
that gives just the hint. Two ways to handle it:
- (a) Have SNIFF fetch the posting page and read its relative
  `href="/boards/…"`. This needs a host-aware rule, because the regex scans raw
  text for `jobs.gusto.com/boards/`.
- (b) Leave it, and make the modal copy say "paste the board link". Recommended
  for this PR; note (a) as a follow-up.

### Tests
The same set as JazzHR, plus:
- the challenge fixture gives `AtsFetchException` with the challenge message;
- a challenge midway through detail calls stops further calls (count them);
- the crawl delay goes through an injectable `Sleeper`/`Clock`, so tests don't sleep;
- the posting-UUID regex rejects `/postings/../x` and absolute hrefs.

### Frontend and docs
- Add `'GUSTO'` to `SUPPORTED_PLATFORMS`, `auth-hero`, and
  `TOKEN_HINTS.GUSTO` (`url: 'jobs.gusto.com/boards/'`, token = the
  `alexandria-electric-llc-…` style slug).
- Add `## Gusto — VERIFIED (…); HTML TIER, CLOUDFLARE-FRONTED` to
  `ats-api-reference.md`. Record the challenge measurements and the
  crawl-delay rule.
- Correct the PR 7 section of `new-ats-add.md`: the gate passed, and why.
- Add a `### GUSTO` block to `ats-test-data.md`.
- Both CLAUDE.md files:
  - add Gusto to the HTML tier;
  - add a rule: "a bot-protection challenge is a FAILED fetch, never something
    to work around; never send a browser UA."

---

## Order and merge-conflict hotspots

Order: **PR 6 first** (docs and tests, no risk, lands fast), then **PR 5**, then **PR 7**
(it depends on the gate measurement). Each is its own commit series, or its own
PR if the branch is split.

The parallel branches touch the same shared files. Keep edits there small and
append-only:

| File | Who else edits it | Mitigation |
|---|---|---|
| `CompanyResolver.java` | Workday WIP changes the probe guard (`PROBEABLE.contains(platformHint)`); PRs 2–3 add RIPPLING/BAMBOOHR to `PROBEABLE` | PRs 5–7 don't touch `PROBEABLE` at all |
| `application.yml` | Workday adds `jobx.fetch.workday.max-pages` | Only add `jobx.fetch.jazzhr.*` if pagination exists; append it as a sibling block |
| `watchlist.model.ts`, `auth-hero.ts` | Every platform PR appends to `SUPPORTED_PLATFORMS`, `TOKEN_HINTS` and `platforms` | One-line appends; trivial rebase conflicts |
| `ats-api-reference.md`, `ats-test-data.md`, both `CLAUDE.md`s | Every platform PR | Add new sections only and edit just this platform's candidate-table row; rewrite the tier summary paragraph in whichever PR merges **last** |
| HTML-tier helper | Jobvite (PR 4) will need the same redirect/marker logic | Keep it private in each fetcher for now. After PRs 4, 5 and 7 merge, a follow-up can pull a shared `HtmlBoardPage` (status + Location + jsoup doc + JSON-LD extraction) out of three real users instead of guessing at it from one |

## Verification (each fetcher PR)
1. `./mvnw test`: all of the above, plus the existing suite.
2. `npm test` and `ng build` in `jobx-frontend`.
3. Live run (`run` skill):
   - paste `https://brennancenter.applytojob.com/apply` (or the Gusto board URL)
     into the add-company modal; the card shows real titles;
   - Watch, then Check now; jobs appear with description, location and date,
     and `last_fetch_status = SUCCESS`;
   - a second Check now logs 0 detail calls.
4. Dead-board checks:
   - a bogus JazzHR sub returns 400 on add;
   - set a watched board's token to `jazzhr` (inactive) or a bogus Gusto slug
     in the DB; the next poll is FAILED, not SUCCESS with 0 jobs;
   - `getinflow` is rejected at add time (0 roles) but would poll as SUCCESS
     with 0 jobs if already watched.
