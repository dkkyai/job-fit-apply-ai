#!/usr/bin/env bash
#
# run_analyzer.sh — analyze the JD pipeline's recently-completed jobs and (optionally,
# gated) open a living draft PR with fixes. Replaces the old analyze_run.sh.
#
# There is NO batch to trigger any more: the poller feeds Gmail->bridge and the processor
# drains the bridge continuously. A "run" is the set of jobs completed since the analyzer's
# persisted cursor. The analyzer consumes the bridge completed-event feed with its own
# independent cursor (it never acks writeback — that is the poller's job).
#
# Usage:
#   run_analyzer.sh              analysis + trend + notify (cheap; hourly on the schedule)
#   run_analyzer.sh --autofix    gated auto-fix -> living draft PR -> notify (daily; opt-in)
#
# Config (env):
#   RUN_ANALYZER_MODEL     analysis model (oMLX local default Qwen3.5-9B-OptiQ-4bit)
#   JD_BRIDGE_URL          bridge base URL (default http://127.0.0.1:8765)
#   RUN_ANALYZER_AUTOFIX   set to 1 to arm the --autofix loop (default off)
#   PROJECT_DIR            pipeline checkout (auto-detected from this script's location)
#
set -uo pipefail

MODE="analyze"
[ "${1:-}" = "--autofix" ] && MODE="autofix"

# ── Resolve paths (worktree-friendly: derive from this script's location) ────────────
ANALYZER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$ANALYZER_DIR/../.." && pwd)}"
STATE_DIR="$ANALYZER_DIR/state"
mkdir -p "$STATE_DIR"

RUN_TS="$(date +%Y%m%d_%H%M%S)"
export RUN_TS
export RUN_LOG="$PROJECT_DIR/output/runs/run_log.jsonl"
export FINDINGS_DIR="$ANALYZER_DIR/findings/$RUN_TS"
export CURSOR_FILE="$STATE_DIR/cursor"
export METRICS_FILE="$STATE_DIR/last_metrics.json"
export HISTORY_FILE="$STATE_DIR/metrics_history.jsonl"
export LEDGER_FILE="$STATE_DIR/autofix_ledger.jsonl"
export SKILL_FILE="$ANALYZER_DIR/RUN_ANALYZER_SKILL.md"
export PROMPT_FILE="$ANALYZER_DIR/PROMPT.md"
export JD_BRIDGE_URL="${JD_BRIDGE_URL:-http://127.0.0.1:8765}"
export ANALYZER_DIR PROJECT_DIR

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [run_analyzer] $*"; }

# ── Single-instance lock (portable mkdir lock; macOS has no flock) ───────────────────
LOCKDIR="/tmp/jd-run-analyzer.lock"
if ! mkdir "$LOCKDIR" 2>/dev/null; then
    if [ -f "$LOCKDIR/pid" ] && kill -0 "$(cat "$LOCKDIR/pid" 2>/dev/null)" 2>/dev/null; then
        log "another run-analyzer is running (pid $(cat "$LOCKDIR/pid")) — exiting"
        exit 0
    fi
    rm -rf "$LOCKDIR"
    mkdir "$LOCKDIR" 2>/dev/null || { log "lock race — exiting"; exit 0; }
fi
echo $$ > "$LOCKDIR/pid"
trap 'rm -rf "$LOCKDIR"' EXIT

cd "$PROJECT_DIR" || { log "project dir not found: $PROJECT_DIR"; exit 1; }

# ── Make LLM-routing creds available (cloud auth + local oMLX) and pick the model ─────
MODEL="${RUN_ANALYZER_MODEL:-Qwen3.5-9B-OptiQ-4bit}"
if [ -f "$PROJECT_DIR/.env" ]; then
    export OLLAMA_API_KEY="${OLLAMA_API_KEY:-$(grep -E '^OLLAMA_API_KEY=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export OLLAMA_CLOUD_BASE_URL="${OLLAMA_CLOUD_BASE_URL:-$(grep -E '^OLLAMA_CLOUD_BASE_URL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export MLX_LOCAL_BASE_URL="${MLX_LOCAL_BASE_URL:-$(grep -E '^MLX_LOCAL_BASE_URL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export MLX_API_KEY="${MLX_API_KEY:-$(grep -E '^MLX_API_KEY=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    # Notification creds (Discord/Telegram) — no-op downstream when blank.
    export DISCORD_BOT_TOKEN="${DISCORD_BOT_TOKEN:-$(grep -E '^DISCORD_BOT_TOKEN=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export DISCORD_CHANNEL_ID="${DISCORD_CHANNEL_ID:-$(grep -E '^DISCORD_CHANNEL_ID=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-$(grep -E '^TELEGRAM_BOT_TOKEN=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    export TELEGRAM_CHAT_ID="${TELEGRAM_CHAT_ID:-$(grep -E '^TELEGRAM_CHAT_ID=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    MODEL="${RUN_ANALYZER_MODEL:-$(grep -E '^RUN_ANALYZER_MODEL=' "$PROJECT_DIR/.env" | cut -d= -f2-)}"
    MODEL="${MODEL:-Qwen3.5-9B-OptiQ-4bit}"
fi
export MODEL

# ── Capture the processor log tail for root-causing (pm2 is retired — this is Compose) ─
export PROCESSOR_LOG_TAIL="$(docker logs --tail 200 jobfit-processor 2>&1 || true)"

# ── Dispatch ─────────────────────────────────────────────────────────────────────────
cd "$ANALYZER_DIR" || { log "analyzer dir not found: $ANALYZER_DIR"; exit 1; }
if [ "$MODE" = "autofix" ]; then
    log "autofix mode (model=$MODEL)"
    python3 -m analyzer.autofix
else
    log "analysis mode (model=$MODEL)"
    python3 analyze.py
fi
RC=$?

log "done (mode=$MODE, findings in $FINDINGS_DIR)"
exit $RC
