# Jobx UI/UX Guide

Final selected direction: `mockup-command-center-v3.html`.

Reference files:

- HTML prototype: `docs/mockup-command-center-v3.html`
- Original visual reference: `MockUp.png`

## 1. Product direction

Jobx should feel like a focused job-search command center, not a generic job board.

The primary user goal is:

> “Show me the best new roles from companies I care about, explain why they matched, and help me track what I did with them.”

The UI should prioritize:

- fresh job matches
- match score visibility
- matched skills / keywords
- watched company health
- quick status updates: `NEW`, `SEEN`, `APPLIED`, `DISMISSED`
- lightweight filtering and sorting

Avoid turning v1 into:

- a full application CRM
- a resume builder
- an AI chatbot interface
- an auto-apply tool
- a generic public job board

## 2. Final layout

Use the Command Center v3 layout.

Page structure:

```text
AppShell
├── Sidebar
│   ├── jobx logo
│   ├── Dashboard
│   ├── Matches
│   ├── Watchlist
│   ├── Profile
│   ├── Unlock more matches card
│   └── Collapse control
├── Header
│   ├── Greeting
│   ├── Subheading
│   ├── Theme toggle
│   ├── Add company button
│   └── Avatar
├── Metric cards
│   ├── Top matches
│   ├── High scores
│   ├── Applied
│   └── Watchlist
├── Main match board
│   ├── Title row
│   ├── Search/filter row
│   ├── Match rows
│   └── Pagination/footer placeholder
└── Right rail
    ├── Watchlist health
    └── Search preferences
```

## 3. Branding

Use the logo style from `MockUp.png`.

Logo text:

```text
jobx
```

Rules:

- exact lowercase structure: `jobx`
- `job` is white in the sidebar
- `x` is blue
- do not use `Jobx`, `JOBX`, or mixed casing in the sidebar logo

Recommended CSS:

```css
.wordmark {
  font-size: 37px;
  line-height: .95;
  font-weight: 850;
  letter-spacing: -2.4px;
}

.wordmark .job {
  color: #fff;
}

.wordmark .x {
  color: #315cff;
}
```

## 4. Typography

Use a modern system font stack close to the reference image:

```css
font-family: Inter, -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, Arial, sans-serif;
```

Typography behavior:

- use large, confident headers
- use tight letter spacing for headings
- keep labels compact
- keep table rows readable
- avoid small low-contrast text

Suggested sizes:

| Element | Size |
| --- | --- |
| Sidebar logo | 37px |
| Page heading | 35px |
| Header subtitle | 17px |
| Metric number | 27px |
| Match title | 16px |
| Body text | 14–15px |
| Metadata | 12.5–13.5px |

## 5. Theme system

Build light and dark mode from the beginning using CSS variables or Angular theme tokens.

Do not hardcode colors inside Angular components.

### Light theme

Light mode should still use the dark navy sidebar from `MockUp.png`.

Core tokens:

```css
--bg: #f8faff;
--panel: #ffffff;
--panel-2: #f6f8fc;
--line: #e2e8f0;
--text: #080f24;
--muted: #56637a;
--brand: #2455f4;
--good: #219b68;
--lime: #a7d900;
--warn: #f59e0b;
--sidebar: #031433;
--sidebar-deep: #010b20;
--sidebar-active: #2557f5;
```

### Dark theme

Dark mode should preserve the sidebar identity, but deepen the app background and panels.

Core tokens:

```css
--bg: #070b13;
--panel: #101824;
--panel-2: #162130;
--line: #27364a;
--text: #f2f6ff;
--muted: #a7b3c7;
--brand: #7895ff;
--good: #3bd59a;
--lime: #b8e51b;
--warn: #f8bc52;
--sidebar: #010a1c;
--sidebar-deep: #010714;
--sidebar-active: #244fee;
```

Persist theme locally in `localStorage` for v1. No backend field is required yet.

## 6. Angular component plan

Recommended component split:

