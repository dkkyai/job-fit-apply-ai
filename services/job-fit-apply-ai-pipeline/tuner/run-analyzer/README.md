# run-analyzer

Analyzes the JD pipeline's **recently-completed jobs**, has a configurable model judge
pipeline health + scoring quality, and writes each problem as a self-contained task file.
Optionally (gated, opt-in) turns high-severity findings into a **living draft PR**.

There is **no batch to trigger**. The pipeline runs continuously — the `jd-poller` feeds
Gmail→bridge and the `jobfit-processor` container drains the bridge. A "run" is simply the
set of jobs that completed since the analyzer's **cursor**. The analyzer consumes the
bridge completed-event feed (`GET /api/jobs/completed`) with its own independent cursor.

## Design

The analyzer reasons over a **durable, structured window** rather than watching logs:

- **Source of truth = the bridge completed-event feed** (`services/job-fit-apply-ai-bridge`),
  consumed via an independent, persisted `completed_seq` cursor (mirrors the notifier's
  `Cursor`/`drainOnce` pattern; seeds at head on cold start to skip history; at-least-once).
  The feed is the **spine** — it defines which jobs are in the window.
- **`run_log.jsonl`** (`output/runs/`, written by the processor via `utils/RunReport.kt`)
  **enriches** each record with `jdTextLen` (the thin-JD signal), `durationMs`, `isDigest`,
  `board`, `source`. Joined on `jobId`. A bridge job with no run_log record is flagged
  `run_log_missing`.
- **Postgres `tracks`** + per-job output files (`score_fit.txt`, `job_description.txt`,
  `metadata.json`) feed the deeper **scoring audit** (judges whether scores are *justified by
  evidence*, not just whether they're zero).
- **`RUN_ANALYZER_SKILL.md`** — canonical instructions (what to look for, severity rubric,
  strict JSON output contract). **`PROMPT.md`** — entry point. **`analyze.py`** — orchestrator
  wiring the `analyzer/` package.

### Package layout

```
analyzer/
  cursor.py    persisted independent completed_seq cursor (mirror of Cursor.kt)
  bridge.py    HTTP client for GET /api/jobs/completed(+/head)
  sources.py   assemble the window: bridge spine + run_log enrichment
  metrics.py   deterministic aggregate + rate metrics
  history.py   append-only metrics history + rolling baseline        (Phase 2)
  llm.py       model routing (oMLX / ollama-cloud / ollama-local)
  findings.py  write analysis.json + per-finding task files + fingerprint
  audit.py     deep per-job scoring-correctness audit                (Phase 3)
  autofix.py   gated auto-fix -> living draft PR loop                (Phase 4)
  notify.py    Discord/Telegram messaging                            (Phase 4)
analyze.py     orchestrator (drain -> metrics -> model -> findings)
run_analyzer.sh driver (cursor consumer; single-instance lock; no batch/pm2)
```

## Usage

```bash
# analysis + trend + notify (cheap; run hourly on the schedule)
./tuner/run-analyzer/run_analyzer.sh

# gated auto-fix -> living draft PR -> notify (run daily; opt-in)
RUN_ANALYZER_AUTOFIX=1 ./tuner/run-analyzer/run_analyzer.sh --autofix

# one-off with a stronger cloud model
RUN_ANALYZER_MODEL=minimax-m3:ollama-cloud ./tuner/run-analyzer/run_analyzer.sh
```

Empty windows exit immediately (before any model call), so frequent scheduling is cheap.

Config (env; `run_analyzer.sh` also reads these from the project `.env`):

| var | default | purpose |
|---|---|---|
| `RUN_ANALYZER_MODEL` | `Qwen3.5-9B-OptiQ-4bit` (local oMLX) | analysis model |
| `JD_BRIDGE_URL` | `http://127.0.0.1:8765` | bridge base URL |
| `RUN_ANALYZER_AUTOFIX` | *(off)* | set `1` to arm the `--autofix` loop |
| `MLX_LOCAL_BASE_URL` / `MLX_API_KEY` | oMLX local | OpenAI-wire backend |
| `OLLAMA_CLOUD_BASE_URL` / `OLLAMA_API_KEY` | — | for `:ollama-cloud` models |
| `DISCORD_*` / `TELEGRAM_*` | — | notifications (no-op when blank) |

The metrics are computed deterministically in Python and are accurate regardless of model; a
**stronger model produces better root-cause + file-path accuracy** in the findings — local
models reliably triage but tend to guess at file paths. Set `RUN_ANALYZER_MODEL=<model>:ollama-cloud`
to route to Ollama Cloud (`Bearer $OLLAMA_API_KEY`).

## State (gitignored, under `state/`)

- `cursor` — last consumed `completed_seq`.
- `pending_since` — epoch of the oldest unanalyzed job (accumulate-until-N-or-T gate).
- `metrics_history.jsonl` — append-only per-run metrics (rolling baseline; Phase 2).
- `findings_ledger.jsonl` — per-fingerprint finding history for NEW/WORSENING/RESOLVED classification.
- `autofix_ledger.jsonl` — fingerprints handled by the autofix loop (`pr_open` → `pr_merged`); the
  analysis pass reads it to check whether a merged fix moved its target metric (auto-retiring resolved
  findings) and reconciles PR merges via `gh`.

Delete `state/cursor` to re-seed at head (skips history). Findings land in `findings/<run-ts>/`.

## Tests

Stdlib `unittest`, no third-party deps, hermetic (a fake bridge + stubbed model — no network,
LLM, or live services). Run from this directory:

```bash
python3 -m unittest discover -s tests -p 'test_*.py'
```

`tests/test_units.py` covers the pure logic (metrics, history/baseline, fingerprint, pending,
run_log join, audit grounding/triage); `tests/test_findings_ledger.py` the NEW/WORSENING/UNCHANGED
classifier; `tests/test_detectors.py` the deterministic detectors (per-board grouping, share-gating);
`tests/test_outcomes.py` the finding→fix→outcome loop (merge reconciliation, metric-recovery
resolved/regressed); `tests/test_integration.py` the cadence-gate decision table and the full
`analyze.py` flow (delta + ledger + history, cross-run dedup, deterministic-findings-survive-outage,
malformed-model degradation). Also run in CI
(`.github/workflows/ci.yml` → `run-analyzer`).

## External dependencies

Running `jd-bridge` (`:8765`), `jobfit-processor` + `jobfit-db` containers; the LLM endpoint
per `RUN_ANALYZER_MODEL`. The `--autofix` loop additionally needs host `git`, an authed `gh`
CLI, and the `claude` CLI.

## Scheduling

Two committed launchd plists (see `scripts/com.jd.run-analyzer*.plist`): the hourly analysis
job and the daily gated `--autofix` job. A shared single-instance lock
(`/tmp/jd-run-analyzer.lock`) guarantees they never overlap (the autofix git ops must not race
the analysis run). The findings task files can be reviewed directly, fed to a coding session
via each file's "Agent prompt" section, or applied by the `--autofix` loop.
