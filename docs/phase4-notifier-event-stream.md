# Phase 4 — multi-consumer event stream + Notifier service

> **Status: DEPLOYED (2026-07-05).** Bridge completed-feed is now a multi-consumer event stream
> (`?all=true` + `completed_seq` cursor + `/head`); the widened event carries company/role/fit/action/
> urls (artifact_url now persisted). `jobfit-notifier` runs as a Compose service (cursor seeded at
> head on cold start → no history spam), sending Discord/Telegram from the stream. Messaging removed
> from the Processor (`BatchNotificationService` deleted; `NotificationClient` kept for `AlertService`).
> All suites green; contract-tested against a real bridge. Rollback dist: `build/install/…pre-phase4`.

**Goal:** move messaging (Discord per-job + Telegram high-fit) out of the Processor into a
containerized **Notifier** service, and — because more consumers are foreseen (analytics, webhooks,
mobile push) — do it by evolving the bridge's completed feed into a **generic multi-consumer event
stream**. The Notifier is its first consumer.

**Motivation (from discussion):** architectural tidiness + get messaging off the host Processor into
a container + foreseeing more consumers of completed jobs. Reliability is a bonus, not the driver.

## Design

### Bridge — evolve the completed feed into a multi-consumer stream
The feed already has a monotonic `completed_seq` per terminal job (the event log). Changes:

1. **`?all=true` (include-written-back) mode** on `GET /api/jobs/completed`. Today it filters
   `writeback_done = false` (the Poller's ack/work-queue). With `all=true`, return completed jobs by
   `completed_seq > since` **regardless** of `writeback_done`, so a cursor consumer sees every event.
   The Poller keeps its existing (default) behavior; consumers coexist without conflict.

2. **Widen the feed item** with the fields consumers want. `CompletedJob` today has
   `terminal_label / draft_text / message_id / is_recruiter / artifacts / error / status`. Add:
   `company`, `role_title`, `fit_score`, `pipeline_action`, `job_url`, `artifact_url` (the markserv
   report URL). Sources: `fit_score`/`pipeline_action`/`error`/`job_url` are columns; `company`/
   `role_title` parse out of `jd_json`; **`artifact_url` must be newly persisted** (see #3). Additive
   → backward-compatible (the Poller ignores unknown fields).

3. **Persist `artifact_url`.** The Processor already sends it in `ProcessingResult`, but the bridge's
   `ResultRequest` has no such field, so it's silently dropped. Add `artifact_url` to `ResultRequest`
   + an `artifact_url` column + set it in `recordResult`. (Small, self-contained.)

*(Cursor model: **client-side** — each consumer owns its cursor, like JSearch's `last_run`. The
bridge stays stateless about consumers. If many consumers later warrant central progress tracking,
upgrade to server-side consumer-groups then — the Notifier wouldn't change, only its cursor source.)*

### Notifier service `services/job-fit-apply-ai-notifier` (`com.jd.notifier`)
A **long-running poll loop** (like the Poller, not one-shot like JSearch — it's near-real-time, so a
15–30s loop; one-shot would cold-start thousands of times/day):
- `NotifierBridgeClient.fetchEvents(since, limit)` → `GET /api/jobs/completed?since=<cursor>&all=true`.
- `NotificationClient` (moved from the pipeline): Discord (bot token + channel) + Telegram (bot token
  + chat), Apache HttpClient. Config: `DISCORD_BOT_TOKEN/DISCORD_CHANNEL_ID/TELEGRAM_BOT_TOKEN/
  TELEGRAM_CHAT_ID`.
- `MessageFormatter` (moved from `BatchNotificationService.notifyJobResult` + label helpers): per-job
  Discord line (`• company — [title](report) — **score** (action)`, or an error line); Telegram
  high-fit ping when `fit_score >= FIT_THRESHOLD`.
- `Cursor` — persisted max `completed_seq` sent, in a small volume (like JSearch's state). Advance
  after each event sent.
- `NotifierLoop`: fetch events > cursor → for each, format + send → advance cursor. **At-least-once**
  (a crash mid-batch re-sends a few messages on restart — harmless for notifications).
- `cli/Main`: `--poll` (loop), `--once` (drain once, for tests/manual), `--health` (heartbeat).
- Dockerfile (lean JRE), compose service `notifier` (reaches `http://bridge:8765`, cursor volume,
  creds via env, heartbeat healthcheck), doctor check.

### Processor — drop messaging
Remove `notifyJobResult` from `ProcessorCommandHandler` and delete `BatchNotificationService` /
`NotificationClient` / `ScoredJob` from the pipeline (they move to the Notifier). The Processor just
posts results. `logConfigStatus` (notification config banner) moves to the Notifier's startup.

## Task breakdown

1. **Bridge:** `?all=true` on the completed feed; persist `artifact_url` (ResultRequest + column +
   recordResult); widen `CompletedJob` (company/role_title/fit_score/pipeline_action/job_url/
   artifact_url, sourcing company/role from jd_json). Bridge unit + write-back-feed tests.
2. **Scaffold** `job-fit-apply-ai-notifier` (module, settings include).
3. **Port** `NotificationClient` + the `notifyJobResult`/label formatting → `MessageFormatter`;
   `NotifierBridgeClient`; `Cursor` (persisted); `NotifierLoop`; config; `cli/Main`.
4. **Tests:** unit (message formatting: error vs scored vs high-fit-threshold; cursor advance;
   loop sends + advances against an in-process fake bridge; Discord/Telegram gated by config);
   integration (real-bridge contract: post a result → event appears with the new fields → notifier
   fetches it). Reuse the disposable-bridge harness.
5. **Dockerfile** + `.dockerignore` + image smoke test (`--health`, `--once` against a disposable
   bridge with a seeded completed job).
6. **Compose** `notifier` service (cursor volume, creds env, healthcheck) + doctor check.
7. **Cutover:** deploy notifier → verify it sends on a new completed job → remove messaging from the
   Processor (delete `notifyJobResult` call + the notification classes) → rebuild + `installDist`
   the pipeline. (Brief window where both could send is avoided by removing from the Processor only
   after the notifier is confirmed working; accept at most a couple of duplicate messages.)
8. **Docs:** README process table + this doc marked deployed.

## Risks / open questions

- **Duplicate messages during cutover** — if the notifier is live before the Processor stops sending,
  a job gets notified twice briefly. Harmless; sequence to minimize.
- **At-least-once dupes** on notifier crash/restart — acceptable for messaging; flag for future
  consumers where dupes matter (they'd need idempotency or server-side ack).
- **Cursor cold-start** — a fresh notifier with no cursor should start at the *current* max
  `completed_seq` (not 0), else it re-sends the entire history on first boot. Seed cursor = latest
  seq on first run.
- **Creds** — Discord/Telegram tokens move to the notifier's env (gitignored), out of the Processor.
- **Config secret handling** — same pattern as `JSEARCH_API_KEY`: gitignored env, never committed.
