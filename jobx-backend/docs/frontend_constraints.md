# Jobx Frontend Constraints

This file lists the backend/API constraints that affect the Angular frontend.

The goal is to avoid designing UI behavior that the current backend cannot honestly support.

## 1. `SAVED` is not a real backend status

Current match statuses:

```java
NEW
SEEN
APPLIED
DISMISSED
```

There is no `SAVED` status.

Impact:

- A “Saved” filter cannot directly map to the backend.
- A “Save” button cannot persist a true saved/bookmarked state unless backend changes.

Frontend options:

1. Treat “Save” as `SEEN`.
2. Rename “Saved” to “Seen”.
3. Add a backend field later, such as:

```java
boolean bookmarked
```

or add a new status:

```java
SAVED
```

Recommended for v1:

Use `SEEN` internally and avoid over-emphasizing “Saved” until the backend supports it clearly.

## 2. `MatchResponse` does not expose location

`Job.location` exists in the backend entity, but `MatchResponse` currently does not return it.

Current `MatchResponse`:

```java
UUID id
UUID jobId
String jobTitle
String companyName
String applyUrl
Integer score
List<String> matchedKeywords
MatchStatus status
Instant createdAt
```

Impact:

- Match cards cannot honestly show location from `GET /matches`.
- UI examples like “Bengaluru · Remote” need either mock data or backend extension.

Needed backend change:

Add to `MatchResponse`:

```java
String location
```

Recommended for Angular v1:

If backend is unchanged, hide location or show it only after the DTO is extended.

## 3. No job detail endpoint exists

There is currently no:

```http
GET /matches/{id}
```

or:

```http
GET /jobs/{id}
```

Impact:

- “View details” drawer cannot load full job description.
- UI cannot show job excerpt, full description, platform posted date, or detailed keyword explanation unless the list endpoint is extended.

Current safe behavior:

- “View details” can open the direct `applyUrl`.
- Or it can open a lightweight drawer using only fields already present in `MatchResponse`.

Needed backend change:

Add a detail endpoint returning:

```ts
{
  id: string;
  jobId: string;
  jobTitle: string;
  companyName: string;
  location: string | null;
  description: string | null;
  applyUrl: string;
  platformPostedAt: string | null;
  firstSeenAt: string;
  score: number;
  matchedKeywords: string[];
  status: MatchStatus;
}
```

## 4. `platformPostedAt` is not exposed in `MatchResponse`

`Job.platformPostedAt` exists in the entity, but the match list DTO does not return it.

Impact:

- UI cannot show “Posted today” or “Posted 2 days ago” from the ATS timestamp.
- It can only show when Jobx created the match using `createdAt`.

Recommended for v1:

Use `createdAt` as “Added to Jobx” or “Matched 2h ago”.

Avoid labeling it as employer-posted date.

## 5. User has no display name

Current `User` entity has:

```java
email
passwordHash
role
createdAt
```

There is no:

```java
displayName
firstName
lastName
```

Impact:

- “Good morning, Arjun” cannot come from the backend.
- Avatar initials cannot come from a real user profile name.

Frontend options:

1. Derive display name from email local-part.
2. Show generic greeting: “Good morning”.
3. Add `displayName` to backend later.

Recommended for v1:

Derive from email until profile editing exists.

## 6. Watchlist fetch health is incomplete

Current `WatchedCompanyResponse` has:

```java
id
companyName
atsPlatform
boardToken
status
lastFetchedAt
createdAt
```

There is no:

```java
lastFetchStatus
lastFetchError
```

Impact:

- UI cannot reliably show “Refresh issue”.
- UI can show `ACTIVE`, `PAUSED`, or `UNSUPPORTED`.
- UI can show `lastFetchedAt`.
- It cannot distinguish “no jobs found” from “fetch failed” unless backend persists failure state.

Needed backend fields:

```java
lastFetchStatus: SUCCESS | FAILED
lastFetchError: string | null
```

Recommended for v1:

- Show last checked time.
- Show paused/unsupported state.
- Do not show fetch failure unless backend adds status/error.

## 7. Watchlist health percentage is not a backend value

The mockup shows:

```text
Watchlist health: 78%
```

The backend does not calculate this.

Impact:

