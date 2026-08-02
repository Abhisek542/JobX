# Jobx v1: Small, high-value improvements

> **Status (2026-08-02):** the backend halves of P0 are DONE — filter-profile API,
> error contract + validation, and the safe production baseline (see per-item notes
> below). The remaining P0 items are Angular work, folded into the step-5 dashboard
> build as its acceptance criteria. Note this doc predates phase 3: the P2 "Second
> ATS" item and the multi-ATS non-goal were overtaken by events — all four ATS
> fetchers are built and live-verified as of 2026-08-02.

## Purpose

This is a deliberately narrow improvement scope for the first usable Jobx release.
Jobx's core is already sound: it polls company ATS boards, stores new jobs, and
builds a per-user match feed. The work below makes that loop usable, trustworthy,
and safe without turning v1 into a job board, an autofill tool, or an AI resume
product.

**Selection rule:** each item must be independently shippable, useful to an early
job seeker, and achievable with the existing Spring Boot + Postgres + planned
Angular stack. No new paid service or new ATS integration is required.

## Recommended release scope

### P0 — required before inviting real users

| Improvement | Why it matters | Small implementation shape |
| --- | --- | --- |
| Filter-profile onboarding and editing — **DONE 2026-08-02** (`FilterProfileController`, PUT upserts, lists normalized via `TextLists`) | The scorer exists but a new user cannot create or change the keywords, exclusions, or experience range that make the feed relevant. This is the largest functional gap. | Add authenticated `GET`, `PUT`, and `DELETE /profile/filter`. Validate/trim/deduplicate lists; allow empty exclusions; require at least one keyword to activate matching. Create a profile during onboarding, not automatically at registration. |
| Empty and error states in the dashboard | A new account, an unpolled watchlist, zero matches, a paused company, and an ATS fetch failure are all normal states. Without explicit messages they look broken. | Angular cards/messages only, plus a `lastFetchStatus`/`lastFetchError` field returned for each company if operational transparency is desired. Keep the actual raw exception server-side. |
| Search, sort, and simple filters in the match feed | Users need to rapidly find high-score, new, or still-actionable roles once the feed grows. | Client-side filtering for v1 (score, status, company, title text) and default sort: newest first, with a score-sort toggle. Move to server-side pagination only after real feed size requires it. |
| Save / apply workflow clarity | Existing statuses are good but the UI needs a clear progression: `NEW → SEEN → APPLIED`, plus `DISMISSED`. This gives users a lightweight application tracker without adding a separate product. | Mark a match `SEEN` on opening it; call `APPLIED` only after the user explicitly confirms after opening the direct link. Include the existing status filter and a small applied count. Do not infer an external application succeeded. |
| Safe production baseline — **DONE 2026-08-02** (`RateLimitFilter` 10/min/IP per endpoint, `JwtService` fail-fast, `DevController` + permitAll dev-profile-only, `spring.profiles.default: dev`) | Authentication is implemented, but a public login needs basic brute-force protection and production configuration hygiene. | Rate-limit `/auth/login` and `/auth/register` at the edge/reverse proxy or with a small Spring filter; return HTTP 429. Fail startup outside `dev` when `JOBX_JWT_SECRET` is missing/default. Remove or restrict `/dev/**` before deployment. Keep the existing generic login failure message. |
| Error contract and request validation — **DONE 2026-08-02** (`ApiError` `{status, code, detail, fieldErrors?}` via `GlobalExceptionHandler`; `@Valid` on all request DTOs; 401/429 emit the same shape) | The frontend needs predictable errors; invalid enum/JSON bodies currently depend on Spring defaults. | Enable Spring `ProblemDetail` responses or add a `@RestControllerAdvice` with `{status, code, detail}`. Add `@Valid` constraints to watchlist and status request DTOs. Return `409` for duplicate watches and `404` for another user's resource, as the current controllers intend. |

### P1 — build immediately after P0 if the dashboard is working

