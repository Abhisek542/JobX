# Jobx — Angular dashboard design spec (step 5 pre-work)

Design-first artifact, no code. Written from the `MockUp` image (light theme) plus
the real backend contracts in this repo (`WatchlistController`, `MatchController`,
`FilterProfileController`, their DTOs and entities) and `V1_IMPROVEMENTS.md`'s P0
acceptance criteria. Goal: settle layout, data mapping, states, and open questions
before any Angular component is written.

Every data point below is labeled either **REAL** (a field that exists today in a
DTO/entity, cited by name) or **GAP** (something the mockup shows that has no
backend support yet). Per the project's honesty-first positioning, gaps are called
out explicitly rather than quietly invented.

---

## 1. Theme tokens

DECIDED in `CLAUDE.md`: ship both themes, user-toggleable, built on theme-able
tokens from day one.

**Dark** — pulled directly from `jobx-dashboard.html`'s `:root` block (exact hex,
already shipped and proven):

| Token | Hex | Use |
| --- | --- | --- |
| `--bg` | `#0d1117` | page background |
| `--panel` | `#121821` | sidebar, cards |
| `--panel-2` | `#161d28` | nested surfaces, chip-box, inputs |
| `--line` | `#222c3a` | borders |
| `--line-soft` | `#1b2430` | subtle dividers |
| `--text` | `#e6edf3` | primary text |
| `--text-2` | `#9aa7b6` | secondary text |
| `--text-3` | `#5f6e7e` | tertiary/muted text |
| `--mint` | `#3ad6a0` | brand accent, high score, active nav |
| `--mint-dim` | `#1f8e6c` | accent borders/hover |
| `--amber` | `#e0a93b` | mid score, warnings |
| `--red` | `#e2615f` | errors, destructive |

**Light** — visually estimated from the `MockUp` image (not color-picked
pixel-exact — do that against the source file before finalizing CSS):

| Token | Approx. hex | Use |
| --- | --- | --- |
| `--bg` | `#f5f7fb` | page background |
| `--panel` | `#ffffff` | sidebar, cards |
| `--panel-2` | `#f0f2f7` | nested surfaces |
| `--line` | `#e4e8f0` | borders |
| `--text` | `#0f1729` | primary text |
| `--text-2` | `#5b6472` | secondary text |
| `--text-3` | `#8b93a1` | tertiary/muted text |
| `--brand-blue` | `#2f5bff` (approx) | primary buttons, active nav, logo mark |
| `--green` | `#1ea672` (approx) | high match-score ring/text |
| `--yellow-green` | `#8fb93b` (approx) | mid match-score ring (the 82% ring) |

Note the dark theme's accent is mint-green; the light theme's is blue (primary
button, "jobx" nav highlight) with green reserved for score rings. Keep these as
separate token roles (`--brand` vs `--score-high`) rather than one shared accent
token, since they diverge by theme.

## 2. Score ring color — open question

`jobx-dashboard.html`'s proven `ringColor()` function uses two thresholds:
`score >= 75 → mint`, `score >= 60 → amber`, else muted gray. That logic is part of
the verified port and shouldn't be redesigned.

The mockup image shows 94% and 89% as the same solid green, but 82% visibly
shifted toward yellow-green — suggesting a continuous gradient rather than two
flat bands. **Decide before building the ring component:** reuse the existing
two-band system (simpler, consistent with the proven dark mockup) or move to a
continuous green→yellow→red gradient (matches the new image more closely, more
implementation work). Not blocking — flagging so it's a conscious choice.

## 3. Layout / component tree

```
AppShell
├── Sidebar (jobx wordmark, nav: Dashboard / Matches / Watchlist / Profile, collapse)
├── Header (greeting, "Add company" button, avatar menu)
├── DashboardPage
│   ├── FilterBar (search input, view pills, sort dropdown)
│   ├── MatchFeed
│   │   └── MatchCard × N (repeats per match)
│   └── RightRail
│       ├── SearchPreferencesPanel
│       ├── ProfileCompletenessCard
│       ├── WatchlistHealthPanel
│       └── AddCompanyCard
├── FilterEditModal (Edit link on SearchPreferencesPanel)
├── AddCompanyModal ("Add company" button)
└── JobDetailDrawer (View details link — proven pattern in jobx-dashboard.html)
```

