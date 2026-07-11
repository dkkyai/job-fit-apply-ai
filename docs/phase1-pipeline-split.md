# Phase 1 — Pipeline re-architecture: Poller / Processor split + per-resource LLM gate

**Goal.** Eliminate local-LLM resource contention by consolidating *all* LLM work behind a
single consumer (the **Processor**), fed by an LLM-free **Poller** via the bridge queue.

**Scope.** Host-only (PM2). No containers in Phase 1 — the split is validated on the host first.
Containerizing the Poller + Processor is Phase 3.

## Why (recap)

Today, LLM work runs in **two independent OS processes** that hit the single-model oMLX
(`:11436`) / Ollama-local (`:11434`) at the same time with no coordination:
- **Ingestion** (cron, fire-and-forget): `ScanEmail` (LLM) → digest fan-out → `ScrapeJd` (LLM) → submit scraped `JdRecord`.
- **Worker** (continuous): `ScoreFit` + tailoring (~7 LLM) + cover letter + recruiter draft (LLM).

Consolidating both into one Processor makes an **in-process** semaphore sufficient to serialize
the shared GPU — the thing that's currently impossible across two processes.

## Target service map

```
Gmail ─poll─► Poller  (all Gmail I/O, no LLM)         Postgres (unchanged)  markserv (unchanged)
                │  submit EMAIL_RAW (idempotency_key = message-id)      ▲
                ▼                                                       │ JDBC (tracks)
              Bridge (queue + result store + artifact server + completed feed)
                ▲  claim / postResult / uploadArtifacts                │
                │                                                      ▼
              Processor  (ALL LLM + Chrome/CDP + Playwright, NO Gmail)
                scan → [digest → re-enqueue children as JD_SCRAPED] → scrape
                → CheckDuplicate → score → tailor → cover → PDF → track
                → emit result { terminal_label, draft_text?, message_id? }
                ▲ every LLM call passes the per-resource gate (LOCAL permits=1)
              Poller loop-B: drain completed feed → apply label + assemble/send draft → writeback-done
```

## Locked decisions

- **Poller = dedicated Gradle module** `services/job-fit-apply-ai-poller` (own fat jar / future container). The Processor **sheds** all Gmail deps + the token.
- **Duplicate the shared DTOs** per service (bridge work-item + result shapes). No umbrella multi-project build yet.
- **Bridge feed:** `GET /api/jobs/completed?since=<cursor>` + a `writeback_done` flag; `POST /api/jobs/{id}/writeback-done`.
- **Processor is serial in Phase 1** (one job at a time, as the worker is today). Bounded concurrency is Phase 2 — but the LLM gate is built now so Phase 2 is a config flip.
- **Drop the `--email` synchronous path.** Everything is fire-and-forget through the queue.
- **Poller owns all Gmail.** Processor *generates* the recruiter reply text (LLM) but never calls Gmail; the Poller assembles + sends the draft and applies labels.
- **LLM gate keyed by physical resource:** `LOCAL` = `{MLX_LOCAL_BASE_URL, OLLAMA_LOCAL_BASE_URL}` share **one** permit (=1); `CLOUD` = everything else, permits=N. Classified by **config endpoint membership**, not loopback (so it survives `host.docker.internal` in Phase 3).

---

## Work breakdown

### 1. Bridge (`services/job-fit-apply-ai-bridge`)

