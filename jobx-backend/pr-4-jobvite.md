# Plan: PR 4 — Jobvite fetcher (the first HTML-tier fetcher)

Part of the ATS rollout in `new-ats-add.md`. PR 0 (groundwork) is done.
`AtsPlatform.JOBVITE`, the `AtsUrlParser` rule (`jobs.jobvite.com/TOKEN`), the
`HOST_HINTS` entry and `boardUrl` all exist. This PR only adds the fetcher bean
plus its docs and frontend lists. `FetcherRegistry` auto-discovers it. It
doesn't depend on PRs 1–3.

This is the first fetcher that parses HTML instead of JSON, so it sets the
pattern JazzHR (PR 5) will follow.

## Reuse, don't reinvent

- `fetcher/workable/WorkableFetcher.java` is the two-call template: its seams,
  the rule that a failed detail call skips the job, and the summary log line.
- `FetchFilter.isKnown` must run before the detail page GET.
- The default `validateBoard`. Don't override it.
- `BoardPreview.SAMPLE_SIZE`, `ExperienceParser`, Jsoup (already in `pom.xml`),
  `AtsFetchException`.
- Tests: `FixtureSupport`. Copy the filter and detail-failure tests from the
  Workable ones.
- **Redirects:** WebClient doesn't follow redirects here, and `retrieve()` treats
  a 3xx as success. Use `.retrieve().toEntity(String.class)` and check for a 3xx
  explicitly.

## 1. Recon (before any fetcher code)

- Capture `jobvite-egnyte-list.html` (`GET https://jobs.jobvite.com/egnyte/jobs`)
  and `jobvite-egnyte-detail.html` (`/egnyte/job/{id}`).
- Confirm:
  - the list selectors: `.jv-job-list-name`, `.jv-job-list-location`, and links
    to `/{co}/job/{id}`;
  - the description selector on the detail page;
  - whether the list paginates (`?p=` or a "next" link) or is complete. Use
    `nutanix` as the large-board check;
  - **the no-openings marker** on a real board with zero jobs. Capture
    `jobvite-{co}-empty.html` if such a board can be found;
  - what a dead company returns: a 3xx to `jobvite.com/support/…?invalid=1`.
- No posting date is available, so `platformPostedAt` stays null. The TTL falls
  back to `first_seen_at`, which is already supported, and tombstones stop
  re-adds.

## 2. `fetcher/jobvite/JobviteFetcher.java`

- **Token:** a path segment on the fixed host `jobs.jobvite.com`. Reject
  anything that isn't `[A-Za-z0-9_-]+` with `AtsFetchException`, so `../` or `?`
  can't change the path.
- **HTTP seam `get(url)`:** package-private and overridable. It uses
  `retrieve().toEntity(String.class)`.
  - Any 3xx means the board is dead and throws `AtsFetchException`. Mention a
    `/support/` Location in the message.
  - A null body throws.
- **List parse** (`Jsoup.parse(html, baseUri)`): one card per job link.
  - `externalId` is the `/job/{id}` segment, deduped.
  - Title and location come from the selectors.
  - `applyUrl` is the absolute job URL.
  - If the list paginates, follow it up to a fixed page cap and log a WARN when
    the cap is hit.
- **Fail loudly:** zero cards on a 200 page throws `AtsFetchException`, unless
  the page contains the no-openings marker captured in recon. In that case,
  return an empty list. A layout change must show up as FAILED health, never as
  a quietly empty feed.
- Then check `filter.isKnown(id)`, then GET the detail page.
  - Take the description from the selector, strip it, and run
    `ExperienceParser`.
  - A missing description element counts as a detail failure: skip the job and
    retry next cycle rather than storing it blank. Skip the job on any detail
    failure.
- **`previewBoard`:** one list page. Return the card count and titles.
  - The display name is `null`, or the page `<title>` if recon shows it is
    clean.
  - On a paginated board the count covers only the first page. Document that.
    `validateBoard` only needs a count above 0.
- Keep the HTML helpers private to this class. Extract a shared HTML-tier
  helper only when JazzHR needs the same code.
- **Not probeable.** Jobvite is found only from a pasted URL or a sniffed
  careers page (URL/SNIFF).

## 3. Tests (`src/test/java/com/jobx/fetcher/jobvite/`)

- `JobviteFetcherTest`:
  - the list fixture yields ids, titles, locations and absolute URLs;
  - the detail fixture yields the description and experience;
  - preview mapping.
- Zero cards with no marker throws. The empty-board fixture with the marker
  returns an empty list.
- A 3xx (dead board) throws. A hostile token (`../x`, `a?b`, `a/b`) throws
  before any request.
- Filter test: a known id costs 0 detail calls.
- Detail-failure test, including a detail page with no description element.

## 4. Docs and frontend

- `docs/ats-api-reference.md`:
  - replace the Jobvite row in "Candidate platforms" with a
    `## Jobvite — VERIFIED (date)` section;
  - record the selectors, the pagination finding, the dead-board redirect and
    the no-openings marker;
  - list the fixtures;
  - add a row to "Careers-page URL forms".
- `docs/ats-test-data.md`: add a `### JOBVITE` block.
- `watchlist.model.ts`: add `JOBVITE` to `SUPPORTED_PLATFORMS` and
  `TOKEN_HINTS` (`egnyte`).
- `auth-hero.ts`: add `'Jobvite'` to `platforms`.
- Both `CLAUDE.md`s: update the endpoint list, the platform count and the
  next-wave status. Note that Jobvite is the first HTML-tier fetcher, and point
  to its zero-cards rule as the reference for JazzHR.

## Verification

1. `./mvnw test`: the new tests pass and the existing suites stay green.
2. `npm test && npx ng build` in `jobx-frontend`.
3. Live check with the `run` skill:
   - Paste `jobs.jobvite.com/egnyte/jobs`. The confirm card shows real titles.
   - Watch, then click "Check now".
   - Jobs show descriptions and locations, with no date, and
     `last_fetch_status = SUCCESS`.
4. Dead board:
   - A bogus company returns 400 (because of the redirect).
   - Break a watched board's token in the DB. The next poll marks it FAILED.
5. A second check logs 0 detail calls for stored postings.