## 4. Component-by-component data mapping

### MatchCard (per item in "Top matches")

Source: `GET /matches` → `MatchResponse[]`.

| Mockup element | Field | Status |
| --- | --- | --- |
| Job title | `jobTitle` | REAL |
| Company name | `companyName` | REAL |
| Verified badge (checkmark next to company) | — | **GAP** — no `verified` flag anywhere in `WatchedCompanyResponse` or `MatchResponse`. Either drop the badge or define what "verified" means (e.g., ATS-confirmed board vs. manually added) and add the field. |
| Location / work-mode tag | — | **GAP** — `Job.location` exists on the entity but `MatchResponse` doesn't expose it. Needs adding to `MatchResponse` (this is exactly the P1 "company/job details" item in `V1_IMPROVEMENTS.md`). |
| Skill-tag chips | `matchedKeywords` | REAL, reuse as chips directly |
| Match-% ring | `score` | REAL (0–100, per `MatchScorer`) |
| "Matched key skills" line | `matchedKeywords` (joined) | REAL |
| "View details" link | — | Needs `GET /matches/{id}` for full detail (P1, not built) — or link straight to `applyUrl` for v1 and defer a real detail view. |
| Save action | — | **GAP — needs a decision, see §6** |
| Mark applied action | `PATCH /matches/{id}` `{status: APPLIED}` | REAL, `UpdateMatchStatusRequest` |
| Dismiss action | `PATCH /matches/{id}` `{status: DISMISSED}` | REAL |
| Star/pin icon (top card in mockup) | — | **GAP** — no pinning concept exists. Likely just visual emphasis for the top-ranked card, not a stored field — confirm before building it as a real toggle. |

### SearchPreferencesPanel

Source: `GET /profile/filter` → `FilterProfileResponse`.

| Mockup element | Field | Status |
| --- | --- | --- |
| Roles | — | **GAP** — `FilterProfileResponse` has no `roles` field, only `keywords`. Mockup shows "Roles: Backend Engineer" as distinct from "Keywords: Java, Spring Boot, PostgreSQL." Either roles are just the first keyword(s) displayed differently, or this is a field that doesn't exist yet. Needs a decision, not an assumption. |
| Keywords | `keywords` | REAL |
| Experience | `expMin`–`expMax` | REAL |
| Edit → opens modal → `PUT /profile/filter` | `FilterProfileRequest` | REAL |

### ProfileCompletenessCard (85% ring)

**Entirely a GAP.** No completeness concept exists anywhere in the backend —
not in `User`, not in `FilterProfileResponse`, not in `V1_IMPROVEMENTS.md`. If kept,
this has to be computed client-side from whatever fields are present (e.g., has a
filter profile + has ≥1 watched company + keyword count), since there's no
server-side field to bind to. Recommend treating as P1/nice-to-have, not step-5
required, unless you want to define the formula now.

### WatchlistHealthPanel

Source: `GET /watchlist` → `WatchedCompanyResponse[]`.

| Mockup element | Field | Status |
| --- | --- | --- |
| Company name | `companyName` | REAL |
| "Checked 8m ago" | `lastFetchedAt` | REAL — format as relative time client-side |
| "Refresh issue" warning state | — | **GAP** — confirmed by reading `WatchedCompany.java` and `FetchScheduler.java`: there is no `lastFetchStatus`/`lastFetchError` field, and fetch failures aren't caught or persisted anywhere today. This is explicitly the unbuilt P1 item "Watchlist fetch health" in `V1_IMPROVEMENTS.md`. The panel cannot show this state honestly until that backend work lands — for step 5, either omit the warning state or ship it visually disabled/"coming soon." |

### AddCompanyCard / AddCompanyModal

