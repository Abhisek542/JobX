# Jobx Frontend — UI/UX Build Plan (Angular)

> **Status:** approved 2026-08-15. This is the working reference for step 5 of
> `CLAUDE.md`'s build order. Read this before writing Angular code.
>
> **Scope:** the logged-in Angular app. The public/SEO Astro layer (step 7) and
> the tailoring feature (step 6) are out of scope.

---

## 0. Read-me-first: the mockup is frozen and deliberately out of date

The approved visual reference is `docs/jobx-focused-feed-mockup.html`. It is
**frozen** — do not edit it to match the app.

Three approved changes were made *after* the mockup was signed off, so the app
will intentionally differ from it in exactly these three places. If you find
yourself "fixing" the app back toward the mockup here, stop — the app is right.

| # | Change | Mockup shows | App must do |
|---|---|---|---|
| 1 | Rail CTA card | Three rail panels, last is "Add more companies" | **Two** rail panels: Search preferences, Watchlist health |
| 2 | Greeting | "Good evening, {name}" + subhead + demo chip | **No greeting.** Slim right-aligned action bar: theme toggle + "Add company" |
| 3 | Pagination | All matches in one list | **Numbered pagination**, 10/page (§4) |

Everything else in the mockup — layout, spacing, tokens, card anatomy, score
ring, drawer, modals, empty states, toasts — is the spec. The mockup's
`visible()`, `band()`, `logoStyle()`, `relTime()` and `renderWatchlist()`
functions are reviewed, working logic; port them rather than reinventing them.

`UIUX_guide.md` is **stale** in §2, §3, §5, §8 and §10 — it still describes the
abandoned Command Center v3 direction. Where it disagrees with this document,
this document wins. See §3 for the specific overrides.

---

## 1. Product direction

Jobx is a focused job-search command center, not a job board. The user goal:

> "Show me the best new roles from companies I care about, explain why they
> matched, and help me track what I did with them."

Prioritize: fresh matches · score visibility · matched keywords · watchlist
health · quick status changes · lightweight filter/sort.

Do **not** build toward: a full application CRM · a resume builder · a chatbot ·
auto-apply · a generic public job board.

Honesty is the brand position. §7 is not optional polish — it is the product.

---

## 2. Stack

Verified available on this machine: Node 20.19.6, npm 10.8.2, Angular CLI 21.0.4.

| Choice | Decision | Why |
|---|---|---|
| Framework | Angular 21, **standalone components**, no NgModules | Current idiom |
| State | **Signals** (`signal` / `computed` / `effect`) | The app's state is small and derivation-shaped |
| Store library | **None (no NgRx)** | Three signal services cover this surface; a store would be more machinery than state |
| HTTP | `provideHttpClient(withInterceptors([...]))` — functional interceptors | |
| Control flow | `@if` / `@for` / `@switch` | |
| Change detection | `OnPush` everywhere | |
| Styles | **SCSS** + CSS custom properties | Theming; never hardcode a color in a component |
| Tests | Karma/Jasmine on **pure logic only** (filter/sort/paginate, score banding, error mapping) | Template tests are low value here |

Scaffold: `ng new` with SCSS + routing, **no SSR** (the app is behind auth).

---

## 3. Locked design decisions

| Decision | Choice | Overrides |
|---|---|---|
| Direction | Focused feed | `UIUX_guide.md` §2 (Command Center v3) |
| Sidebar | White `--panel` surface in light mode. Wordmark `Jobx` — dark text, blue `x` | Guide §3 (lowercase `jobx`, white on navy) and §5's `--sidebar-*` navy tokens, which are **dropped** |
| Metric cards | **None.** Pill counts + rail carry the numbers | Guide §2, §10 |
| Type scale | 25px wordmark · 28px page headings · 16px card title · 14.5px body · 12.5–13px meta | Guide §4's 37/35/17 scale |
| Greeting | Removed | — |
| Rail CTA card | Removed | — |
| Pagination | Numbered, 10/page, client-side, URL-backed | — |
| `SEEN` semantics | `SEEN` = **user explicitly saved**. Opening a card does **not** mutate status | Guide §8 ("opening a match can mark it SEEN") |
| Theme | Light + dark, toggle in the action bar, persisted to `localStorage`, defaults to `prefers-color-scheme` | — |

### Why `SEEN` does not auto-set on open

