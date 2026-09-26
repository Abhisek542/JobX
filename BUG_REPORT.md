# Jobx — Bug Report (2026-09-12)

Found by a full read of `jobx-backend/` and `jobx-frontend/` on branch `task/bug-findFix`.
Gaps that `CLAUDE.md` already records as deliberate (unpaginated `GET /matches`, no `SAVED`
status, login timing, N+1 on the feed) are excluded. Fixed items are marked **Fixed** below.

| # | Severity | Area | Summary |
|---|----------|------|---------|
| 1 | High | Frontend | **Fixed 2026-09-12** — Sign-out does not clear the data stores — next user sees the previous user's data |
| 2 | High | Backend | **Fixed 2026-09-13** — Workable / SmartRecruiters re-fetch detail for every tombstoned posting, every cycle |
| 3 | Medium | Backend | **Fixed 2026-09-13** — Email is case-sensitive at register and login |
| 4 | Medium | Backend | **Fixed 2026-09-13** — Experience penalty never fires for open-ended ranges ("5+ years") |
| 5 | Medium | Frontend | **Fixed 2026-09-13** — Typeahead pick shows "0 open roles" and a blank board link |
| 6 | Medium | Backend | **Fixed 2026-09-19** — Long outbound HTTP calls run inside DB transactions |
| 7 | Medium | Backend | **Fixed 2026-09-19** — Framework exceptions (404 path, 405 method, missing param) become 500 |
| 8 | Low | Backend | **Fixed 2026-09-19** — Malformed `Location` header on a careers site becomes a 500 |
| 9 | Low | Frontend | **Fixed 2026-09-20** — `?next=` deep link is set by the guard but ignored by the login page |
| 10 | Low | Frontend | **Fixed 2026-09-20** — Copy says scores update "on the next check"; backend rescores immediately |
| 11 | Low | Backend | **Fixed 2026-09-20** — No per-board lock between "Check now" and the scheduled cycle |
| 12 | Low | Backend | **Fixed 2026-09-26** — `%` / `_` not escaped in the catalog search `LIKE` |

---

## 1. Sign-out does not clear the data stores (High)

**Symptom.** User A signs out, user B signs in on the same tab without a page reload. The
dashboard renders A's matches, watchlist and filter profile. The 401 path in the error
interceptor has the same hole.

**Cause.** `AuthStore.clear()` only drops the session. The feed, watchlist and profile stores
are root singletons whose `load()` returns early once `loaded` is true, and `AppShell` calls the
plain `load()` on construction.

`jobx-frontend/src/app/core/services/auth.store.ts:39`
```ts
  /** Local sign-out. Nothing to revoke server-side — the JWT is stateless. */
  clear(): void {
    this.session.set(null);
    removeStorage(SESSION_KEY);
  }
```

`jobx-frontend/src/app/shared/layout/app-shell.ts:73`
```ts
  constructor() {
    // One load per session; individual actions force-refresh when they change data.
    this.feed.load();
    this.watchlist.load();
    this.profile.load();
  }

  protected signOut(): void {
    this.auth.clear();
    void this.router.navigate(['/login']);
  }
```

`jobx-frontend/src/app/features/dashboard/feed.store.ts:89` (same shape in
`watchlist.store.ts:89` and `filter-profile.store.ts:41`)
```ts
  load(options: { force?: boolean } = {}): void {
    if (this.loadingSignal()) return;
    if (this.loadedSignal() && !options.force) return;
```

`jobx-frontend/src/app/core/interceptors/error.interceptor.ts:28`
```ts
      if (error.status === 401 && !isAuthCall) {
        auth.clear();
        void router.navigate(['/login'], { queryParams: { expired: 1 } });
      }
```

**Fix direction.** Give each store a `reset()` and call all three from `AuthStore.clear()`
(or from an auth-state effect), so `loaded` flips back to false and the arrays empty.

**Fixed (2026-09-12, branch `task/sign-out-clear-fix`).** `AuthStore.clear()` now resets
`FeedStore`, `WatchlistStore` and `FilterProfileStore`, clears `ToastService` and closes the
`UiStore` overlays, so both the sidebar button and the 401 path are covered. Two further leaks
were closed in the same change:
- **Late responses.** A request sent before sign-out could land after the reset and set
  `loaded = true` again, recreating the bug. Each store now carries a session `epoch` that
  `reset()` bumps, and every subscribe callback bails if the epoch changed.
