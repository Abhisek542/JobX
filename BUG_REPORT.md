# Jobx — Bug Report (2026-09-12)

Found by a full read of `jobx-backend/` and `jobx-frontend/` on branch `task/bug-findFix`.
Gaps that `CLAUDE.md` already records as deliberate (unpaginated `GET /matches`, no `SAVED`
status, login timing, N+1 on the feed) are excluded. Fixed items are marked **Fixed** below.

| # | Severity | Area | Summary |
|---|----------|------|---------|
| 1 | High | Frontend | Sign-out does not clear the data stores — next user sees the previous user's data |
| 2 | High | Backend | **Fixed 2026-09-13** — Workable / SmartRecruiters re-fetch detail for every tombstoned posting, every cycle |
| 3 | Medium | Backend | Email is case-sensitive at register and login |
| 4 | Medium | Backend | Experience penalty never fires for open-ended ranges ("5+ years") |
| 5 | Medium | Frontend | Typeahead pick shows "0 open roles" and a blank board link |
| 6 | Medium | Backend | Long outbound HTTP calls run inside DB transactions |
| 7 | Medium | Backend | Framework exceptions (404 path, 405 method, missing param) become 500 |
| 8 | Low | Backend | Malformed `Location` header on a careers site becomes a 500 |
| 9 | Low | Frontend | `?next=` deep link is set by the guard but ignored by the login page |
| 10 | Low | Frontend | Copy says scores update "on the next check"; backend rescores immediately |
| 11 | Low | Backend | No per-board lock between "Check now" and the scheduled cycle |
| 12 | Low | Backend | `%` / `_` not escaped in the catalog search `LIKE` |

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

---

## 2. Detail calls are spent on every tombstoned posting, every cycle (High)

> **Fixed 2026-09-13** (branch `task/tombstoned-issue`). `FetchScheduler` now builds a
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

---

## 4. Experience penalty never fires for open-ended ranges (Medium)

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

---

## 6. Long outbound HTTP calls run inside DB transactions (Medium)

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

> **Still open.** The snippet below predates the #2 fix. Dedup now checks in-memory sets
> (`FetchFilter`), and the scheduler re-reads stored ids after the fetch returns. That keeps
> the race window about as narrow as the old per-posting `existsBy`, but it doesn't close
> it. The per-company lock below is still the fix.

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