Source: `POST /watchlist` with `WatchedCompanyRequest {companyName, atsPlatform, boardToken}`. All REAL, all required, `boardToken` must come from the live careers URL per `CLAUDE.md` (never guessed). `AtsPlatform` enum currently has a stale-looking comment marking LEVER/ASHBY/WORKABLE as "deferred — fetcher stub only," which contradicts `CLAUDE.md`'s "all four platforms live-verified 2026-08-02." Worth a one-line fix in the enum's Javadoc so the dropdown copy doesn't get written from outdated comments.

### Header greeting ("Good morning, Arjun") + avatar ("AK")

**GAP.** `User` entity has only `email`, `passwordHash`, `role`, `createdAt` — no
name field at all. Either add a `displayName` (or `firstName`/`lastName`) column,
or fall back to deriving initials/greeting from the email local-part for v1. This
needs a decision before the header component can bind to anything real.

## 5. States (grounded in `V1_IMPROVEMENTS.md` P0 acceptance criteria)

- **Empty watchlist** — new user, zero `WatchedCompany` rows: show the
  "Add more companies" CTA as the primary content, not an empty match feed.
- **Empty match feed, watchlist non-empty** — company added but not yet polled, or
  polled with zero matches: distinguish "not checked yet" (`lastFetchedAt` is null)
  from "checked, nothing matched" (`lastFetchedAt` set, feed empty).
- **Fetch error** — currently unrepresentable (see WatchlistHealthPanel gap above).
- **Loading** — standard skeleton/spinner while `GET /matches` and `GET /watchlist`
  resolve.
- **"Check now" feedback** — `POST /watchlist/{id}/fetch` → `ManualFetchResponse
  {newJobs, newMatches}`. REAL. Render "Checked just now; N new matches" when
  `newMatches > 0`, "No new roles" otherwise. 429 response means cooldown active —
  surface the `Retry-After`/error detail from `ApiError`, don't silently fail.

## 6. Save / Mark applied / Dismiss — status model conflict

`V1_IMPROVEMENTS.md` explicitly defines the intended progression: `NEW → SEEN →
APPLIED`, plus `DISMISSED`. `Match.MatchStatus` in code matches this exactly:
`NEW, SEEN, APPLIED, DISMISSED`. There is no `SAVED` status.

The mockup shows three explicit actions — **Save**, **Mark applied**, **Dismiss** —
plus a "Saved" filter pill alongside "New" and "Applied." This doesn't map cleanly
onto the four real statuses. Two options, needs a decision:

1. "Save" is UI-only sugar for marking a match `SEEN` (i.e., "I've looked at this
   and want to keep it around" = same status opening the card sets automatically) —
   simplest, no backend change, but then "Save" and opening a card do the same
   thing, which may confuse users if both exist as separate affordances.
2. Add a real `SAVED` status (or a separate boolean bookmark flag) — requires a
   migration and touches `MatchScorer`/`MatchResponse` not at all, just the status
   enum and controller — small backend change, but it's scope creep on an already-
   decided model and should go back to the user rather than being assumed here.

## 7. Explicitly out of scope for this spec

- No Angular code, components, or routing — that's the next step, after this spec
  is reviewed.
- No resolution of the open items in §4/§6 above — those are decisions for you,
  not assumptions for me to make silently.
- Job detail drawer beyond what `MatchResponse` already carries is P1 work
  (`GET /matches/{id}` doesn't exist yet).

## Summary of decisions needed before Angular work starts

1. Score ring: two-band (proven) vs. continuous gradient (matches new image).
2. "Verified" badge, location tag, "View details" — depend on extending
   `MatchResponse` (P1 item already scoped in `V1_IMPROVEMENTS.md`).
3. "Roles" vs. "Keywords" in the preferences panel — same field, different label,
   or genuinely a new field?
4. Profile completeness % — keep (client-computed, define formula) or cut for v1?
5. Watchlist "Refresh issue" state — needs the P1 backend work first, or ship
   without it for step 5?
6. Header display name/avatar initials — add a `User` field, or derive from email?
7. Save vs. Seen vs. a new `SAVED` status — pick one of the two options in §6.