- **Toasts and overlays.** An Undo toast kept a closure that would PATCH the previous user's
  match, and a modal open at a 401 stayed open for the next user.

The collapsed-sidebar preference survives on purpose, like the theme. Covered by
`jobx-frontend/src/app/core/services/session-reset.spec.ts`.

---

## 2. Detail calls are spent on every tombstoned posting, every cycle (High)

> **Fixed 2026-09-13** (PR #8, branch `task/tombstoned-issue`). `FetchScheduler` now builds a
> `FetchFilter` (stored + tombstoned external ids, and the TTL cutoff) *before* the fetch and
> passes it to `AtsFetcher.fetch(Company, FetchFilter)`. Workable and SmartRecruiters check it
> before every detail call, and also skip postings whose list date is already past the TTL.
> Workable's date-only `published_on` is judged by the end of that day. The scheduler applies
> the same filter to every platform, which closes the related gap below. The per-posting
> `existsByCompanyAndExternalId` queries are gone: it's now three set queries per board.
> Live-verified against the dev database: PhonePe went from 39 detail calls per cycle to 0, and
> Deloitte from 267 to 0. The text below is the original report.

**Symptom.** After the six-day TTL sweeps a board, every posting still listed on it is absent
from `jobs` and present in `expired_jobs`. The Workable and SmartRecruiters fetchers guard the
N+1 only against `jobs`, so each of those postings costs a full detail HTTP call every
30 minutes, forever. Apna ≈ 96 calls per cycle; Bosch ≈ 4,774 per cycle (≈ 229k/day), which is
the exact fan-out the guard's own comment says it prevents.

**Cause.** The tombstone check lives in `FetchScheduler`, after the fetcher has already
returned fully-hydrated jobs.

`jobx-backend/src/main/java/com/jobx/fetcher/workable/WorkableFetcher.java:122`
```java
            // N+1 guard: known jobs get skipped entirely — the scheduler would
            // dedup them anyway, so a detail call would be pure waste
            if (fetchDetails && jobRepository.existsByCompanyAndExternalId(company, shortcode)) {
                continue;
            }
            ...
                String detailBody;
                try {
                    detailBody = webClientBuilder.build()
                            .get()
                            .uri(BASE_URL + "/api/v2/accounts/" + company.getBoardToken() + "/jobs/" + shortcode)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
```

`jobx-backend/src/main/java/com/jobx/fetcher/smartrecruiters/SmartRecruitersFetcher.java:165`
```java
            // N+1 guard: a known posting would be deduped by the scheduler
            // anyway, so its detail call would be pure waste. Bosch-sized
            // boards make this the difference between 2 calls and 4,774.
            if (jobRepository.existsByCompanyAndExternalId(company, externalId)) {
                continue;
            }

            Job job = mapListItem(node, company, externalId);

            String detailBody;
            try {
                detailBody = get(BASE_URL + "/v1/companies/" + token + "/postings/" + externalId, null);
```

`jobx-backend/src/main/java/com/jobx/scheduler/FetchScheduler.java:138` — the check that
should have happened before the detail call:
```java
        Set<String> tombstoned = expiredJobRepository.findExternalIdsByCompany(company);

        for (Job job : fetchedJobs) {
            if (jobRepository.existsByCompanyAndExternalId(company, job.getExternalId())) {
                continue;
            }
            if (tombstoned.contains(job.getExternalId())) {
                continue;
            }
```

**Related gap.** `fetchCompany` has no age gate, so a newly added board ingests every posting
already older than the TTL, scores and shows them as "New", and the sweep deletes them within
24 h. Both list endpoints carry a posting date (`published_on`, `releasedDate`), so age can be
checked before any detail call.

**Fix direction.** Pass the tombstone set (or an `isKnown(externalId)` predicate that checks
both tables) into the fetchers, and skip postings whose list-level posted date is already past
the TTL cutoff before fetching detail.

---

## 3. Email is case-sensitive at register and login (Medium)

**Symptom.** Register `Abhi@Example.com`, log in as `abhi@example.com` → 401. Two accounts
that differ only by case can coexist.

**Cause.** No normalization anywhere; `users.email UNIQUE` is case-sensitive in Postgres.

`jobx-backend/src/main/java/com/jobx/controller/AuthController.java:30`
```java
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }

        User user = new User();
        user.setEmail(request.email());
        ...
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);
```

`jobx-backend/src/main/java/com/jobx/repository/UserRepository.java:11`
```java
    Optional<User> findByEmail(String email);
```

`jobx-backend/src/main/resources/db/migration/V1__create_schema.sql:8`
```sql
    email         TEXT NOT NULL UNIQUE,
```

**Fix direction.** Trim + lower-case the email in both endpoints before lookup/save, and add
a unique index on `LOWER(email)` (or migrate existing rows to lower case).

**Fixed 2026-09-13.** `RegisterRequest` / `LoginRequest` normalize the email in their
compact constructors via `util/Emails.normalize` (strip + `toLowerCase(Locale.ROOT)`), so
validation, lookup and save all see the canonical form. `V7__users_email_case_insensitive.sql`
lower-cases existing rows and adds `UNIQUE (LOWER(email))`. It aborts rather than merging if
two existing accounts already collide. A concurrent duplicate register hits the index and
maps to 409 through `GlobalExceptionHandler`. Tests: `AuthControllerTest`, `EmailsTest`.

---

## 4. Experience penalty never fires for open-ended ranges (Medium)

> **Fixed 2026-09-13** (branch `task/experience-penalty`). `MatchScorer` now treats a null
> bound as open (min → 0, max → ∞). A gap is counted only where a real bound exists on each
> side of it, so a "10+ years" job against a 0–1 profile is 9 years out (70 instead of 100). A job
> or profile with no range at all still gets the full 30, and ranges with all four bounds score
> exactly as before. `MatchScorerTest` gained 7 open-ended cases (min-only jobs, min-only and
> max-only profiles, both open above). Scores already stored are not rescored; stale NEW matches
> expire with the 6-day TTL, or a profile save rescores that user immediately. The text below
> is the original report.

**Symptom.** A "10+ years" role scores the full 30 experience points for a 0–1 year profile.
A profile with only a minimum (or only a maximum) set is likewise ignored.

**Cause.** `MatchScorer` requires all four bounds to be non-null, but two of the three
`ExperienceParser` patterns set only `expMin`.

`jobx-backend/src/main/java/com/jobx/scorer/MatchScorer.java:81`
```java
        // 3. Soft experience scoring — null exp range = distance 0 (full 30 pts)
        int distance = 0;
        if (job.getExpMin() != null && job.getExpMax() != null
                && profile.getExpMin() != null && profile.getExpMax() != null) {
            if (job.getExpMax() < profile.getExpMin()) {
                distance = profile.getExpMin() - job.getExpMax();
            } else if (job.getExpMin() > profile.getExpMax()) {
                distance = job.getExpMin() - profile.getExpMax();
            }
        }
        int experienceScore = Math.max(0, 30 - distance * 10);
```

`jobx-backend/src/main/java/com/jobx/fetcher/ExperienceParser.java:39`
```java
        // Try "X+ years"
        Matcher plusMatcher = EXP_PLUS.matcher(text);
        if (plusMatcher.find()) {
            job.setExpMin(Integer.parseInt(plusMatcher.group(1)));
            // no upper bound
            return;
        }

        // Try "minimum X years"
        Matcher minMatcher = EXP_MIN_KW.matcher(text);
        if (minMatcher.find()) {
            job.setExpMin(Integer.parseInt(minMatcher.group(1)));
        }
```

**Fix direction.** Treat a null bound as open (`expMin == null → 0`, `expMax == null → ∞`)
on both the job and profile side, and compute distance only across the bounds that exist.
`MatchScorerTest` should gain cases for min-only jobs and min-only profiles.

---

## 5. Typeahead pick shows "0 open roles" and a blank board link (Medium)

> **Fixed 2026-09-13** (branch `task/typehead-pick-fix`). A pick now calls the new
> `GET /watchlist/resolve/catalog/{companyId}`, backed by `CompanyResolver.previewCatalog`.
> It shares the per-board evidence logic with the name path (stored jobs, else one live
> preview), so the card shows the real count, titles and board URL for that exact board. It
> sits under the `/watchlist/resolve` rate-limit budget; `RateLimitFilter` now keys that budget
> on the prefix rather than the full path, so each company id can't get a fresh window (auth
> keeps its per-path windows). A 404 (unknown board, or nothing
> live) makes the modal fall back to a full resolve by name, which can find a company that
> moved ATS. The board link is also hidden when there is no URL. The text below is the
> original report.

