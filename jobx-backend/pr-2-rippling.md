# Plan: PR 2 — Rippling fetcher (API tier)

Part of the ATS rollout in `new-ats-add.md`. PR 0 (groundwork) is done.
`AtsPlatform.RIPPLING`, the `AtsUrlParser` rules, the `HOST_HINTS` entry and
`boardUrl` all exist. `CompanyResolver` and `POST /watchlist` treat a platform
with no registered fetcher as "recognised, not watchable". So this PR only adds
the fetcher bean plus its docs and frontend lists. `FetcherRegistry`
auto-discovers it, and the platform becomes watchable with no other wiring. It
doesn't depend on PR 1 (Workday).

## Reuse, don't reinvent

- `fetcher/workable/WorkableFetcher.java` is the template for the two-call
  design. It has the package-private seams `parseList(body, company, filter,
  fetchDetails)`, `fetchDetail(...)`, `applyDetail(...)` and `parsePreview(...)`.
  It also has the skip-on-detail-failure rule and its comment, and it logs one
  summary line with the counts.
- `FetchFilter.isKnown` / `isTooOld`.
- The default `AtsFetcher.validateBoard` rejects zero roles. Don't override it.
- `BoardPreview.SAMPLE_SIZE`, `ExperienceParser.parse`,
  `Jsoup.parse(html).text()`, `AtsFetchException`.
- Tests: `FixtureSupport.fixture/company`. Copy from `WorkableFetcherTest`,
  `WorkableFetchFilterTest` (an anonymous subclass overrides `fetchDetail` to
  count calls) and `WorkableDetailFailureTest`.

## 1. Recon (before any fetcher code)

Use curl and record the results in the docs.
- Capture `rippling-{slug}-v1-list.json` from
  `GET https://api.rippling.com/platform/api/ats/v1/board/{slug}/jobs` on a live
  board (`rippling`).
- Capture `rippling-{slug}-v2-detail.json` from
  `GET https://ats.rippling.com/api/v2/board/{slug}/jobs/{uuid}`.
- Confirm:
  - (a) the v1 list carries no date, which is why the detail call is needed;
  - (b) a bogus slug on v1 also returns 404, not an empty 200;
  - (c) whether slugs are case-sensitive (`SlugCandidates` tries both casings);
  - (d) whether v1 repeats a job once per location, as Workable does, so we
    should dedupe by `uuid`;
  - (e) the exact shape of `workLocation`, including the label field's name.

## 2. `fetcher/rippling/RipplingFetcher.java`

`@Component`, `supports() = RIPPLING`.

- **List:** one v1 GET returns the whole board.
  - A root that isn't an array throws `AtsFetchException`.
  - A 404 surfaces as `AtsFetchException` through the `retrieve()` error.
- **Map each item:**
  - `externalId` is `uuid`. Skip blanks and duplicates within the batch.
  - Check `filter.isKnown(uuid)` before the detail call.
  - `title` comes from `name`, `applyUrl` from `url`, and `location` from
    `workLocation.label`.
- **Detail (v2):**
  - The description is `description.company` + `description.role`. Both are
    HTML; strip each and join them, then run `ExperienceParser.parse`.
  - Map `createdOn` (ISO) to `platformPostedAt`, then check
    `filter.isTooOld(...)`. The date only arrives with the detail, so the age
    gate saves storing the job, not the call. Say so in the javadoc.
  - Store the detail JSON as `rawJson`.
- **Detail failure or empty body:** skip the job and retry next cycle. Copy
  Workable's comment.
- **`previewBoard`:** one v1 call. Return the count of unique uuids and the
  first `SAMPLE_SIZE` titles. The display name is `null`, because v1 has none.
- **Token:** it sits in a path on a fixed host, so `BoardTokens` isn't needed.
  Still URL-encode or reject `/` in the token. Build URIs the same way the
  existing path-token fetchers do.

## 3. Resolver

Add `AtsPlatform.RIPPLING` to `PROBEABLE` in
`resolve/CompanyResolver.java`, and update the javadoc there. Do this only if
recon confirms (b).

## 4. Tests (`src/test/java/com/jobx/fetcher/rippling/`)

- `RipplingFetcherTest`:
  - list mapping (`fetchDetails=false`);
  - `applyDetail` mapping: description, date, experience;
  - `parsePreview`: count, titles, null name;
  - a root that isn't an array throws.
- `RipplingFetchFilterTest`: a known uuid costs 0 detail calls. A too-old
  `createdOn` is dropped after the detail call.
- `RipplingDetailFailureTest`: when `fetchDetail` throws, that job is skipped
  and the others are kept.
- `CompanyResolverTest`: a probe with no hint includes RIPPLING (only if it was
  added to `PROBEABLE`).

## 5. Docs and frontend

- `docs/ats-api-reference.md`:
  - replace the Rippling row in "Candidate platforms" with a
    `## Rippling — VERIFIED (date)` section;
  - list the fixtures under "Test fixtures";
  - add a row to "Careers-page URL forms" (`ats.rippling.com/{slug}/jobs`).
- `docs/ats-test-data.md`: add a `### RIPPLING` block with the live company and
  token.
- `jobx-frontend/src/app/core/models/watchlist.model.ts`: add `RIPPLING` to
  `SUPPORTED_PLATFORMS` and `TOKEN_HINTS`, using the real token from recon.
- `shared/ui/auth-hero.ts`: add `'Rippling'` to `platforms`.
- Both `CLAUDE.md`s: update the "ATS integration approach" endpoint list, the
  platform count and list, and the next-wave status. In the frontend
  `CLAUDE.md`, also update the line saying `SUPPORTED_PLATFORMS` is still the
  original five.

## Verification

1. `./mvnw test`: the new tests pass, and `AtsUrlParserTest`, `BoardTokensTest`
   and `CompanyResolverTest` stay green.
2. `npm test && npx ng build` in `jobx-frontend`.
3. Live check with the `run` skill:
   - Paste `ats.rippling.com/{slug}/jobs` into the add-company modal. The
     confirm card shows real titles.
   - Watch, then click "Check now".
   - Jobs show descriptions, locations and dates, and
     `last_fetch_status = SUCCESS`.
4. Dead board:
   - A bogus slug returns 400.
   - Break a watched board's token in the DB. The next poll marks it FAILED.
5. A second check outside the cooldown logs 0 detail calls for stored postings.