The backend has no `SAVED` status (`MatchStatus` is NEW/SEEN/APPLIED/DISMISSED),
so the Save button maps to `SEEN`. If merely *opening* a card also set `SEEN`,
every role you glanced at would pile into the "Saved" filter and that filter
would be a lie. So opening is read-only; `SEEN` means the user chose to keep it.

Revisit if the backend gains a real `bookmarked` flag — then auto-mark-on-open
becomes safe again and guide §8 can be honored.

---

## 4. Pagination (change #3)

`GET /matches` takes no query parameters and returns the full list unpaginated
(`MatchController.list`). v1 pagination is therefore **client-side** over the
already-loaded array. Do not build UI that implies the server paginates
(`frontend_constraints.md` §10, §11).

**Control**

```
Showing 1–10 of 43
[‹ Prev]  [1]  2   3   4  [Next ›]
```

**Rules**

- Page size **10**.
- Page lives in the URL: `/dashboard?page=3`, read/written via `ActivatedRoute`
  + `Router`. A view survives refresh and is shareable.
- **Reset to page 1** whenever search text, status pill, or sort changes.
- **Clamp** when the list shrinks beneath the current page — dismissing the last
  card on page 4 lands on page 3, never on an empty page.
- Show at most 5 page numbers; use ellipses beyond that.
- Hide the whole control when the filtered result fits on one page.
- Fixed order of operations: **filter → search → sort → paginate**.
- Status pill counts always reflect the **whole feed**, never the current page.
- `aria-current="page"` on the active number; Prev/Next disabled at the ends.

Move to server-side pagination only when real feed size demands it; that needs
`GET /matches?page=&size=` on the backend first.

---

## 5. Verified API contract