**Symptom.** Picking a suggestion from the add-company typeahead lands on the confirm step
reading "Greenhouse · 0 open roles" with no sample titles, and "Open their board" opens the
dashboard itself in a new tab (`href=""`). The backend fix for catalog rows with no stored jobs
only covers the `/watchlist/resolve` path, not this one.

`jobx-frontend/src/app/shared/overlays/add-company-modal.ts:408`
```ts
  /** A catalog pick is already a confirmed board — skip straight to confirming it. */
  protected pickSuggestion(option: CompanySearchResponse): void {
    const board: ResolvedBoardResponse = {
      source: 'CATALOG',
      atsPlatform: option.atsPlatform,
      boardToken: option.boardToken,
      companyName: option.companyName,
      boardUrl: '',
      jobCount: 0,
      sampleTitles: [],
      alreadyWatched: option.alreadyWatched,
    };
```

Rendered unconditionally at `add-company-modal.ts:205` and `:225`:
```html
                  <span class="ac-card-meta">
                    {{ label(board.atsPlatform) }} · {{ board.jobCount }}
                    {{ board.jobCount === 1 ? 'open role' : 'open roles' }}
                  </span>
...
                <a [href]="board.boardUrl" target="_blank" rel="noopener noreferrer">
                  Open their board<app-icon name="external-link" size="sm"
                /></a>
```

