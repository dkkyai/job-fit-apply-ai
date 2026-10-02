# JobBot

JobBot is the Telegram agent behind **@DkkyAIJobBot**. It handles the buttons on JFAA's high-fit cards and answers when you reply to a card. It is a [Hermes](https://hermes-agent.nousresearch.com) gateway (`docker/jobbot`) plus a small Kotlin service, `jobbot-mcp` (`services/job-fit-apply-ai-jobbot-mcp`), which is its only door into JFAA.

```
Processor ──► Bridge (completed feed: completed_seq, job_url, message_id, is_recruiter, terminal_label…)
                │ GET
                ├──────► Notifier ──sendMessage only──► Telegram @DkkyAIJobBot
                │          card ends with  #J<seq>              │ ▲
                │          buttons carry   <verb>:<seq>          │ │ answers, card edits, replies
                │                                               ▼ │
                │                                  getUpdates (the ONLY reader)
                │                                     jobbot  (Hermes, network `jobbot` only)
                │                                      ├─ jobbot_actions plugin: taps → /plugin/tap
                │                                      └─ agent: read-only MCP tools (mcp__jfaa__*)
                │                                               │ Bearer JOBBOT_MCP_TOKEN
                └──── GET (route allowlist) ───────── jobbot-mcp  (networks default + jobbot)
                      POST only tracks/{id}/events|status   ├─ pipeline-output (ro), resume/profile YAML (ro)
                                                       ├─ poller-secrets (ro): Gmail token, refreshed in memory
                                                       └─ /state/actions.db (taps, undo records, errors)
```

## The rules it is built on

- **One bot, split by direction.** The notifier only calls `sendMessage`; its Telegram client cannot build any other method. The `jobbot` container is the token's **only** `getUpdates` reader. Telegram allows one, and a second reader gets HTTP 409. `make doctor` fails while the retired `pm2 nanobot-jobbot` is online.
- **Alerts never depend on JobBot.** The notifier works with `jobbot` stopped. Without JobBot, cards just have no working action buttons, so leave `NOTIFIER_TELEGRAM_ACTIONS` blank whenever JobBot is down for long.
- **Buttons are code, not prompts.** A tap is decided by `jobbot-mcp`'s `TapService`, which is deterministic Kotlin. The model never sees a tap unless a verb hands work to it (Reply, later).
- **The model is (almost) read-only.** Its tools are `get_job`, `list_high_fit`, `read_job_file`, `list_tracks`, `get_profile`, `get_track_timeline` and `get_job_email`. Its only writes are `add_track_note` and `set_track_status`, which append to a job's history in the bridge's `track_events` as `source=jobbot`, and only on a track matched by `track_id` or `artifact_url`, never a fuzzy match. Hermes's terminal, file, browser, code-execution, delegation, cron and skill-editing toolsets are disabled. Config, persona and plugins are re-seeded from the image on every start (`docker/jobbot/seed.py`).
- **Untrusted content is data.** Job postings, reports quoting them, and emails never become instructions. File text from `read_job_file` is prefixed with a notice saying so.

## Card buttons

The notifier sends a verb only when it is listed in `NOTIFIER_TELEGRAM_ACTIONS` **and** the event qualifies. `jobbot-mcp` re-checks the same rules on every tap. Both sides test against `docker/jobbot/contract/button_eligibility.json`.

| Verb | Shown when | Tap does |
|---|---|---|
| `apply` | the job has a `job_url` | **Phase 1:** replies "Not implemented yet" |
| `reply` | `is_recruiter` and a source `message_id` | posts the reply draft (the poller's, or one built from JFAA's `draft_text`, with the resume attached) under **✅ Send / ✖ Cancel**. If neither exists, the agent writes one |
| `archive` | a source `message_id` the poller left in the inbox | removes `INBOX` from that email only; the button becomes **↩ Undo archive** (`undo:<seq>`), which restores exactly the labels it removed. Mail already out of the inbox, archived by Muse for example, gets "Already out of the inbox." |

`callback_data` is `<verb>:<completed_seq>`. The seq is the bridge's id for the completion, so nothing is registered before the send.

**Every tap is checked in this order:**
1. The verb is known.
2. The user is in `JOBBOT_TELEGRAM_ALLOWED_USERS`. Blank means nobody.
3. The card is at most 7 days old (`JOBBOT_CARD_TTL_DAYS`).
4. The verb is in `JOBBOT_ACTIONS`.
5. The seq resolves on the bridge.
6. The event qualifies for the verb.

Then the verb's handler runs. With `JOBBOT_DRY_RUN=true`, it stops after the checks, logs the tap, and toasts `[dry run] Would …`.

`/jdstatus` shows the mode, the handled verbs, the latest job, pending actions, recent actions and recent errors. Inbox sweeps are not JobBot's: Muse still does them.

## Sending replies: the ✅ Send tap is the only way

