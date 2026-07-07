#!/bin/sh
# Container entrypoint: run the one-shot JVM (--once) on a coarse tick, then sleep. The JVM
# self-gates on the persisted last-run, so most ticks are cheap no-ops and the JVM's ~200 MB only
# exists for the few seconds it actually fetches. Steady state is this sleeping shell (~few MB).
HEARTBEAT_FILE="${HEARTBEAT_FILE:-/tmp/jsearch-heartbeat}"
CHECK_INTERVAL_S="${JSEARCH_CHECK_INTERVAL_S:-3600}"

echo "[jsearch] shell loop starting — checking every ${CHECK_INTERVAL_S}s; JVM self-gates on last-run"
while true; do
  /app/bin/job-fit-apply-ai-jsearch --once || echo "[jsearch] --once exited non-zero (continuing)"
  touch "$HEARTBEAT_FILE"                     # liveness for the healthcheck (this loop is alive)
  sleep "$CHECK_INTERVAL_S"
done