**Fix direction.** On pick, either call `resolve(option.companyName)` (which returns a real
preview) or make the count/titles/link conditional and build `boardUrl` client-side from the
platform + token.
chec
---

## 6. Long outbound HTTP calls run inside DB transactions (Medium)

> **Fixed 2026-09-19** (branch `task/long-outbound-http-calls`). No DB connection is held
> across an outbound HTTP call any more. Four sites, not the two reported:
> `CompanyResolver.resolve` and `previewCatalog` simply lose `@Transactional(readOnly = true)`
> (`search` keeps it — it is network-free by design); `WatchlistController.add` loses
> `@Transactional` and its two writes move to a new `WatchlistService`, so `validateBoard`'s ATS
> round trip runs between them with nothing open; and `FetchScheduler.fetchCompany` — the worst
> offender, unreported, which held a connection for the whole board fetch on both the scheduler
> and "Check now" — splits into dedup reads, the fetch, and one short `persistFetched`
> transaction.
>
> **Dropping `@Transactional` was only half of it.** Spring's Hibernate adapter defaults to
> `DELAYED_ACQUISITION_AND_HOLD`, so with `open-in-view` on (and `GET /matches` needs it) the
> session pinned a connection from its first query to the end of the request, transaction or
> not. `hibernate.connection.handling_mode: DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`
> is what actually frees the pool. A Hikari `leak-detection-threshold` was added as the tripwire
> and is what caught this: the first fixed build still logged ten leaks, one per resolve.
>
> Live-verified against the dev Postgres, ten concurrent resolves on a careers URL that stalls
> (~13 s each), polling `GET /watchlist` throughout. Pre-fix: 11 polls landed in the window and
> one blocked **11,816 ms** waiting for a connection. Post-fix: **73 polls, none over 174 ms**,
> and a full 12-board fetch cycle ran with zero leak warnings. Tests 240 → 243. The text below
> is the original report.

**Symptom.** Ten concurrent add-company resolves hold the whole default Hikari pool (10
connections) for up to ~20 s each, stalling every other request and the fetch scheduler.

`jobx-backend/src/main/java/com/jobx/resolve/CompanyResolver.java:131`
```java
    @Transactional(readOnly = true)
    public ResolveResponse resolve(User user, String query) {
        ...
        // 3. SNIFF — fetch the careers page and read the board off it.
        if (looksLikeUrl(trimmed)) {
            Optional<String> html = safeUrlFetcher.fetch(trimmed);       // up to 10 s
        ...
        // 4. PROBE — guess the slug, then make the ATS prove it.
            List<ResolvedBoardResponse> probed = boardProbe.probe(platforms, tokens)   // 9 s budget
```

`jobx-backend/src/main/java/com/jobx/controller/WatchlistController.java:143`
```java
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public WatchedCompanyResponse add(@AuthenticationPrincipal User user,
                                      @Valid @RequestBody WatchedCompanyRequest request) {
        Company company = getOrCreateCompany(request);       // → validateBoard → HTTP call
```

`WatchlistController.java:288`
```java
    private void validateBoard(Company company) {
        ...
            fetcher.validateBoard(company);     // one ATS round trip, inside the add transaction
```