```text
app-shell
├── sidebar
├── top-header
├── metric-card
├── match-board
│   ├── match-filters
│   ├── match-row
│   └── match-score
├── watchlist-health-card
├── search-preferences-card
├── add-company-modal
└── filter-profile-modal
```

Suggested routes:

```text
/dashboard
/matches
/watchlist
/profile
/login
/register
```

For v1, `/dashboard` can contain the selected Command Center layout. The sidebar routes can be added incrementally.

## 7. Data mapping

### Match board

Backend source:

```http
GET /matches
```

Use `MatchResponse`:

```ts
type MatchResponse = {
  id: string;
  jobId: string;
  jobTitle: string;
  companyName: string;
  applyUrl: string;
  score: number;
  matchedKeywords: string[];
  status: 'NEW' | 'SEEN' | 'APPLIED' | 'DISMISSED';
  createdAt: string;
};
```

UI mapping:

| UI element | Backend field |
| --- | --- |
| Job title | `jobTitle` |
| Company | `companyName` |
| Apply link | `applyUrl` |
| Score circle | `score` |
| Skill chips | `matchedKeywords` |
| Status dropdown | `status` |
| New/recent sorting | `createdAt` |

### Search preferences

Backend source:

```http
GET /profile/filter
```

Use `FilterProfileResponse`:

```ts
type FilterProfileResponse = {
  id: string;
  keywords: string[];
  excludeWords: string[];
  expMin: number | null;
  expMax: number | null;
  updatedAt: string;
};
```

### Watchlist

Backend source:

```http
GET /watchlist
```

Use `WatchedCompanyResponse`:

```ts
type WatchedCompanyResponse = {
  id: string;
  companyName: string;
  atsPlatform: 'GREENHOUSE' | 'LEVER' | 'ASHBY' | 'WORKABLE';
  boardToken: string;
  status: 'ACTIVE' | 'PAUSED' | 'UNSUPPORTED';
  lastFetchedAt: string | null;
  createdAt: string;
};
```

## 8. Interactions

### Match status update

Use:

```http
PATCH /matches/{id}
```

Body:

```json
{
  "status": "APPLIED"
}
```

Recommended behavior:

- opening/viewing a match can mark it `SEEN`
- “Mark applied” sets `APPLIED`
- dismiss action sets `DISMISSED`
- do not claim that Jobx applied on behalf of the user

### Add company

Use:

```http
POST /watchlist
```

Fields:

- `companyName`
- `atsPlatform`
- `boardToken`

UX rule:

The `boardToken` must come from the real careers URL. Do not guess it.

### Check company now

Use:

```http
POST /watchlist/{id}/fetch
```

Show:

- “Checked just now”
- “3 new matches”
- or cooldown/error message from the API

### Filter profile editing

Use:

```http
PUT /profile/filter
DELETE /profile/filter
```

UX copy should explain:

- keywords use OR matching
- exclude words hard-remove jobs
- experience is a soft score adjustment, not a hard filter

## 9. Empty states

Use the Guided Setup direction for empty states.

Key cases:

| State | UI behavior |
| --- | --- |
| No filter profile | Show setup checklist and “Add keywords” CTA |
| No watched companies | Show “Add company” as primary CTA |
| Watchlist exists but no fetch yet | Show “Waiting for first check” |
| Matches empty after fetch | Show “No matching roles yet” and suggest broadening keywords |
| API error | Show clear retry message using `ApiError.detail` |

## 10. Responsive behavior

Desktop is primary for v1.

Still support tablet/mobile gracefully:

- hide sidebar behind a mobile menu below `900px`
- stack metric cards into 2 columns, then 1 column
- convert match table rows into cards
- move right rail below match board

## 11. Implementation notes

Start with static Angular components using mock data shaped exactly like backend DTOs. Then wire services.

Recommended order:

1. App shell + theme tokens
2. Sidebar + header
3. Match board with mock data
4. Status update interactions
5. Watchlist/search-preference cards
6. Add company modal
7. Filter profile modal
8. Empty/error states
9. API wiring

Do not add frontend fields that the backend cannot provide unless they are clearly marked as display-only placeholders.