- Any health percentage must be client-computed.
- Without `lastFetchStatus`, the percentage is approximate.

Possible v1 client heuristic:

```text
healthy = ACTIVE companies with lastFetchedAt present
attention = UNSUPPORTED companies + ACTIVE companies never fetched
paused = PAUSED companies
```

Recommended:

Label the card carefully. Prefer concrete counts over fake precision.

Example:

```text
8 active
2 paused
1 unsupported
```

## 8. Profile completeness is not a backend value

The mockups include profile completeness, but the backend does not return a completeness score.

Impact:

- Any completeness percentage must be frontend-only.
- It may feel arbitrary unless the formula is clear.

Possible client formula:

| Condition | Points |
| --- | --- |
| Has filter profile | 25 |
| Has at least 1 keyword | 25 |
| Has experience range | 25 |
| Has at least 1 watched company | 25 |

Recommended:

Use this only as onboarding support, not as a major dashboard metric.

## 9. Roles are not separate from keywords

`FilterProfileResponse` has:

```java
keywords
excludeWords
expMin
expMax
```

There is no separate `roles` field.

Impact:

- “Roles: Backend Engineer” is not a real backend field.
- The frontend would have to treat the first keyword as a role, which is weak.

Options:

1. Remove “Roles” from v1.
2. Display “Keywords” only.
3. Add a backend `roles` field later.

Recommended for v1:

Use “Keywords” only unless backend adds roles.

## 10. Client-side filtering only

`GET /matches` currently takes no query parameters.

Impact:

- Search, score filter, status filter, company filter, and sorting must happen in Angular.
- This is fine for v1 but may become slow with large feeds.

Recommended for v1:

Implement filtering client-side.

Later backend enhancement:

```http
GET /matches?status=NEW&minScore=70&company=Postman&sort=score&page=0&size=25
```

## 11. No pagination API yet

`GET /matches` returns a full list.

Impact:

- Pagination in the mockup is visual/client-side only.
- Angular can paginate locally, but it is not server-backed.

Recommended:

Use client-side pagination if needed, but do not imply backend pagination exists.

## 12. Company logos are not stored

No backend field returns company logo URL.

Impact:

- Match rows cannot show real company logos from the API.

Frontend options:

1. Use initials.
2. Use generated color blocks.
3. Add a frontend-only logo map for known companies.
4. Add `logoUrl` later.

Recommended for v1:

Use initials/color blocks. Avoid relying on external logo services.

## 13. Verified badge is not supported

The visual reference shows a verified badge near company names.

There is no backend `verified` field.

Impact:

- The frontend should not show a meaningful verified badge unless we define what it means.

Possible meaning:

- ATS board was successfully fetched.
- Company was added from a supported ATS.

But this requires explicit backend state.

Recommended:

Do not show verified badge in v1, or treat it as purely decorative only after product decision.

## 14. Manual fetch has cooldown

Manual fetch endpoint:

```http
POST /watchlist/{id}/fetch
```

Can return:

- `409` if company is not `ACTIVE`
- `429` if checked recently

Impact:

- Angular must handle cooldown errors clearly.
- Button should show disabled/loading/cooldown states.

Recommended UX:

- “Checking…”
- “Checked just now · 3 new matches”
- “Checked recently · try again in 120s”
- “Paused companies cannot be checked”

## 15. Auth response has email but no profile object

`AuthResponse` returns:

```java
token
expiresAt
userId
email
```

Impact:

- Header user identity is limited to email-derived display.
- Role exists in backend but is not returned in auth response.

Recommended:

Store token, expiry, userId, and email in Angular auth state.

Do not build role-specific UI yet.

## 16. Error contract exists and should be used

All non-2xx errors should use:

```ts
type ApiError = {
  status: number;
  code: string;
  detail: string;
  fieldErrors?: Record<string, string>;
};
```

Impact:

- Angular should centralize error handling.
- Form validation should display `fieldErrors`.
- General API failures should display `detail`.

Recommended:

Build one API error mapper and reuse it across forms and dashboard actions.

## 17. Final implementation rule

If the mockup shows something not backed by the current API, Angular should either:

1. hide it,
2. compute it transparently client-side,
3. label it as placeholder/demo-only,
4. or wait for a backend change.

Do not silently invent production data in the frontend.

