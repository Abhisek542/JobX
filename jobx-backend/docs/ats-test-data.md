# ATS test data — companies, board tokens, fetchers

Manual-testing reference for a **clean database**. Every token below was hit live
against its public ATS API on **2026-08-22** and returned HTTP 200 with the job
count shown. Field-level API notes live in `ats-api-reference.md`; this file is
just "what to type into the app".

> **Board tokens are case-sensitive.** `Sprinto`, `Aspora` and
> `razorpaysoftwareprivatelimited` are exactly as written. Lever and Ashby 404 on
> the wrong case, which the app surfaces as **502 / Refresh issue** — not a bug.

---

## The companies

`Usable jobs` = what actually lands in the `jobs` table after the fetcher's own
filtering (Greenhouse drops prospect posts, Ashby drops `isListed: false`,
Workable dedupes repeated shortcodes). Counts drift as boards change — they are a
sanity check, not an assertion.

### GREENHOUSE — `GreenhouseFetcher`

| Company  | Board token                      | Usable jobs | Notes |
|----------|----------------------------------|-------------|-------|
| Razorpay | `razorpaysoftwareprivatelimited` | 25          | India fintech; the original verification board |
| PhonePe  | `phonepe`                        | 68          | 1 prospect post filtered out (`internal_job_id: null`) |
| Groww    | `groww`                          | 5           | Tiny board — good for a fast end-to-end pass |
| Mixpanel | `mixpanel`                       | 96          | Non-India, engineering-heavy titles |
| GitLab   | `gitlab`                         | 204         | Largest board here; use it to feel pagination + scoring at volume |

### LEVER — `LeverFetcher`

| Company    | Board token  | Usable jobs | Notes |
|------------|--------------|-------------|-------|
| FamPay     | `fampay`     | 14          | Small, India, mixed eng/design/marketing |
| Sprinto    | `Sprinto`    | 35          | **Capital S** |
| Meesho     | `meesho`     | 49          | India e-commerce |
| Zeta       | `zeta`       | 23          | India fintech |
| Porter     | `porter`     | 28          | India logistics |
| Netomi     | `netomi`     | 24          | AI / customer support |
| MindTickle | `mindtickle` | 21          | India SaaS |

### ASHBY — `AshbyFetcher`

| Company | Board token | Usable jobs | Notes |
|---------|-------------|-------------|-------|
| Aspora  | `Aspora`    | 6           | **Capital A**; was 18 on 2026-08-02, the board shrank |
| Atlan   | `atlan`     | 5           | India data-catalog startup |
| Linear  | `linear`    | 32          | |
| Sardine | `sardine`   | 35          | Fintech / risk |
| Notion  | `notion`    | 128         | Large board |
| Ramp    | `ramp`      | 136         | Large board |

### WORKABLE — `WorkableFetcher`

**Workable is the slow one.** The list endpoint carries no descriptions, so the
first fetch of a board makes one extra detail call per job. Apna's first fetch
takes roughly a minute; later fetches are near-instant because the N+1 guard only
fetches details for shortcodes not already stored.

| Company   | Board token | List rows | Unique jobs | Notes |
|-----------|-------------|-----------|-------------|-------|
| Epignosis | `epignosis` | 5         | 4           | **Start here** — smallest Workable board, whole flow in seconds |
| Foodics   | `foodics`   | 22        | 21          | MENA; 1 duplicate shortcode |
| Apna      | `apna`      | 158       | 126         | India; 32 duplicate rows — the board that proves shortcode dedup works |

---

## Fast manual run (clean DB)

1. **Register** a fresh user in the app (`/register`). A brand-new account should
   land on the onboarding state, not an error — the `GET /profile/filter` 404 is
   expected and handled.
2. **Set a filter profile** (Profile page, or `PUT /profile/filter`). See the
   suggested profile below — a bad one silently yields zero matches.
3. **Add companies** (Add company button, or `POST /watchlist`). Suggested minimum
   spread, one per fetcher, all small boards: Groww · FamPay · Atlan · Epignosis.
4. **Check now** on each row, or wait for `FetchScheduler` (every 30 min).
5. Feed should populate with scored matches. Save / Mark applied / Dismiss, reload,
   confirm they persisted.

### Suggested filter profile that actually matches

