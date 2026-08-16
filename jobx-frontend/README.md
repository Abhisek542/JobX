# Jobx frontend

Angular 21 dashboard for Jobx — step 5 of `CLAUDE.md`'s build order, built to
`docs/uiux_plan.md` against the frozen mockup `docs/jobx-focused-feed-mockup.html`.

## Run

```bash
npm start          # dev server on http://localhost:4200
npm run build      # production bundle
npm test           # unit tests (pure logic only)
```

The backend must be on `http://localhost:8080` with the `dev` profile — its CORS
allow-list already contains `http://localhost:4200` (`application.yml`). A CORS
failure means the origin changed, not that CORS is missing.

```bash
cd ../jobx-backend && ./mvnw spring-boot:run
```

## Shape

```
src/app/
├── core/            api clients · DTO models · interceptors · guards · stores
├── features/        dashboard (+ feed.store, feed-logic) · matches · watchlist · profile · auth
├── shared/          layout · feed · rail · overlays · ui
└── ../styles/       _tokens.scss and the token-driven global stylesheet
```

- **Standalone components, signals, `OnPush`, no NgRx.** One `signal` holds the
  feed; filtered/searched/sorted/paged/counts are all `computed`, so no two views
  of it can drift.
- **All CSS is token-driven.** Colours only ever resolve through a custom
  property in `styles/_tokens.scss`, which is what makes the light/dark toggle
  total. Never hardcode a colour in a component.
- **The frozen mockup is the spec**, minus the three approved changes in
  `docs/uiux_plan.md` §0: two rail panels (not three), no greeting (a slim action
  bar instead), and numbered pagination at 10/page.

## Honesty constraints (`docs/uiux_plan.md` §7)

The UI never invents data the API cannot back:

- No location, description excerpt, employer posting date, or verified badge —
  `MatchResponse` has none of them. "Found 3h ago" is `createdAt`, which is when
  *Jobx* first saw the role, and is never labelled as the employer's posting date.
- Company "logos" are initials on a hue derived from the name. No logo service is
  called.
- Watchlist health is concrete client-computed counts ("2 checking fine · 2
  refresh issue"), never a percentage.
- The detail drawer says out loud that the full description needs a
  `GET /matches/{id}` endpoint that does not exist yet.
- A failed board says **"Refresh issue · last tried 12m ago"**, not "last worked"
  as the mockup did: the backend stamps `lastFetchedAt` on failed attempts too
  (`FetchScheduler.recordFailure`), so that timestamp is the last *attempt*.

## Deviations from `docs/uiux_plan.md`, and why

| Plan | Built | Why |
|---|---|---|
| §2 Karma/Jasmine | **Vitest** | Angular 21's `ng new` default; Karma is deprecated in v21. The specs are the same describe/it/expect, still pure-logic only. |
| §6 per-component `:host` | one global rule | The routed pages and small UI components need transparent hosts (`display: contents`) so `<main>`/`<aside class="rail">` land in the shell's grid. Declared once in `styles/_layout.scss` rather than as ten near-identical `:host` blocks. |

## Verified against the live backend

Registered a user, set a filter profile, watched four real boards, fetched, and
scored 99 real matches. Confirmed live: save/applied/dismissed persist across a
reload · `?page=N` survives refresh and resets on filter change · manual check
renders 200/429/502 distinctly · duplicate company shows the 409 · a fresh account
gets onboarding (not an error) for the `GET /profile/filter` 404.

Not visually verified: the ≤1280px and ≤900px breakpoints (the automation browser
window could not be resized). The media queries are ported verbatim from the
frozen mockup.
