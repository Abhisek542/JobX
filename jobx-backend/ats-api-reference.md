# ATS API reference (verified field-level notes)

Not loaded by Claude Code by default — read this when resuming Lever/Ashby/Workable
fetcher work, or when touching Greenhouse field-parsing logic. Summary decisions live
in `CLAUDE.md`; this file is the detailed backup.

## Greenhouse — VERIFIED (Phase 0 complete)

Confirmed against a real live board (Razorpay, token
`razorpaysoftwareprivatelimited` — board token must be read from the real careers page
URL, never guessed from company name) and cross-checked against
developers.greenhouse.io/job-board.html.

- **List endpoint** `GET /v1/boards/{token}/jobs` returns: `id`, `internal_job_id`,
  `title`, `updated_at` (ISO 8601 with tz offset, e.g. `2016-01-14T10:55:28-05:00` —
  usable directly for `posted_at`), `requisition_id`, `location.name`, `absolute_url`,
  `language`, `metadata`.
- **Add `?content=true`** for: `content` (full HTML description, entity-escaped),
  `departments[]`, `offices[]`.
- **`metadata` is per-company, not a fixed schema** — e.g. Razorpay uses `"Job
  Location"`, PhonePe uses `"Requisition Location"`. Don't design the data model
  assuming a consistent shape; treat as optional/inspect-per-company, or skip for v1 matching.
- **Prospect posts pollute the `jobs` array** — "register your interest" pages with
  `internal_job_id: null`. Filter with `WHERE internal_job_id IS NOT NULL`.
- **No pagination** — full response in one call, `meta.total` gives count.
- **`first_published` is a stable cross-company field**, good for `platform_posted_at`.
- Harvest API v1/v2 deprecation (Aug 31, 2026) does **not** affect this — that's a
  separate authenticated internal-recruiter API. The public Job Board API is unaffected.

## Lever — confirmed live via public demo account

`api.lever.co/v0/postings/leverdemo?group=team&mode=json`. Field shape: `id`, `text`
(title), `categories.{team, department, location, commitment}`, `createdAt` (epoch
**milliseconds**, needs conversion), `hostedUrl`, `applyUrl`, `descriptionPlain`,
`lists[]` (structured bullets). **No `updated_at` field at all** — only `createdAt`.

Real, currently-live Indian boards worth testing next: FamPay (`fampay`), Sprinto
(`Sprinto`), Postman (`postman`).

## Ashby — confirmed live via real company board

`api.ashbyhq.com/posting-api/job-board/ramp` (Ramp). Field shape: `jobs[]` with `id`,
`title`, `department`, `team`, `employmentType`, `location`, `secondaryLocations[]`,
`publishedAt` (proper ISO, good for `posted_at`), `isListed`, `isRemote`,
`descriptionHtml`/`descriptionPlain`, `jobUrl`, `applyUrl`.

**Stale token flag:** Hasura's board token may be dead — their careers page now
redirects to `hasura.io/careers/` under PromptQL branding (likely rebrand/pivot).
Verify their actual current slug, or swap to Aspora (`jobs.ashbyhq.com/Aspora`, live
Indian fintech) before building against Hasura.

## Workable — endpoint pattern confirmed via docs, not yet fully live-tested

Pattern confirmed via Workable's help docs and third-party sources, but a full live
JSON fetch hasn't been completed in this project yet.

**Stale assumption flag:** Zerodha's real careers page (`careers.zerodha.com`) looks
custom-built, not Workable-hosted — confirm before using as the test target. A
confirmed live, active Indian Workable board to use instead: Apna
(`apply.workable.com/api/v1/widget/accounts/apna`).

## Not blocking anything right now

None of the Lever/Ashby/Workable notes above are blocking current work — they're
reference to pick back up once Greenhouse-only work (and the open Match-creation bug
in CLAUDE.md) is done and the user explicitly decides to resume other ATS platforms.
