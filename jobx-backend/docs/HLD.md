# Jobx Backend — High-Level Design (Current State)

**Version:** 1.0 — August 2026
**Status:** Discovery feature live, Tailoring (Step 6) not started

---

## 1. System Overview

Jobx is an India-first job discovery tool. Users watch company career portals
(Greenhouse, Lever, Ashby, Workable); the system polls these ATS APIs on a
30-minute cycle, normalizes postings into a unified schema, scores each job
against a per-user filter profile, and surfaces the best matches in a ranked
feed with a direct apply link.

**What it is:** a multi-tenant SaaS backend that ingests job postings from
multiple ATS platforms, applies rule-based scoring, and serves a REST API for
the Angular dashboard.

**What it is not:** no AI/LLM (planned for Step 6), no pgvector/semantic
matching, no autofill, no resume editor.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Angular Dashboard                        │
│              (jobx-frontend/ — separate build, port 4200)       │
│         Standalone components, signals, JWT bearer auth          │
└──────────────────────────┬──────────────────────────────────────┘
                           │ HTTPS (CORS allow-listed)
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Spring Boot Backend                         │
│                      (this codebase)                            │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────┐  │
│  │ Auth     │  │ Watchlist│  │ Matches  │  │ Filter Profile │  │
│  │Controller│  │Controller│  │Controller│  │  Controller    │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └───────┬────────┘  │
│       │              │             │                │            │
│  ┌────▼──────────────▼─────────────▼────────────────▼────────┐  │
│  │              Security Filter Chain                        │  │
│  │  RateLimitFilter → JwtAuthFilter → AuthorizationFilter    │  │
│  └──────────────────────────┬────────────────────────────────┘  │
│                             │                                   │
│  ┌──────────────────────────▼────────────────────────────────┐  │
│  │                    Core Services                          │  │
│  │                                                           │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │  │
│  │  │FetchScheduler│  │ MatchScorer  │  │  JwtService    │  │  │
│  │  │  (30-min)   │  │  (0-100)     │  │  (HMAC-SHA)   │  │  │
│  │  └──────┬──────┘  └──────────────┘  └────────────────┘  │  │
│  │         │                                                │  │
│  │  ┌──────▼─────────────────────────────────────────────┐  │  │
│  │  │            Fetcher Registry                        │  │  │
│  │  │  Greenhouse │ Lever │ Ashby │ Workable             │  │  │
│  │  │  (AtsFetcher interface → normalized Job entities)  │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                             │                                   │
│  ┌──────────────────────────▼────────────────────────────────┐  │
│  │                   Spring Data JPA                          │  │
│  │              (Hibernate, ddl-auto=validate)                │  │
│  └──────────────────────────┬────────────────────────────────┘  │
└─────────────────────────────┼───────────────────────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │    PostgreSQL      │
                    │  (Flyway-managed)  │
                    └───────────────────┘
