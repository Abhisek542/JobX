# Plan: PR 3 — BambooHR fetcher (API tier, token in the host)

Part of the ATS rollout in `new-ats-add.md`. PR 0 (groundwork) is done.
`AtsPlatform.BAMBOOHR`, the `AtsUrlParser` host rule, the `HOST_HINTS` entry,
`boardUrl` and `BoardTokens.requireSubdomainLabel` all exist. This PR only adds
the fetcher bean plus its docs and frontend lists. `FetcherRegistry`
auto-discovers it. It doesn't depend on PR 1 (Workday) or PR 2 (Rippling).

## Reuse, don't reinvent

- `fetcher/workable/WorkableFetcher.java` is the template for the two-call
  design. Reuse its seams (`parseList`, `fetchDetail`, `applyDetail`,
  `parsePreview`), its skip-on-detail-failure rule and its summary log line.
- `FetchFilter.isKnown` / `isTooOld` run before any detail call.
- The default `validateBoard`. Don't override it.
- `BoardPreview.SAMPLE_SIZE`, `ExperienceParser`, Jsoup text stripping,
  `AtsFetchException`.
- Tests: `FixtureSupport`. Copy from `WorkableFetchFilterTest` and
  `WorkableDetailFailureTest`.
- **Redirects:** `WebClientConfig` doesn't turn on Netty redirect following, and
  `retrieve()` treats a 3xx as success. Use `.retrieve().toEntity(String.class)`
  and check `getStatusCode().is3xxRedirection()` explicitly. Don't count on a
  null or empty body to catch a redirect.

## 1. Recon (before any fetcher code)

- Find at least one live tenant with openings. Search the web for
  `"bamboohr.com/careers"`.
- Capture `bamboohr-{sub}-list.json` from
  `GET https://{sub}.bamboohr.com/careers/list`.
- Find and verify the per-job detail endpoint. The guess is
  `/careers/{id}/detail`. Capture `bamboohr-{sub}-detail.json`.
- Capture `bamboohr-andela-empty.json` for a real board that is empty
  (`totalCount 0`).
- Confirm:
  - the item fields: id, title, location shape, department, and whether the
    list has any date;
  - the apply/page URL form, probably `https://{sub}.bamboohr.com/careers/{id}`;
  - that an unknown subdomain **reliably** returns a 302 to `www.bamboohr.com`.
    Try several random labels.

## 2. `fetcher/bamboohr/BambooHrFetcher.java`

- The first line of `fetch` and of `previewBoard` is
  `String sub = BoardTokens.requireSubdomainLabel(company.getBoardToken());`.
  Build the host only as `"https://" + sub + ".bamboohr.com"`. Never
  concatenate free text into it.
- One shared HTTP seam, `get(String url)`, package-private so tests can
  override it:
  - it uses `retrieve().toEntity(String.class)`;
  - a **3xx** throws `AtsFetchException("BambooHR board '" + sub + "' does not exist")`;
  - a null body throws.
- **List:** parse `result`. If it is missing or not an array, throw.
  - `meta.totalCount == 0` with an empty array is a real empty board, so return
    an empty list.
  - `externalId` is the item id. Check `filter.isKnown` before the detail call.
  - If the list carries a date, also apply `filter.isTooOld` before the detail
    call.
- **Detail:** strip the HTML description, then run `ExperienceParser`. Map the
  date if one is present. Skip the job if the detail call fails.
- **`previewBoard`:** one list call. Return `totalCount` (or the array size)
  and the titles. Set the display name only if recon finds one in the payload.

## 3. Resolver

Add `BAMBOOHR` to `PROBEABLE` only if recon shows the wildcard 302 is reliable.
A guessed label goes through `requireSubdomainLabel` inside the fetcher, so a
hostile slug candidate fails closed. `BoardProbe.probeOne` already treats an
exception as "not a candidate".

**Existing test to retarget:**
`CompanyResolverTest.aSniffedBoardOnAnUnwatchablePlatformBecomesTheHint` uses a
BambooHR link as its "unwatchable" platform. Switch it to a JazzHR link
(`acme.applytojob.com`) so it stays true once BambooHR has a fetcher. Check how
that test stubs its `fetcherRegistry` mock.

## 4. Tests (`src/test/java/com/jobx/fetcher/bamboohr/`)

- `BambooHrFetcherTest`:
  - list, detail and preview mapping;
  - an empty board returns an empty list;
  - a missing `result` throws.
- A filter test (a known id costs 0 detail calls) and a detail-failure test.
- Hostile tokens (`evil.com#`, `a.b`, `x@y`) throw before any request. Assert
  that neither `get` nor `fetchDetail` is ever reached.
- 3xx handling: override `get` to return a 302 `ResponseEntity`, and assert
  that `AtsFetchException` names the board.

## 5. Docs and frontend

- `docs/ats-api-reference.md`:
  - replace the BambooHR row in "Candidate platforms" with a
    `## BambooHR — VERIFIED (date)` section;
  - list the fixtures;
  - add a row to "Careers-page URL forms".
- `docs/ats-test-data.md`: add a `### BAMBOOHR` block.
- `watchlist.model.ts`: add `BAMBOOHR` to `SUPPORTED_PLATFORMS` and
  `TOKEN_HINTS`, using the real tenant.
- `auth-hero.ts`: add `'BambooHR'` to `platforms`.
- Both `CLAUDE.md`s: update the endpoint list, the platform count and the
  next-wave status.

## Verification

1. `./mvnw test`: the new tests pass, and `AtsUrlParserTest`, `BoardTokensTest`
   and `CompanyResolverTest` (with the retargeted test) stay green.
2. `npm test && npx ng build` in `jobx-frontend`.
3. Live check with the `run` skill:
   - Paste `{sub}.bamboohr.com/careers`. The confirm card shows real titles.
   - Watch, then click "Check now".
   - Jobs show descriptions and locations, and `last_fetch_status = SUCCESS`.
4. Dead board:
   - A bogus subdomain returns 400 (because of the 302).
   - A hostile token returns 400 without making any request.
   - Break a watched board's token in the DB. The next poll marks it FAILED.
5. A second check logs 0 detail calls for stored postings.