**Work-item schema — the queued item gains a type discriminator:**
- `EMAIL_RAW` — raw email (subject/body/headers/message-id + recruiter hint). Needs scan+scrape. Submitted by the Poller.
- `JD_SCRAPED` — a pre-structured `JdRecord` (today's shape). Submitted by the extension, JSearch, and by the Processor's **digest fan-out** (children are already scraped → they skip re-scan).

**Result schema — grows for write-back:** add `terminal_label` (enum string), `draft_text` (nullable), `is_recruiter` (bool), `message_id` (nullable — present only for `EMAIL_RAW`). Existing `pipeline_action` / `fit_score` / artifacts / `error` stay.

**New columns on `jobs` (SQLite, `createMissingTablesAndColumns` handles adds):** `writeback_done BOOLEAN DEFAULT 0`, plus the result fields above (or keep them inside the existing result JSON blob + a `writeback_done` column + a monotonic `completed_seq`).

**New endpoints (`Routes.kt`):**
- `GET /api/jobs/completed?since=<cursor>` → jobs that are terminal (`done`/`error`) **and** `writeback_done = false`, newest-cursor-first, returning `{ job_id, message_id, terminal_label, draft_text, is_recruiter, artifacts }`. Cursor = a monotonic `completed_seq` (assigned when a job goes terminal) so the Poller can page deterministically.
- `POST /api/jobs/{id}/writeback-done` → sets `writeback_done = true`.

**Dedup:** `EMAIL_RAW` uses the Gmail **message-id** as `idempotency_key`; the existing `findActiveDuplicate` already dedups by key, so a re-forwarded email is a no-op.

**Tests:** work-item type round-trip; completed feed filters `writeback_done`; cursor paging; message-id dedup; `writeback-done` flips the flag and removes the job from the feed.

### 2. Processor (current `services/job-fit-apply-ai-pipeline`, minus Gmail)

**Move scan/scrape/digest in (from ingestion):** `ScanEmailNode`, `ScrapeJdNode`, `nodes/scan/digest/*` become the front of the processing path.
- Claiming `EMAIL_RAW`: `ScanEmail` → if digest, fan out → **re-enqueue each child to the bridge as `JD_SCRAPED`**, then finish the parent with `terminal_label = JD_Processed_Digest` → done. Else `ScrapeJd` → the existing `ProcessingPipeline`.
- Claiming `JD_SCRAPED`: skip scan/scrape, start at `CheckDuplicate` (extension / JSearch / digest children).

**Terminal-label *decision* moves here** (the *application* moves to the Poller): the Processor maps outcome→label using the state flags it already has — `JD_Error` / `Recruiter_Response_Required` / `JD_Not_Found` (`!isJobPosting`) / `JD_Processed_Digest` / else `JD_Processed` — and puts the string in the result. `message_id` is passed through from the work item (null for non-email jobs → Poller skips Gmail labeling for those).

**Recruiter draft:** `CreateDraftReplyNode` keeps its **LLM generation** and writes `draft_text` + `is_recruiter` into the result. Delete the Gmail delivery half (moves to Poller).

**Remove from the Processor:** `google-api-client` / gmail deps in `build.gradle.kts`, `client/gmail/GmailAuth.kt`, `cli/EmailLabelingService.kt` (application half), `--reauth` / `--check-token`, the token/cred files. Rename `--worker` → `--processor` (keep `--worker` as an alias if convenient). `--init-profile` / `--test` stay.

**Per-resource LLM gate (`client/LlmClient.kt`):**
- Config: `LOCAL_LLM_ENDPOINTS = {MLX_LOCAL_BASE_URL, OLLAMA_LOCAL_BASE_URL}`, `LOCAL_MAX_CONCURRENCY=1`, `CLOUD_MAX_CONCURRENCY=4` (per-provider or one shared pool).
- `Map<Pool, Semaphore>`; `pool = LOCAL if resolvedEndpoint ∈ LOCAL_LLM_ENDPOINTS else CLOUD`. `acquire()` around the inference HTTP call, `release()` in `finally`, honoring the existing wall-clock timeout. (Serial in Phase 1 makes this forward-looking; it becomes load-bearing in Phase 2.)

**Tests:** `EMAIL_RAW` path (scan→scrape→…); `JD_SCRAPED` path (skips scan); digest fan-out re-enqueues N children as `JD_SCRAPED`; result carries `terminal_label` + `draft_text`; gate classifies MLX+Ollama-local into one LOCAL pool and Ollama-cloud into CLOUD; LOCAL permit serializes (concurrency test with a fake slow endpoint).

### 3. Poller (new `services/job-fit-apply-ai-poller`)

**Module:** Kotlin/JVM 21, Gradle + shadow jar. Deps = Gmail API client + a thin bridge HTTP client + Jackson. **No** LLM, Playwright, or LLM config.

**Move here (from the pipeline):** `GmailAuth`, the Gmail **fetch/read**, `EmailLabelingService` (application), the Gmail **draft delivery** (MIME + threading headers + Compose API), `--reauth` / `--check-token`, and the `gmail_credentials.json` / `tokens/gmail_token.json` files. Scope stays `readonly + modify + compose`.

**Loop A — intake (Gmail → bridge):** poll the Gmail search query → for each new email: apply the `JD_Processing` in-flight label → `submit(EMAIL_RAW)` with `idempotency_key = message-id`. (Continuous loop replaces the cron + heartbeat/PID guard.)

**Loop B — write-back (bridge → Gmail):** poll `GET /api/jobs/completed?since=cursor` → for each job:
1. if `message_id`: apply `terminal_label` (idempotent).
2. if `is_recruiter && draft_text`: fetch `resume.pdf` + `cover_letter.txt` from the bridge artifact API, assemble the **threaded** MIME reply (the Poller already has the original message's `Message-Id`/`References`), create the Gmail draft.
3. `POST /api/jobs/{id}/writeback-done`; advance the cursor.

**CLI:** `--poller` (run both loops), `--reauth`, `--check-token`.

**Tests:** intake (fetch→label→submit with message-id key); write-back (label + draft assembly + artifact fetch + cursor advance + writeback-done); token helpers.

### 4. Intake sources other than Gmail

- **Chrome extension** → posts `JD_SCRAPED` directly to the bridge. Unchanged.
- **JSearch** → keep as its own small submitter that posts `JD_SCRAPED` (no Gmail, no write-back). Could later fold into a general "intake" service; out of scope for Phase 1.

### 5. Migration / cutover (host, PM2)

1. Build the Processor (scan/scrape moved in, Gmail removed, gate added) — still reads the bridge. Deploy as `jd-processor` (PM2), retire the old `jd-worker`.
2. Build + deploy the Poller as `jd-poller` (PM2). It starts intake + write-back.
3. Retire the cron `--max-emails` ingestion (the Poller replaces it) and the `--email` path.
4. `make doctor` gains `jd-poller` / `jd-processor` checks; drop `jd-worker`.

---

## Risks / must-handle

- **Write-back idempotency.** The completed feed is at-least-once: if the Poller crashes after sending a draft but before `writeback-done`, the job re-appears. Labels are idempotent (safe to re-apply); **draft creation is not** (could create a duplicate draft). Mitigation: before creating a draft, check for an existing draft on that thread (Gmail `drafts.list` by thread), or accept rare duplicates (drafts are human-reviewed and deleted anyway). Pick one; recommend the existence-check.
- **Terminal-label decision** must move cleanly from `EmailLabelingService` (decision + application coupled today) — Processor decides the string, Poller applies it.
- **Digest children** must re-enqueue as `JD_SCRAPED` (never re-scanned) and carry **no** `message_id` (only the parent digest email gets a Gmail label).
- **`--reauth` must work from the Poller module** (token path relocates); verify the OAuth desktop flow still runs there.
- **Cross-process contention with other oMLX consumers** (nanobot / openclaw share `:11436`) is **not** solved by the in-process gate — it only serializes the Processor's own calls. Separate concern; note it, don't fix it here.

## Explicitly not in Phase 1

- Bounded job concurrency in the Processor (Phase 2 — flip `LOCAL` gate does the protecting).
- Containerizing Poller + Processor; `host.docker.internal` for MLX/Chrome (Phase 3).
- 2-lane priority (fast scan vs heavy tailor) / cloud-offload tuning (Phase 4, optional).