Read from backend source, **not** from `frontend_constraints.md` — that file's §6
is stale (it says `lastFetchStatus` doesn't exist; it was added 2026-08-15).
This table is authoritative. Base URL `http://localhost:8080`.

| Endpoint | Success | Failures the UI must handle |
|---|---|---|
| `POST /auth/register` | 201 `AuthResponse` | 409 email taken · 400 `fieldErrors` · 429 |
| `POST /auth/login` | 200 `AuthResponse` | 401 invalid · 429 |
| `GET /matches` | `MatchResponse[]` — **all**, including `DISMISSED`, unpaginated | 401 |
| `PATCH /matches/{id}` | `MatchResponse` | 404 (also returned for not-owned) |
| `GET /watchlist` | `WatchedCompanyResponse[]` | 401 |
| `POST /watchlist` | 201 `WatchedCompanyResponse` | 409 already watching this ATS+token |
| `PATCH /watchlist/{id}` | `WatchedCompanyResponse` | 404 |
| `DELETE /watchlist/{id}` | 204 | 404 |
| `POST /watchlist/{id}/fetch` | `ManualFetchResponse` | **404** not owned · **409** not ACTIVE · **429** cooldown · **502** board unreachable |
| `GET /profile/filter` | `FilterProfileResponse` | **404 = no profile yet → onboarding, not an error** |
| `PUT /profile/filter` | `FilterProfileResponse` | 400 (no keyword survived normalization / `expMin > expMax`) |
| `DELETE /profile/filter` | 204 | 404 |

### Types

```ts
type MatchResponse = {
  id: string; jobId: string; jobTitle: string; companyName: string;
  applyUrl: string; score: number; matchedKeywords: string[];
  status: MatchStatus; createdAt: string;
};

type WatchedCompanyResponse = {
  id: string; companyName: string; atsPlatform: AtsPlatform; boardToken: string;
  status: CompanyStatus; lastFetchedAt: string | null;
  lastFetchStatus: FetchStatus | null;      // null = never checked
  createdAt: string;
};

type FilterProfileResponse = {
  id: string; keywords: string[]; excludeWords: string[];
  expMin: number | null; expMax: number | null; updatedAt: string;
};

type AuthResponse       = { token: string; expiresAt: string; userId: string; email: string };
type ManualFetchResponse= { companyId: string; companyName: string; checkedAt: string;
                            newJobs: number; newMatches: number };
type ApiError           = { status: number; code: string; detail: string;
                            fieldErrors?: Record<string, string> };

type MatchStatus   = 'NEW' | 'SEEN' | 'APPLIED' | 'DISMISSED';
type CompanyStatus = 'ACTIVE' | 'PAUSED' | 'UNSUPPORTED';
type FetchStatus   = 'SUCCESS' | 'FAILED';
type AtsPlatform   = 'GREENHOUSE' | 'LEVER' | 'ASHBY' | 'WORKABLE' | 'UNSUPPORTED';
```

`AuthResponse` carries **no display name and no role** — derive the sidebar name
and avatar initials from the email local-part (`frontend_constraints.md` §5).
Every non-2xx body is `ApiError`, including the hand-written 401 entry point and
429 limiter.

---

## 6. Structure

```
src/app/
├── core/
│   ├── api/            match.api.ts · watchlist.api.ts · filter-profile.api.ts · auth.api.ts
│   ├── models/         one file per DTO in §5
│   ├── interceptors/   auth.interceptor.ts   (attach bearer)
│   │                   error.interceptor.ts  (ApiError → AppError, once)
│   ├── guards/         auth.guard.ts
│   └── services/       auth.store.ts · theme.service.ts · toast.service.ts
├── features/
│   ├── dashboard/      dashboard.page.ts · feed.store.ts
│   ├── matches/ watchlist/ profile/     (routes; may start as thin wrappers)
│   └── auth/           login.page.ts · register.page.ts
├── shared/
│   ├── layout/         app-shell · sidebar · action-bar
│   ├── feed/           match-card · match-actions · score-ring · feed-toolbar · pagination
│   ├── rail/           search-preferences-card · watchlist-health-card
│   ├── overlays/       add-company-modal · filter-profile-modal · match-detail-drawer
│   └── ui/             empty-state · toast-host · company-logo
└── styles/             _tokens.scss · _reset.scss · styles.scss
```

**Routes** — `/login`, `/register` public; `/dashboard`, `/matches`,
`/watchlist`, `/profile` behind `auth.guard`. `/dashboard` is the focused feed
and the default redirect.

### `feed.store.ts` — the heart

One `signal<MatchResponse[]>` holds the feed. Everything else is `computed`:

```
matches ──▶ filtered ──▶ searched ──▶ sorted ──▶ paged
   └──────▶ statusCounts        └──▶ totalPages
```

No duplicated arrays, so nothing can drift out of sync. Port the predicates and
the score bands straight from the mockup:

| Band | Score | Label |
|---|---|---|
| Strong | ≥ 85 | "Strong match" |
| Good | ≥ 70 | "Good match" |
| Fair | ≥ 55 | "Fair match" |
| Weak | < 55 | "Weak match" |

Search matches `jobTitle`, `companyName`, and `matchedKeywords` only — say so in
the empty state so the scope isn't a mystery.

The `ALL` pill excludes `DISMISSED`; every other pill is an exact status match.

**Status updates are optimistic**: flip the signal, fire `PATCH`, roll back and
toast on failure. Keep the undo affordance from the mockup.

**Auth**: token in `localStorage`, attached by `auth.interceptor`. On 401, clear
and redirect to `/login`. No refresh token exists — accepted for v1. Check
`expiresAt` on boot so the first request isn't already doomed.

**Errors**: `error.interceptor` maps `ApiError` to a typed `AppError` exactly
once. `fieldErrors` drives form validation; `detail` drives toasts. One mapper,
reused everywhere (`frontend_constraints.md` §16).

---

## 7. Honesty constraints — do not build these

The app must never invent data. From `frontend_constraints.md`:

- **No location, description excerpt, or employer posting date on cards.**
  `MatchResponse` has none of them. "Found 3h ago" derives from `createdAt` and
  must never be labelled as the employer's posting date (§2, §4).
- **No verified badge** (§13).
- **No separate "Roles" field** — there is only `keywords` (§9).
- **No external logo service.** Initials on a deterministic hue derived from the
  company name (§12).
- **Watchlist health is concrete counts**, client-computed — "3 checking fine ·
  1 refresh issue · 1 awaiting first check" — never a fake percentage (§7).
- **"View details"** opens a drawer built only from `MatchResponse` fields plus
  the real `applyUrl`, and says plainly that the full description needs a
  `GET /matches/{id}` endpoint that does not exist yet (§3).
- **Never claim Jobx applied on the user's behalf.**

If the design asks for something the API can't back: hide it, compute it
transparently client-side, label it demo-only, or wait for the backend (§17).

---

## 8. Empty and error states

Closes the P0 "empty/error states" item in `V1_IMPROVEMENTS.md`.

| Condition | UI |
|---|---|
| `GET /profile/filter` → 404 | Onboarding: "Set your keywords" checklist + CTA. **Not** an error toast |
| Watchlist empty | "Add your first company" as the primary CTA |
| Company added, `lastFetchedAt` null | "Waiting for first check" |
| `lastFetchStatus === 'FAILED'` | "Refresh issue" warning + Check now |
| `status === 'UNSUPPORTED'` | "Board has no public API" — no Check now button |
| `status === 'PAUSED'` | "Paused" — no Check now button |
| Matches empty after a fetch | "No matching roles yet" + broaden-keywords CTA |
| Search yields nothing | "No matches for '{q}'" + clear-filters |
| Any API failure | `ApiError.detail` + retry |

**Manual fetch feedback** (`POST /watchlist/{id}/fetch`):

| Result | Copy |
|---|---|
| 200, `newMatches > 0` | "Checked just now · 3 new matches at {company}" |
| 200, `newJobs === 0` | "Checked just now · no new roles at {company}" |
| 429 | "Checked recently · try again in a few minutes" |
| 409 | "Paused companies can't be checked" |
| 502 | "{company}'s board is unreachable — Jobx will retry automatically" |

The 502 case matters: before the 2026-08-15 backend fix this returned a cheerful
`200 {newJobs: 0}`, so a board that had been 404ing for a week read as "checked
just now, nothing new". Do not collapse 502 back into the "no new roles" path.

---

## 9. Responsive

Desktop primary. Verified breakpoints from the mockup:

| Width | Layout |
|---|---|
| > 1280px | Sidebar · feed · 328px rail |
| ≤ 1280px | Rail drops below the feed as an auto-fit grid |
| ≤ 900px | Sidebar becomes off-canvas behind a mobile bar; cards stack; drawer goes full-width |

No horizontal page scroll at any width — wide content scrolls inside its own
container. Respect `prefers-reduced-motion`.

---

## 10. Build phases

Each phase ends in something runnable.

| # | Phase | Done when |
|---|---|---|
| 1 | Scaffold + tokens + shell | Theme toggles and survives reload; sidebar collapses |
| 2 | Core plumbing + auth pages | Real register/login against the backend; wrong password shows the 401 `detail`; 429 renders sensibly |
| 3 | Feed, static + **pagination** | 43 mock matches paginate 10/page; filter/search/sort reset to page 1; page survives refresh via URL |
| 4 | Feed, live + status flow | Statuses persist across reload; dismissing the last card on a page clamps correctly |
| 5 | Rail (two panels) | Health line matches real `lastFetchStatus` values |
| 6 | Overlays | Add-company (per-ATS token hints, 409), filter-profile (≥1 keyword, `expMin ≤ expMax`), drawer, Check now with 409/429/502 |
| 7 | Empty + error states | The §8 matrix, all reachable |
| 8 | Responsive + a11y | §9 breakpoints; focus traps in overlays, `aria-current` on pagination, full keyboard path |

---

## 11. Verification

- `npx ng build` clean; `npx ng test` green on the pure-logic specs.
- Backend on `:8080` (`dev` profile), app on `:4200` — CORS already allows that
  origin (`application.yml:44`). A CORS failure means the origin changed, not
  that CORS is missing.
- **Full pilot loop against the live backend** (the `V1_IMPROVEMENTS.md`
  acceptance checklist): register → set filter profile → add a real Greenhouse
  board (`razorpay`) → Check now → see scored matches → save one → mark one
  applied → dismiss one → reload and confirm all three persisted.
- **Force the awkward paths deliberately:** a bogus board token must render
  "Refresh issue", not "no new roles"; Check now twice for the 429; a duplicate
  company for the 409; a fresh account for the `GET /profile/filter` 404.
- Paginate a real feed of >10 matches: URL page param, reset-on-filter,
  clamp-on-shrink.
- Screenshot both themes at 1536px, 1100px and 430px.

---

## 12. Known gaps and follow-ups

Not blockers for this build, but do not rediscover them:

- **`UIUX_guide.md` needs reconciling** — §2, §3, §5, §8, §10 still describe
  Command Center v3 and contradict §3 of this document.
- **`frontend_constraints.md` §6 is stale** — `lastFetchStatus` now exists.
- **Backend open defect** (`CLAUDE.md`): `jobs.company_id` references
  `watched_companies(id)` while `scoreForAllWatchers` fans each job out to every
  watcher, so two users watching the same board get duplicate jobs *and*
  duplicate matches. Invisible with one user; it will corrupt the feed at pilot.
  Recommended fix is **B** (a real `companies` table); still pending a decision.
- Backend items this build works around rather than fixes: no `GET /matches/{id}`
  detail endpoint · no `location`/`platformPostedAt` in `MatchResponse` ·
  `GET /matches` returns `DISMISSED` and is unpaginated · no `SAVED` status.