**Fix direction.** Drop `@Transactional` from `resolve` (each repository call is its own
short read), and in `add` run `validateBoard` before opening the transaction (move the
persistence into a small `@Transactional` service method).

---

## 7. Framework exceptions become 500 (Medium)

> **Fixed 2026-09-19** (branch `task/error-500`). `GlobalExceptionHandler` gained
> `handleFrameworkRejection`, one method mapped to `NoResourceFoundException`,
> `NoHandlerFoundException`, `HttpRequestMethodNotSupportedException`,
> `HttpMediaTypeNotSupportedException`, `HttpMediaTypeNotAcceptableException` and
> `ServletRequestBindingException`. Its parameter is typed `ErrorResponse` — the interface all
> six implement — so the status and the response headers are read off the exception rather
> than re-derived; that is what puts `Allow: POST` on the 405 and `Accept: application/json`
> on the 415. The annotation still lists concrete classes, because its `value()` is
> `Class<? extends Throwable>[]` and an interface is not a `Throwable`. Both 404 shapes are
> listed so the contract no longer depends on `spring.web.resources.add-mappings`; no
> property was changed. Details are fixed strings: `NoResourceFoundException.getMessage()` is
> "No static resource watchlist/nope.", which echoes the caller's path and advertises an
> internal dispatch detail that means nothing for a JSON API. These log one WARN line with
> method, path, status and code, and no stack trace — `log.error("Unhandled exception", ex)`
> no longer fires for them. `MissingPathVariableException` is mapped separately back to the
> 500 path, because Spring types it 500 for a reason: it means our own routing is wrong, and
> it should keep its trace.
>
> **Deliberately unchanged:** an unknown path still returns **401**, not 404, for an anonymous
> caller. `anyRequest().authenticated()` means the entry point answers before the dispatcher
> ever runs. Changing that would need the filter chain to second-guess which routes exist, and
> would turn the 401/404 split into an endpoint-enumeration oracle for unauthenticated
> scanners. The fix covers authenticated callers and everything under `/auth/**` —
> `GET /auth/login` (405) reproduced anonymously and is fixed.
>
> Tests 240 → 253: nine cases in `GlobalExceptionHandlerTest`, plus
> `GlobalExceptionHandlerWiringTest` — the suite's first MockMvc test — which proves the 405,
> missing-param and 415 exceptions genuinely *reach* the advice, not merely that the mapping
> is right. Standalone MockMvc registers no static resource handler, so its unmapped-path case
> yields `NoHandlerFoundException`, not the production `NoResourceFoundException`; a
> `@WebMvcTest` would cover that but would be the suite's first Spring context test, so that
> one is pinned by a unit test on the real exception object plus the live run.
> **Live-verified** against the dev database on a second port, with the pre-fix build still
> running for comparison: unknown path authenticated 500 → 404 `not_found`, `GET /auth/login`
> 500 → 405 with `Allow: POST`, a `text/plain` body → 415 with `Accept`, and the
> 400 / 409 / validation / malformed paths byte-identical. Across the whole run the log had
> zero ERROR lines, zero stack frames, and exactly one WARN per rejected request. The text
> below is the original report.

**Symptom.** An unknown path, a wrong HTTP method (`GET /auth/login`), or a missing query
parameter all answer `500 {"code":"internal_error"}` and log a stack trace at ERROR instead of
404 / 405 / 400.

`jobx-backend/src/main/java/com/jobx/controller/GlobalExceptionHandler.java:87`
```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Full detail server-side only — never leak internals to the client
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of(500, "internal_error", "something went wrong"));
    }
```

**Fix direction.** Add handlers for `NoResourceFoundException` (404),
`HttpRequestMethodNotSupportedException` (405), `MissingServletRequestParameterException`
(400), and `ErrorResponse` in general, before the catch-all.

---

## 8. Malformed `Location` header becomes a 500 (Low)