The model has **no send tool**.
- **Model tools:** it can `write_reply_draft`, which only ever saves a Gmail draft, and `request_send_approval`.
- **Preview:** `request_send_approval` stores a pending approval with a SHA-256 fingerprint of the draft (recipients, subject, text, attachment names). The plugin's `post_tool_call` hook then posts that exact preview with `send:<id>` / `cancel:<id>` buttons.
- **Checks at Send.** A tap on ✅ Send is checked again in `jobbot-mcp`:
  - the allowlist
  - the preview's age
  - `JOBBOT_SEND_ENABLED` (off by default)
  - `JOBBOT_MAX_SENDS_PER_DAY`
  - the draft still matches the fingerprint
  - every recipient is already in the thread
  - Richard hasn't replied in the thread since, for example via Muse

  Then `drafts.send`. A second tap answers "Already sent."
- **Why not Hermes's approval gate?** Hermes's `pre_tool_call` → `approve` gate is skipped by the gateway's `/yolo` toggle, and no config locks `/yolo`. A deterministic tap is immune to both the model and `/yolo`.

## Gmail (Archive / Undo / get_job_email)

`jobbot-mcp` uses **the poller's token**: the same dkkytech@ account and scopes, since neither is useful without the other.
- **Mount:** `poller-secrets` is mounted read-only as a directory. A poller re-auth recreates the token file, and the new file is picked up without a restart.
- **Never written:** the token file is never written, deleted or re-authorized here. Access tokens stay in memory.
- **Dead token:** `invalid_grant` puts `/jdstatus` on `Gmail: NEEDS RE-AUTH`. The fix is the poller's own `docker compose run --rm poller --reauth`.
- **Production app:** the OAuth app is in Production, so tokens don't expire weekly.
- **What it touches:** Archive and Undo touch only the `INBOX` label. `TRASH` and `SPAM` are refused in code.

## Configuration (root `.env`)

| Variable | Meaning |
|---|---|
| `COMPOSE_PROFILES` | Must include `jobbot` (`intake,jobbot`). |
| `JOBBOT_TELEGRAM_BOT_TOKEN` | The same value as `NOTIFIER_TELEGRAM_BOT_TOKEN`. |
| `JOBBOT_TELEGRAM_ALLOWED_USERS` | Your Telegram user id. |
| `JOBBOT_TELEGRAM_HOME_CHANNEL` | Your chat id. |
| `JOBBOT_MCP_TOKEN` | Shared secret between `jobbot` and `jobbot-mcp` (`openssl rand -hex 32`). |
| `JOBBOT_MODEL` | Chosen by the env-llm-tuner skill. Default `deepseek-v4.1-flash:cloud`. Ollama Cloud ids end in `:cloud`; the bare name 404s. The fallback is oMLX `Qwen3.6-35B-A3B-OptiQ-4bit`. |
| `JOBBOT_ACTIONS` | Verbs JobBot acts on. `NOTIFIER_TELEGRAM_ACTIONS` must be a subset. |
| `JOBBOT_DRY_RUN` | `true` validates and logs taps but does nothing. |
| `JOBBOT_SEND_ENABLED` | Kill switch for ✅ Send (default `false`). |
| `JOBBOT_MAX_SENDS_PER_DAY` | Daily cap on sends (default 10). |
| `NOTIFIER_TELEGRAM_ACTIONS` | Verbs the cards show. |

## Runbook

**Deploy.** Use a release worktree at the merge commit, with the three personal-file symlinks, as for every service. Run from that worktree:

```bash
docker compose --env-file ~/projects/job-fit-apply-ai/.env --profile jobbot up -d --build --no-deps jobbot-mcp jobbot
```

**Check.**
- `make doctor` has a JobBot section.
- `docker logs jobfit-jobbot` should show `[jobbot-seed] wrote config.yaml`, `Wired native handlers from plugin 'jobbot_actions'`, `MCP server 'jfaa' (HTTP): registered 5 tool(s)`, and `Connected to Telegram (polling mode)`.
- Gateway logs live in `${JFAA_DATA_ROOT}/jobbot/logs/`.

**One-shot agent test (no Telegram):**

```bash
docker exec jobfit-jobbot hermes chat -q "Look up JFAA job #J7650 and say why it fits."
```

**Rollback to the old reply bot:**

```bash
docker compose --env-file ~/projects/job-fit-apply-ai/.env --profile jobbot stop jobbot
```

```bash
pm2 start nanobot-jobbot
```

Then set `NOTIFIER_TELEGRAM_ACTIONS=` (blank) and recreate the notifier, so cards stop showing buttons nothing handles.

## Tests

| Level | Where |
|---|---|
| Notifier unit + contract | `services/job-fit-apply-ai-notifier`: `./gradlew test` |
| jobbot-mcp unit, fake-bridge HTTP, real MCP client over HTTP | `services/job-fit-apply-ai-jobbot-mcp`: `./gradlew test` |
| Hermes plugin (stubbed Telegram/Hermes) + seed | `docker/jobbot`: `python -m pytest tests` |
| Compose isolation (networks, mounts, ports, profile) | `make compose-data-root-test` |
| Buttons at the wire (Bridge → Processor → Notifier) | `make e2e` |

## Known gaps

- **Memory is Hermes's built-in** (`MEMORY.md` / `USER.md`). The planned separate Hindsight bank needs a provider that isn't in the `v2026.9.24` image.
- **Workflows are in the persona, not skills.** They live in `docker/jobbot/skills/*.md` and are appended to the persona at boot. Hermes's skills toolset is disabled, because it would let the agent write its own skills.
