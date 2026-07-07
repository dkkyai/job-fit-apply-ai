# run-analyzer

Runs a pipeline batch, waits for the jd-worker to drain it, then has a configurable
Ollama model analyze the run and write findings as task files. Replaces the summary
step of the cron pipeline with LLM-driven analysis.

## Design

Instead of the LLM watching stdout/stderr, the worker emits a **durable structured
run report** and the analyzer reasons over that (drilling into logs/output only for
flagged jobs). This keeps analysis bounded, reproducible, and diffable across runs.

- **`RunReport`** (`src/main/kotlin/.../utils/RunReport.kt`) — the worker appends one
  JSON line per processed job to `output/runs/run_log.jsonl` (gitignored). Fields
  include `score`, `action`, `error`, `jdTextLen` (the thin-JD signal), `board`,
  `isDigest`, `durationMs`.
- **`RUN_ANALYZER_SKILL.md`** — canonical instructions: what to look for (scoring
  quality, digest thin-JDs, timeouts, OOM, blocked scrapes, per-board patterns,
  regressions vs the prior run), the severity rubric, and the **strict JSON output
  contract**.
- **`PROMPT.md`** — entry point (works both for the cron script and for an interactive
  coding session).
- **`analyze.py`** — slices the run window from `run_log.jsonl`, computes metrics
  deterministically, calls the model (`format=json`), and writes each finding to
  `findings/<run-ts>/NN-slug.md` as a self-contained task plus `analysis.json`.
  Saves this run's metrics to `last_metrics.json` for next-run regression comparison.
- **`analyze_run.sh`** — orchestration: run batch → wait for drain → run `analyze.py`.

## Usage

```bash
# one-off
RUN_ANALYZER_MODEL=qwen3:14b ./tuner/run-analyzer/analyze_run.sh 5
```

Config (env): `RUN_ANALYZER_MODEL` (default `qwen3:14b`), `OLLAMA_BASE_URL`,
`RUN_ANALYZER_QUIET` (drain-detect silence, default 90s), `RUN_ANALYZER_MAXWAIT`
(default 1800s). The metrics are computed deterministically in Python and are accurate
regardless of model; a **stronger model produces better root-cause + file-path accuracy**
in the findings — local models reliably triage but tend to guess at file paths.

To analyze with **Ollama Cloud**, set `RUN_ANALYZER_MODEL=<model>:ollama-cloud` (e.g.
`minimax-m3:ollama-cloud`). `analyze.py` routes `:ollama-cloud` models to
`OLLAMA_CLOUD_BASE_URL` with a `Bearer $OLLAMA_API_KEY` header; `analyze_run.sh` reads
both from the project `.env` automatically.

## Wiring into cron

`~/.local/scripts/run_jd_pipeline.sh` calls `analyze_run.sh`, keeping the existing
`heartbeat_check.sh` guard (already-running / screen-locked / GPU-busy) and the timeout
watcher. Discord/Telegram notifications are owned by the Kotlin pipeline
(`BatchCommandHandler` + `WorkerCommandHandler` via `BatchNotificationService`); the old
`pipeline_complete.sh` notifier is retired, and the timeout alert is sent by
`./gradlew run --args="--notify-timeout <minutes>"`. The findings task files can then be reviewed (or fed to a coding
session via each file's "Agent prompt" section).

Note: the jd-worker must be running (pm2) — it processes the jobs the batch submits and
writes the run records.
