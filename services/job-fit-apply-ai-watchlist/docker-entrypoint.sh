#!/bin/sh
# Container entrypoint: run the one-shot JVM (--once) on a coarse tick, then sleep. The JVM
# self-gates on the persisted last-run, so most ticks are cheap no-ops and the JVM only exists for
# the minutes it actually polls employer feeds. Steady state is this sleeping shell (~few MB).
HEARTBEAT_FILE="${HEARTBEAT_FILE:-/tmp/watchlist-heartbeat}"
CHECK_INTERVAL_S="${WATCHLIST_CHECK_INTERVAL_S:-3600}"

echo "[watchlist] shell loop starting — checking every ${CHECK_INTERVAL_S}s; JVM self-gates on last-run"
while true; do
  /app/bin/job-fit-apply-ai-watchlist --once || echo "[watchlist] --once exited non-zero (continuing)"
  touch "$HEARTBEAT_FILE"                     # liveness for the healthcheck (this loop is alive)
  sleep "$CHECK_INTERVAL_S"
done