> **Fixed 2026-09-19** (branch `task/location-header`). The resolve now goes through a new
> package-private `SafeUrlFetcher.nextHop(URI, String)`, which answers empty instead of
> throwing, so a site's bad header ends the sniff as an ordinary dead end and step 4 PROBE
> still runs. Two adjacent defects on the same line went with it: a **blank or absent**
> `Location` used to resolve back to the page already being fetched and cost `MAX_REDIRECTS`
> repeats of the same request, and a redirect to a refused target (`mailto:`, a private
> address, a host that won't resolve) used to surface as a **400 blaming the URL the user
> typed** — `UnsafeUrlException` now escapes only from hop 0, which is what `fetch`'s javadoc
> already promised. The SSRF refusal still logs at INFO inside the guard on every hop. Tests:
> `unusableLocationHeaderIsADeadEnd` and `resolvesOrdinaryRedirectTargets` in
> `SafeUrlFetcherTest`. The text below is the original report.

**Symptom.** A careers site that redirects with a syntactically invalid `Location` makes
`POST /watchlist/resolve` return 500 for the user.

**Cause.** `URI.resolve(String)` throws `IllegalArgumentException`, and that line sits outside
the try/catch that guards the exchange.

`jobx-backend/src/main/java/com/jobx/resolve/SafeUrlFetcher.java:119`
```java
            Response response;
            try {
                response = exchange(current);
            } catch (Exception e) {
                log.debug("Careers page fetch failed for {}: {}", current, e.toString());
                return Optional.empty();
            }
            if (response == null) {
                return Optional.empty();
            }

            if (response.location() != null) {
                // Resolve relative Location headers against the URL we just asked.
                current = current.resolve(response.location());     // can throw
                continue;
            }
```

**Fix direction.** Wrap the resolve in try/catch and return `Optional.empty()` on failure,
matching the "a failure is never an exception here" contract in the class comment.

---

## 9. `?next=` deep link is ignored by the login page (Low)

> **Fixed 2026-09-20** (branch `task/next-issue`). `LoginPage` reads `next` from
> `ActivatedRoute` and navigates with `navigateByUrl(safeNext(next))`. The new pure helper
> `core/util/redirect.ts` passes only a plain in-app absolute path, query string included. It
> falls back to `/dashboard` for a scheme, a protocol-relative `//host` or `/\host`, control
> characters, and `/login` / `/register`, which `guestGuard` would bounce. Two adjacent paths
> that dropped the user's place were fixed with it:
> - **401 expiry.** `errorInterceptor` now sends `?expired=1&next=<current url>`, so a mid-use
>   expiry returns the user to the same page after re-login.
> - **Register detour.** "Create one" and "Sign in" forward `next` between the two auth pages,
>   and `RegisterPage` honours it after sign-up.
>
> `expired` is now read from the same `queryParamMap` snapshot instead of `location.search`.
> Tests 53 → 57: a `safeNext` suite in `util.spec.ts`, and a 401 case in
> `session-reset.spec.ts` asserting the `next` query param. The text below is the original
> report.

**Symptom.** Opening `/watchlist` while logged out redirects to `/login?next=/watchlist`;
after signing in the user lands on `/dashboard`.

`jobx-frontend/src/app/core/guards/auth.guard.ts:12`
```ts
  return router.createUrlTree(['/login'], { queryParams: { next: state.url } });
```

`jobx-frontend/src/app/features/auth/login.page.ts:120`
```ts
    this.auth.login({ email, password: this.password() }).subscribe({
      next: () => void this.router.navigate(['/dashboard']),
```

**Fix direction.** Read `next` from `ActivatedRoute.queryParamMap` and navigate there when it
is a safe relative path, else `/dashboard`.

---

## 10. Copy says scores update "on the next check" (Low)

> **Fixed 2026-09-20** (branch `task/score-next-check-problem`). The toast now reads
> "Preferences saved · feed rescored". `AppShell.onPreferencesSaved` is gone, along with its
> stale comment and the redundant forced `GET /profile/filter`. `FilterProfileStore.save()`
> already sets the profile and reloads the feed. The modal's unused `saved` output was removed.
> The frozen mockup still has the old string, which is expected.

**Symptom.** After saving preferences the toast promises a future update, but
`PUT /profile/filter` runs `MatchingService.rescoreForWatcher` synchronously and the feed is
reloaded immediately. Users may wait for a cycle that already ran.

`jobx-frontend/src/app/shared/overlays/filter-profile-modal.ts:186`
```ts
      this.toasts.ok('Preferences saved · scores update on the next check');
```

`jobx-frontend/src/app/shared/layout/app-shell.ts:85`
```ts
  protected onPreferencesSaved(): void {
    // Scores are recomputed server-side on the next fetch cycle, so the existing
    // feed is unchanged — but the rail's preference panel is not.
    this.profile.load({ force: true });
  }
```

Backend behaviour for reference, `FilterProfileController.java:72`:
```java
        // Reconcile the existing feed against the new rules — without this, a
        // profile created after a board was already fetched leaves the feed
        // empty until the board happens to post something new.
        matchingService.rescoreForWatcher(user, saved);
```

**Fix direction.** Change the toast to "Preferences saved · feed rescored", and drop or
reword the `onPreferencesSaved` comment (the forced reload is redundant since `save()` already
sets the profile).

---

## 11. No per-board lock between "Check now" and the scheduled cycle (Low)

> **Fixed 2026-09-20** (branch `task/check-now-issue`). `FetchScheduler.fetchCompany` now
> takes a per-company try-lock (a `ConcurrentHashMap`-backed set of in-flight company ids,
> released in `finally`) around the dedup reads, the fetch and the persist. A second fetch of
> a board that's already being fetched does nothing and returns
> `FetchResult.alreadyRunning()`. The cycle just moves on. `fetchNow` answers it with the same
> 200-with-zeros as the shared cooldown, so the frontend needs no change: the running fetch
> scores for every ACTIVE watcher, the caller included. This also closes the two-watchers
> version of the race, where both clicks passed the cooldown check before either fetch had
> stamped `lastFetchedAt`.
>
> **In-memory, not `pg_try_advisory_xact_lock`:** an xact lock only lives as long as its
> transaction, so it would have to span the outbound fetch, and a session lock would pin a
> connection just the same. Either one brings back #6. The app is single-instance
> (`RateLimitFilter` makes the same assumption). The post-fetch id re-read in
> `persistFetched` stays as a cheap fallback. Tests 258 → 263: four in
> `FetchSchedulerSharedJobsTest` (same board skipped while one fetch is blocked mid-flight,
> other boards not blocked, lock released after a board failure and after a persist
> exception) and one in `WatchlistControllerFetchTest`. With the lock disabled, the
> concurrency test fails. The text below is the original report.

**Symptom.** If a user clicks "Check now" while the scheduler is mid-fetch of the same board
(Workable boards take a while), both paths pass the `existsBy` check for the same new posting.
The loser trips `UNIQUE (company_id, external_id)`; its whole transaction (all new jobs,
matches and the health stamp) rolls back, and the manual caller sees
`409 "resource already exists"`.

`jobx-backend/src/main/java/com/jobx/scheduler/FetchScheduler.java:140`
```java
        for (Job job : fetchedJobs) {
            // Dedup: skip if we've seen this external_id for this company before.
            if (jobRepository.existsByCompanyAndExternalId(company, job.getExternalId())) {
                continue;
            }
            ...
            // New job — save it (once, shared by all watchers)
            Job saved = jobRepository.save(job);
```

`WatchlistController.java:214` — the manual path enters `fetchCompany` with no coordination:
```java
        FetchScheduler.FetchResult result = fetchScheduler.fetchCompany(company, user);
```

**Fix direction.** Take a per-company advisory lock (`pg_try_advisory_xact_lock(hash(id))`) or
an in-memory `ConcurrentHashMap<UUID, Lock>` at the top of `fetchCompany`, and have
`fetchNow` return the "checked just now" 200 when the lock is held.

---

## 12. `%` / `_` not escaped in the catalog search `LIKE` (Low)

> **Fixed 2026-09-26** (branch `task/like-escape-fix`). `CompanyResolver.searchCatalog` now
> passes the query through a new `escapeLike` helper, and both `LIKE`s in
> `CompanyRepository.searchByNameOrToken` carry `ESCAPE '!'`. `!` rather than the suggested
> backslash, because HQL string literals give the backslash a meaning of their own. Verified
> against the dev database: a raw `%` returned all 29 companies, the escaped `%` and `_`
> return 0, and `ra` still returns its 3. Tests 263 → 264 (`catalogEscapesLikeWildcards` in
> `CompanyResolverTest`). The text below is the original report.

**Symptom.** Typing `%` in the add-company field returns the whole companies table (capped at
24 rows by the page size); `_` matches any single character.

`jobx-backend/src/main/java/com/jobx/repository/CompanyRepository.java:534`
```java
    @Query("SELECT c FROM Company c WHERE LOWER(c.displayName) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(c.boardToken) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<Company> searchByNameOrToken(@Param("query") String query, Pageable pageable);
```

**Fix direction.** Escape `\`, `%` and `_` in the caller (`CompanyResolver.searchCatalog`)
and add `ESCAPE '\\'` to the JPQL.
