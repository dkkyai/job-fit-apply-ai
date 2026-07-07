#!/usr/bin/env bash
#
# analyze_run.sh — run one pipeline batch, wait for the jd-worker to drain it, then
# have a local (or configured) Ollama model analyze the run and write findings as
# task files. Intended to replace the summary step of the cron pipeline.
#
# Usage:   analyze_run.sh [MAX_EMAILS]            (default 3)
# Config (env):
#   RUN_ANALYZER_MODEL   model for analysis (oMLX local) (default Qwen3.5-9B-OptiQ-4bit)
#   OLLAMA_BASE_URL      Ollama endpoint (:ollama-local) (default http://localhost:11434)
#   RUN_ANALYZER_QUIET   seconds of run_log silence = drained   (default 300)
#   RUN_ANALYZER_MAXWAIT max seconds to wait for drain          (default 1800)
#
set -uo pipefail

PROJECT_DIR="${PROJECT_DIR:-$HOME/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline}"
MODEL="${RUN_ANALYZER_MODEL:-Qwen3.5-9B-OptiQ-4bit}"
OLLAMA_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
QUIET="${RUN_ANALYZER_QUIET:-300}"
MAXWAIT="${RUN_ANALYZER_MAXWAIT:-1800}"
MAX_EMAILS="${1:-3}"

RUN_TS="$(date +%Y%m%d_%H%M%S)"
START_ISO="$(date -u +%Y-%m-%dT%H:%M:%S)"
RUN_LOG="$PROJECT_DIR/output/runs/run_log.jsonl"
ANALYZER_DIR="$PROJECT_DIR/tuner/run-analyzer"
FINDINGS_DIR="$ANALYZER_DIR/findings/$RUN_TS"
METRICS_FILE="$ANALYZER_DIR/last_metrics.json"
WORKER_ERR="${WORKER_ERR:-$HOME/.pm2/logs/jd-worker-error.log}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [analyze_run] $*"; }

# ── 1. Run the batch ─────────────────────────────────────────────────────────
cd "$PROJECT_DIR" || { log "project dir not found: $PROJECT_DIR"; exit 1; }
mkdir -p "$(dirname "$RUN_LOG")" "$FINDINGS_DIR"

# Make OLLAMA_API_KEY / OLLAMA_CLOUD_BASE_URL (cloud auth) and MLX_* (local oMLX)
# available so a cloud or local RUN_ANALYZER_MODEL routes correctly.
if [ -f "$PROJECT_DIR/.env" ]; then
    export OLLAMA_API_KEY="${OLLAMA_API_KEY:-$(grep -E '^OLLAMA_API_KEY=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export OLLAMA_CLOUD_BASE_URL="${OLLAMA_CLOUD_BASE_URL:-$(grep -E '^OLLAMA_CLOUD_BASE_URL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export MLX_LOCAL_BASE_URL="${MLX_LOCAL_BASE_URL:-$(grep -E '^MLX_LOCAL_BASE_URL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export MLX_API_KEY="${MLX_API_KEY:-$(grep -E '^MLX_API_KEY=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    MODEL="${RUN_ANALYZER_MODEL:-$(grep -E '^RUN_ANALYZER_MODEL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    MODEL="${MODEL:-Qwen3.5-9B-OptiQ-4bit}"
fi

log "running batch (--max-emails $MAX_EMAILS)"
BATCH_OUT="${BATCH_OUT:-/tmp/run_analyzer_batch_${RUN_TS}.out}"
./gradlew run --args="--max-emails $MAX_EMAILS" > "$BATCH_OUT" 2>&1
SUBMITTED=$(grep -oE '\[Batch done\] [0-9]+ job' "$BATCH_OUT" | grep -oE '[0-9]+' | head -1 || echo 0)
SUBMITTED=${SUBMITTED:-0}
log "batch submitted $SUBMITTED job(s) to the bridge"

# ── 2. Wait for the worker to drain (run_log quiet for $QUIET, capped at $MAXWAIT) ──
if [ "$SUBMITTED" -gt 0 ]; then
    log "waiting for worker to drain (quiet=${QUIET}s, max=${MAXWAIT}s)"
    INITIAL_LINES=$( [ -f "$RUN_LOG" ] && wc -l < "$RUN_LOG" || echo 0 )
    WAITED=0; LAST_LINES="$INITIAL_LINES"; QUIET_FOR=0
    while [ "$WAITED" -lt "$MAXWAIT" ]; do
        sleep 15; WAITED=$((WAITED+15))
        NOW_LINES=$( [ -f "$RUN_LOG" ] && wc -l < "$RUN_LOG" || echo 0 )
        NEW_RECORDS=$((NOW_LINES - INITIAL_LINES))
        if [ "$NOW_LINES" -gt "$LAST_LINES" ]; then
            LAST_LINES=$NOW_LINES; QUIET_FOR=0
            # Early exit once all submitted jobs have been recorded
            if [ "$NEW_RECORDS" -ge "$SUBMITTED" ]; then
                log "all $SUBMITTED job(s) recorded — drained"; break
            fi
        else
            QUIET_FOR=$((QUIET_FOR+15))
            [ "$QUIET_FOR" -ge "$QUIET" ] && { log "worker idle for ${QUIET}s — drained"; break; }
        fi
    done
    [ "$WAITED" -ge "$MAXWAIT" ] && log "WARN: hit max wait (${MAXWAIT}s) — analyzing what completed"
else
    log "no jobs submitted — analyzing for ingestion-side issues only"
fi

# ── 3. Assemble inputs, call the model, write findings (Python does the heavy lifting) ──
SKILL_FILE="$ANALYZER_DIR/RUN_ANALYZER_SKILL.md" \
PROMPT_FILE="$ANALYZER_DIR/PROMPT.md" \
RUN_LOG="$RUN_LOG" START_ISO="$START_ISO" RUN_TS="$RUN_TS" \
FINDINGS_DIR="$FINDINGS_DIR" METRICS_FILE="$METRICS_FILE" \
WORKER_ERR="$WORKER_ERR" BATCH_OUT="$BATCH_OUT" \
MODEL="$MODEL" OLLAMA_URL="$OLLAMA_URL" \
python3 "$ANALYZER_DIR/analyze.py"
RC=$?

log "analysis complete (findings in $FINDINGS_DIR)"
exit $RC
