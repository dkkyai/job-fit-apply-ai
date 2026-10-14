#!/bin/sh
# Container entrypoint: run the one-shot JVM (--once) on a coarse tick, then sleep. The JVM
# self-gates on the persisted last-run, so most ticks are cheap no-ops and the JVM only exists for
# the minutes it actually polls employer feeds. Steady state is this sleeping shell (~few MB).
HEARTBEAT_FILE="${HEARTBEAT_FILE:-/tmp/watchlist-heartbeat}"
CHECK_INTERVAL_S="${WATCHLIST_CHECK_INTERVAL_S:-3600}"
WATCHLIST_BIN="${WATCHLIST_BIN:-/app/bin/job-fit-apply-ai-watchlist}"   # overridable for the entrypoint test

echo "[watchlist] shell loop starting — checking every ${CHECK_INTERVAL_S}s; JVM self-gates on last-run"
# A full sweep takes ~10–15 min (Workday detail fetches), longer than the healthcheck's start
# period, so mark the loop alive before the first sweep instead of only after it.
touch "$HEARTBEAT_FILE"
while true; do
  "$WATCHLIST_BIN" --once || echo "[watchlist] --once exited non-zero (continuing)"
  touch "$HEARTBEAT_FILE"                     # liveness for the healthcheck (this loop is alive)
  sleep "$CHECK_INTERVAL_S"
done