| Improvement | Why it matters | Small implementation shape |
| --- | --- | --- |
| “New since last visit” indicator | Timeliness is Jobx's discovery advantage. A lightweight unread indicator makes this visible without notifications. | Persist `lastFeedViewedAt` on `User`; set it through `POST /matches/mark-viewed` when the feed is opened. The UI labels matches created after that time as new. This is more reliable than treating all `NEW` statuses as unread. |
| Watchlist fetch health | A user should know whether a company has no jobs or Jobx could not fetch it. | Add `last_fetch_status` (`SUCCESS`/`FAILED`) and a short `last_fetch_error` to `watched_companies`; set them in the scheduler. Display “last checked …” and “couldn't refresh; retrying” in the watchlist. Do not expose stack traces. |
| Company/job details drawer | Match cards need enough context to decide whether to apply without leaving the dashboard. | Extend `MatchResponse` with location, `platformPostedAt`, and an excerpt of the already-stored description; add `GET /matches/{id}` for full job detail. Retain the original ATS direct-apply URL. |
| Keyword explanation | Trust is a stated differentiator. Show exactly why a score was produced, not a vague “AI match.” | Return `matchedKeywords` (already stored), score, and a deterministic label such as “Title match” or “Description match.” Do not add LLM explanations. |
| One-click re-check for a user’s watch — **DONE 2026-08-02** (backend: `POST /watchlist/{id}/fetch`, 404 non-owned / 409 non-ACTIVE / 429 within cooldown; cooldown rides on `last_fetched_at`, default 5 min via `jobx.fetch.manual-cooldown-ms`; response carries `newJobs`/`newMatches` for the button's feedback text — Angular button itself lands with step 5) | Waiting 30 minutes is frustrating immediately after adding a company. | Add a rate-limited authenticated `POST /watchlist/{id}/fetch` that verifies ownership and calls the same single-company scheduler method. A 5–10 minute per-company cooldown is enough. Keep scheduler fetching as the source of truth. |
| Export applied jobs | Gives users portability with almost no product complexity. | Client-side CSV export of matches with `APPLIED` status: company, title, URL, score, matched keywords, created date. No storage changes. |

### P2 — useful, but defer until real users ask for it

| Improvement | Why it is still small | Why it is not P0/P1 |
| --- | --- | --- |
| Email digest | Send one daily digest of new high-score jobs, with a verified unsubscribe preference. | Email delivery, bounce handling, and preference management create operational work; do it only once the feed itself proves valuable. |
| Browser notifications | Useful for fresh matches. | Requires permission UX, service-worker work, and notification preference handling. Daily email is usually simpler first. |
| Password reset | Essential once users are external. | Needs outbound email and short-lived, single-use reset tokens. Plan it together with email digests rather than adding an insecure shortcut. |
| Second ATS (Ashby/Workable/Lever) — **OVERTAKEN: done 2026-08-02**, all three built and live-verified before this doc was actioned | Existing fetcher interfaces make this contained. | ~~Retain the documented sequencing decision to defer it.~~ Superseded by the user's decision to pull phase 3 forward. |
| Notes / interview stages | A per-application note is small in isolation. | It starts expanding Jobx from discovery into a broad application-tracker product. Validate demand first. |

## Explicit non-goals for v1

- No automated application submission, form filling, or resume upload to employer ATSs.
- No generic web scraping: use documented public ATS job-board APIs.
- No LLM/semantic matching, resume rewriting, or fabricated-skills suggestions.
- No full CRM/application pipeline, recruiter outreach, or calendar system.
- ~~No multi-ATS expansion before the Greenhouse dashboard loop has been tested with
  users.~~ Overtaken 2026-08-02: multi-ATS shipped first by explicit user decision.

## Suggested implementation order

1. Add filter-profile API and dashboard onboarding; confirm a new user can register,
   add a Greenhouse company, set filters, run a fetch, and see a match.
2. Build the dashboard's match feed: cards, search/sort/filter, details drawer,
   direct-apply link, and status updates.
3. Add predictable API errors, validation, and protected production configuration.
4. Add fetch health and an authenticated, cooldown-limited manual refresh.
5. Add last-visit indicators and applied-job CSV export.
6. Run a small real-user pilot before considering a second ATS or email notifications.

## Acceptance checklist for the first pilot

- A new user can complete the full Greenhouse loop without a database/admin step.
- Every match clearly names the company, title, match score, matched keywords,
  direct apply link, and current status.
- A user can find a role by text, company, score, or status.
- The watchlist says when each company was last checked and whether a fetch failed.
- Login and registration are rate-limited, development routes are not public in a
  production profile, and a real JWT secret is mandatory there.
- A fetch error for one company never stops the next company from being processed.
- No feature claims that Jobx applied for a job, sent a resume, or inferred a skill.

## Research notes

- Greenhouse's public Job Board API explicitly provides job IDs, descriptions,
  locations, publish dates, and `content=true`; that supports a rich job-detail
  view without scraping or employer-side application automation. It also identifies
  prospect posts with a null `internal_job_id`, matching the current fetcher's
  exclusion logic. [Greenhouse Job Board API](https://developer.greenhouse.io/job-board.html)
- Lightweight application tracking—statuses plus notes—appears repeatedly in current
  job-search tools. Jobx already has the valuable minimum: status transitions. The
  recommended scope keeps that useful subset but avoids becoming a full tracker.
  [ReTrack feature overview](https://retrack.work/)
- OWASP recommends generic authentication failures and login throttling to resist
  account enumeration and password guessing. The current generic error is correct;
  rate limiting is the compact missing control. [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- Password reset should use a secure, single-use, expiring token flow and be protected
  against token guessing. That makes it worthwhile, but not a “quick button” to add
  without outbound email infrastructure. [OWASP Forgot Password Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html)