```

---

## 3. Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 17 |
| Framework | Spring Boot | 3.2.5 |
| ORM | Hibernate (via Spring Data JPA) | — |
| Database | PostgreSQL | — |
| Migrations | Flyway | — |
| HTTP Client | Spring WebFlux WebClient (blocking `.block()`) | — |
| Auth | Spring Security + JWT (jjwt) | 0.12.6 |
| HTML Stripping | jsoup | 1.17.2 |
| Build | Maven | — |
| Frontend | Angular 21 (separate repo: `jobx-frontend/`) | — |

---

## 4. Data Model

### Entity Relationship Diagram

```
┌──────────────┐
│    users      │
├──────────────┤
│ id        PK │
│ email     UQ │
│ password_hash│
│ role          │  USER / ADMIN
│ created_at   │
└──────┬───────┘
       │ 1
       ├──┬──────────────────────────┐
       │  │                          │
       │  │ 1                        │ 1
       │  ▼                          ▼
       │ ┌──────────────────┐  ┌─────────────────────┐
       │ │ filter_profiles  │  │ watched_companies    │
       │ ├──────────────────┤  ├─────────────────────┤
       │ │ id           PK  │  │ id              PK  │
       │ │ user_id      FK  │←─│ user_id         FK  │
       │ │ keywords     []  │  │ company_name        │
       │ │ exclude_words[]  │  │ ats_platform        │
       │ │ exp_min          │  │ board_token         │
       │ │ exp_max          │  │ status              │
       │ │ updated_at       │  │ last_fetched_at     │
       │ └──────────────────┘  │ last_fetch_status   │
       │                       │ last_fetch_error    │
       │                       │ created_at          │
       │                       └──────────┬──────────┘
       │                                  │ 1
       │                                  │
       │ 1                                ▼ N
       │                       ┌─────────────────────┐
       │                       │      jobs            │
       │                       ├─────────────────────┤
       │                       │ id              PK  │
       │                       │ company_id      FK  │
       │                       │ external_id         │  ATS's own ID (dedup)
       │                       │ ats_platform        │
       │                       │ title               │
       │                       │ description    TEXT │
       │                       │ location            │
       │                       │ exp_min             │
       │                       │ exp_max             │
       │                       │ apply_url           │
       │                       │ platform_posted_at  │  ATS timestamp (display)
       │                       │ first_seen_at       │  Jobx's observation time
       │                       │ raw_json       JSONB│
       │                       │ created_at          │
       │                       └──────────┬──────────┘
       │                                  │
       │                                  │ N
       │ 1                                ▼ N
       │                       ┌─────────────────────┐
       │                       │     matches          │
       │                       ├─────────────────────┤
       └──────────────────────→│ id              PK  │
             user_id      FK   │ user_id         FK  │
                               │ job_id          FK  │
                               │ score               │  0-100
                               │ matched_keywords[]  │
                               │ status              │  NEW/SEEN/APPLIED/DISMISSED
                               │ created_at          │
                               └─────────────────────┘