```json
{
  "keywords": ["engineer", "software", "backend", "java", "python", "data", "product", "designer"],
  "excludeWords": ["sales", "recruiter"],
  "expMin": 2,
  "expMax": 6
}
```

**Why the exclude list is so short:** hard exclusion scans the *whole
description*, and ATS boilerplate is full of innocent words. `lead` and `intern`
once matched every Razorpay/PhonePe posting through their "About us" text and
zeroed the entire matches table (CLAUDE.md, 2026-07-18). Word-boundary matching
fixed the `Java`-inside-`JavaScript` class of false positive, but a *real
standalone* word sitting in boilerplate still excludes the job. If the feed comes
back empty, suspect `excludeWords` first.

Symbol keywords (`C++`, `C#`, `.NET`) work since 2026-08-15 — worth one test
profile of their own.

---

## Via API instead of the UI

```bash
TOKEN=$(curl -s -X POST localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"manual-test@jobx.dev","password":"testpass123"}' | jq -r .token)

curl -X PUT localhost:8080/profile/filter \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"keywords":["engineer","backend","data"],"excludeWords":["sales"],"expMin":2,"expMax":6}'

add() { curl -s -X POST localhost:8080/watchlist \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"companyName\":\"$1\",\"atsPlatform\":\"$2\",\"boardToken\":\"$3\"}"; echo; }

add Groww     GREENHOUSE groww
add FamPay    LEVER      fampay
add Atlan     ASHBY      atlan
add Epignosis WORKABLE   epignosis
```

Then `POST /watchlist/{id}/fetch` per row, and `GET /matches`.

### Or seed straight into Postgres

`src/main/resources/db/seed/test-watchlist.sql` inserts the older six-company set.
For the spread above, replace `:test_user_id` with your real UUID:

```sql
INSERT INTO watched_companies (id, user_id, company_name, ats_platform, board_token, status, created_at)
VALUES
  (gen_random_uuid(), :test_user_id, 'Groww',     'GREENHOUSE', 'groww',     'ACTIVE', now()),
  (gen_random_uuid(), :test_user_id, 'FamPay',    'LEVER',      'fampay',    'ACTIVE', now()),
  (gen_random_uuid(), :test_user_id, 'Atlan',     'ASHBY',      'atlan',     'ACTIVE', now()),
  (gen_random_uuid(), :test_user_id, 'Epignosis', 'WORKABLE',   'epignosis', 'ACTIVE', now());
```

---

## Deliberately broken inputs (for the error states)

| Goal | What to add | Expected |
|------|-------------|----------|
| **502 + "Refresh issue"** | `GREENHOUSE` / `definitely-not-a-real-board` | Fetch fails, `lastFetchStatus: FAILED`, rail shows "Refresh issue · last tried Nm ago" |
| **502 via wrong case** | `LEVER` / `sprinto` (lowercase) | Lever 404s — same failure path, easy to mistake for a code bug |
| **409 duplicate** | Add `groww` twice | Second add rejected |
| **429 cooldown** | Press "Check now" twice inside 5 min | Cooldown is `jobx.fetch.manual-cooldown-ms` (default 5 min), tracked via `last_fetched_at` |
| **UNSUPPORTED platform** | `UNSUPPORTED` / anything | Currently accepted and set ACTIVE — a known gap, not a passing test |

Dead tokens confirmed dead, don't retry: Lever `postman`, Ashby `hasura`,
Workable `zerodha`.

---

## Re-verifying these tokens later

Boards go dead. This regenerates the counts above (needs Node, no dependencies):

```bash
node --input-type=module -e '
const t=[["GREENHOUSE","groww"],["LEVER","fampay"],["ASHBY","atlan"],["WORKABLE","epignosis"]];
const u=(p,x)=>({GREENHOUSE:`https://boards-api.greenhouse.io/v1/boards/${x}/jobs`,
 LEVER:`https://api.lever.co/v0/postings/${x}?mode=json`,
 ASHBY:`https://api.ashbyhq.com/posting-api/job-board/${x}`,
 WORKABLE:`https://apply.workable.com/api/v1/widget/accounts/${x}`}[p]);
for (const [p,x] of t) { const r = await fetch(u(p,x)); const j = await r.json();
 console.log(p, x, r.status, p === "LEVER" ? j.length : j.jobs.length); }
'
```