```

### Key Schema Constraints

| Table | Unique Constraint | Purpose |
|---|---|---|
| `users` | `email` | One account per email |
| `watched_companies` | `(user_id, board_token, ats_platform)` | User can't watch same board twice |
| `jobs` | `(company_id, external_id)` | Dedup per watch row |
| `matches` | `(user_id, job_id)` | One match per user per job |

### Flyway Migrations

| Migration | Schema Change |
|---|---|
| `V1__create_schema.sql` | 5 tables + 7 indexes, `pgcrypto` for UUIDs |
| `V2__add_user_role.sql` | `users.role` column (`USER`/`ADMIN`, CHECK constraint) |
| `V3__add_fetch_health.sql` | `last_fetch_status` + `last_fetch_error` on `watched_companies` |

---

## 5. Component Design

### 5.1 Security Layer

**Filter chain order:**
```
RateLimitFilter → JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter
```

| Component | Responsibility |
|---|---|
| `RateLimitFilter` | Per-IP fixed-window limiter on `/auth/*` (10/min, 429 + `Retry-After`) |
| `JwtAuthenticationFilter` | Extracts Bearer token → parses JWT → loads `User` → sets SecurityContext |
| `JwtService` | HMAC-SHA key, `generateToken` (7-day expiry), `parseAndValidate` |
| `SecurityConfig` | Stateless sessions, CSRF disabled, CORS allow-list, `/auth/**` + `/error` open |

**Auth flow:**
1. `POST /auth/register` → BCrypt hash → return JWT
2. `POST /auth/login` → `passwordEncoder.matches()` → return JWT
3. Subsequent requests: `Authorization: Bearer <token>` → filter sets `@AuthenticationPrincipal User`

### 5.2 ATS Fetcher Layer

```
                    ┌──────────────────┐
                    │ FetcherRegistry  │  AtsPlatform → AtsFetcher mapping
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐──────────────┐
              ▼              ▼              ▼              ▼
     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
     │ Greenhouse   │ │    Lever     │ │    Ashby     │ │   Workable   │
     │   Fetcher    │ │   Fetcher    │ │   Fetcher    │ │   Fetcher    │
     └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
```

| Fetcher | API Endpoint | Auth | Notes |
|---|---|---|---|
| Greenhouse | `boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true` | None | `?content=true` for full descriptions |
| Lever | `api.lever.co/v0/postings/{company}?mode=json` | None | Multi-field description assembly |
| Ashby | `api.ashbyhq.com/posting-api/job-board/{token}` | None | Public posting API, not `jobPosting.list` |
| Workable | `apply.workable.com/api/v1/widget/accounts/{token}` + `/v2/jobs/{code}` | None | Two-call: list → detail per job |

**All fetchers:**
- Implement `AtsFetcher` interface: `supports()` + `fetch(WatchedCompany)`
- Return normalized `List<Job>` — nothing downstream sees raw ATS JSON
- Throw `AtsFetchException` on board failure (vs. empty list for genuinely empty boards)
- Use shared `ExperienceParser` for best-effort exp range extraction from JD text

### 5.3 Match Scoring Engine

`MatchScorer` — rule-based, deterministic, per-user.

```
Input: FilterProfile + Job
Output: ScoredJob { score: 0-100, excluded: boolean, matchedKeywords: List }
```

**Scoring rules (verified, port this — don't redesign):**

| Phase | Rule | Weight |
|---|---|---|
| 1. Hard exclude | Any `excludeWords` word in title/description → drop | — |
| 2. Keyword match | OR logic, ≥1 keyword must match. Title hit = 2x, desc-only = 1x | 0–70 |
| 3. Experience | SOFT filter — overlapping = 30 pts, distance penalty: `max(0, 30 - dist*10)` | 0–30 |

**Keyword scoring formula:**
```
keywordScore = round(70 * min(1, actualWeight / (keywords.size * 2)))
actualWeight = titleHits*2 + descOnlyHits*1
```

**`containsWord` (symbol-aware boundary matching):**
- `\b` applied conditionally — only on the side ending in a word character
- `.NET` matches `ASP.NET`, `C++` matches `C/C++`
- `Java` does NOT match `JavaScript`

### 5.4 Scheduler & Manual Fetch

| Mode | Entry Point | Transaction Boundary |
|---|---|---|
| Scheduled (30 min) | `@Scheduled` on `fetchAllCompanies()` | One `@Transactional` per company (via self-proxy) |
| Manual "Check now" | `POST /watchlist/{id}/fetch` | Same `fetchCompany()` flow |

**`FetchResult` record:** `(newJobs, newMatchesForOwner, failed)`

**Failure isolation:**
- Each company's fetch is an independent transaction via `ObjectProvider<FetchScheduler>` proxy
- A board failure records `FAILED` health + sanitized error, doesn't affect other companies
- Manual fetch has 5-minute per-company cooldown (rides on `last_fetched_at`, no separate state)

---

## 6. REST API

### Authentication
All endpoints below require `Authorization: Bearer <token>` except `/auth/**`.

### Endpoints

| Method | Path | Auth | Status | Description |
|---|---|---|---|---|
| `POST` | `/auth/register` | Anon | 201 | Create user, return JWT |
| `POST` | `/auth/login` | Anon | 200 | Login, return JWT |
| `GET` | `/watchlist` | User | 200 | List user's watched companies |
| `POST` | `/watchlist` | User | 201 | Add company; 409 on duplicate |
| `PATCH` | `/watchlist/{id}` | User | 200 | Pause/resume/status change |
| `DELETE` | `/watchlist/{id}` | User | 204 | Remove watch (cascades to jobs) |
| `POST` | `/watchlist/{id}/fetch` | User | 200 | Manual "Check now"; 404/409/429/502 |
| `GET` | `/matches` | User | 200 | All matches, desc by createdAt |
| `PATCH` | `/matches/{id}` | User | 200 | Update status (SEEN/APPLIED/DISMISSED) |
| `GET` | `/profile/filter` | User | 200 | Get filter profile |
| `PUT` | `/profile/filter` | User | 200 | Upsert filter profile |
| `DELETE` | `/profile/filter` | User | 204 | Delete filter profile |
| `POST` | `/dev/fetch` | Anon* | 200 | Trigger full fetch cycle (*dev only) |

### Error Contract

All non-2xx responses return `ApiError`:
```json
{
  "status": 409,
  "code": "conflict",
  "detail": "duplicate watch for this board",
  "fieldErrors": null
}
```

Handled by `GlobalExceptionHandler` (`@RestControllerAdvice`), with special-case
handlers for 401 entry point and 429 rate limiter to maintain the same shape.

---

## 7. Configuration

| Property | Default | Description |
|---|---|---|
| `spring.profiles.default` | `dev` | Enables `/dev/**` and JWT secret fallback |
| `jobx.fetch.interval-ms` | `1800000` | 30-minute poll interval |
| `jobx.fetch.manual-cooldown-ms` | `300000` | 5-min per-company manual fetch cooldown |
| `jobx.http.connect-timeout-ms` | `10000` | Outbound ATS connect timeout |
| `jobx.http.response-timeout-ms` | `60000` | Outbound ATS response timeout |
| `jobx.cors.allowed-origins` | `http://localhost:4200` | Explicit allow-list, never `*` |
| `jobx.jwt.secret` | dev fallback | Must be real `JOBX_JWT_SECRET` in non-dev |
| `jobx.jwt.expiration-ms` | `604800000` | 7-day JWT lifetime |
| `jobx.auth.rate-limit.max-attempts` | `10` | Per-IP per-endpoint per-window |
| `jobx.auth.rate-limit.window-seconds` | `60` | Rate limit window |

---

## 8. Deployment Model

- **Single-instance** — no clustering, no distributed lock, no shared cache
- **Dev mode:** Docker Postgres (`localhost:5432`), `spring.profiles.default=dev`
- **Prod readiness:** `@Profile("dev")` gates `DevController`; `JwtService` refuses
  to start on missing/default secret outside dev
- **Frontend:** Separate Angular build (`jobx-frontend/`), served from its own origin
- **No CI/CD** documented yet — manual `mvn package` / `java -jar`

---

## 9. Test Strategy

**77 tests** across 11 test classes, JUnit 5 + Mockito:

| Area | Tests | Approach |
|---|---|---|
| MatchScorer | 29 | Fixture-based scoring rules, symbol keywords, experience |
| FetchScheduler health | 7 | Failure recording, isolation, error truncation |
| Watchlist fetch endpoint | 6 | Success, cooldown, ownership, 502 |
| GlobalExceptionHandler | 5 | Every exception type → ApiError |
| RateLimitFilter | 4 | Threshold, per-IP, per-endpoint |
| ATS Fetchers | 18 | Fixture JSON files (captured live), mapping correctness |
| TextLists | 4 | Normalization, dedup, null handling |

---

## 10. Known Issues & Open Decisions

### Critical: Multi-User Job Duplication (OPEN DEFECT)

`jobs.company_id` references `watched_companies(id)` (per-user watch row), not a
shared companies table. When two users watch the same board, every posting is
stored twice and each user gets two matches for it.

**Candidate fixes:**
- **A (contained):** Drop cross-user fan-out — each watch row scores only its owner
- **B (structural):** Introduce a `companies` table keyed by `(ats_platform, board_token)`

### Backend Gaps the Frontend Papers Over

| Gap | Current Workaround |
|---|---|
| No `GET /matches/{id}` | Drawer admits this on screen |
| No `location`/`platformPostedAt` in `MatchResponse` | Honesty constraint — not rendered |
| `GET /matches` returns DISMISSED, unpaginated | Client-side filter + no `SAVED` status |
| No real `SAVED` status | `SEEN` maps to "Saved" in the UI |

### Deliberately Unfixed

- No `X-Forwarded-For` handling in `RateLimitFilter`
- `AuthController.login` timing distinguishes registered emails
- `POST /watchlist` accepts `UNSUPPORTED` platform and sets it ACTIVE
- Fetchers use `asText("")` for NOT NULL `title`/`apply_url` rather than skipping
