#!/usr/bin/env bash
#
# cdp-watchdog.sh — keep the dedicated warm CDP Chrome alive.
#
# Runs under the com.jd.chrome-cdp launchd agent with StartInterval=60, so it must be
# SHORT-LIVED: it polls the debug port and, only if the port is NOT answering, (re)launches
# Chrome and returns immediately. Chrome is started via LaunchServices (`open -na`) so it is
# NOT a child of this script / the launchd job and keeps running after we exit — the next
# tick just re-polls the port.
#
# Because it checks the PORT (not just "is a Chrome process alive?") it also recovers a hung
# Chrome whose process is up but whose CDP port is dead — it kills that stale instance first.
#
# Manual use:  scripts/cdp-watchdog.sh   (no-op if the port is already healthy)
# Config:      reads the same CHROME_* keys from ../.env as launch-chrome-cdp.sh
set -euo pipefail
export PATH="/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/../.env}"

# Read a single KEY=value from .env, preserving values that contain spaces. Env vars win.
read_env() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2- || true; }
say() { printf '%s [cdp-watchdog] %s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')" "$*"; }

PORT="${CHROME_DEBUG_PORT:-$(read_env CHROME_DEBUG_PORT)}";                  PORT="${PORT:-9222}"
CHROME="${CHROME_EXECUTABLE_PATH:-$(read_env CHROME_EXECUTABLE_PATH)}";      CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
CDP_DIR="${CHROME_CDP_USER_DATA_DIR:-$(read_env CHROME_CDP_USER_DATA_DIR)}"; CDP_DIR="${CDP_DIR:-$HOME/Library/Application Support/Google/Chrome-CDP}"
PROFILE="${CHROME_PROFILE_DIRECTORY:-$(read_env CHROME_PROFILE_DIRECTORY)}"; PROFILE="${PROFILE:-Default}"

# Derive the .app bundle from the inner binary so we can hand the launch to LaunchServices.
APP="${CHROME%/Contents/MacOS/*}"

cdp_up() { curl -sf -o /dev/null --max-time 3 "http://localhost:$PORT/json/version"; }

# 1) Healthy — nothing to do. This is the common path, so the watchdog stays quiet and cheap.
if cdp_up; then
  exit 0
fi

say "CDP port $PORT not answering — (re)launching the warm Chrome."

# 2) A Chrome may be alive but hung (process up, port dead) still holding the dedicated
#    user-data-dir lock. Kill ONLY that instance. Matching on the CDP dir is precise: it can
#    never hit your everyday Chrome, which uses a different --user-data-dir.
if pgrep -f "$CDP_DIR" >/dev/null 2>&1; then
  say "Terminating a stale/hung Chrome on $CDP_DIR."
  pkill -f "$CDP_DIR" || true
  sleep 2
  pkill -9 -f "$CDP_DIR" 2>/dev/null || true
  sleep 1
fi

# 3) Launch DETACHED. `open -na` starts a fresh instance through LaunchServices, independent of
#    this job's process group, so it survives after the watchdog (and launchd tick) exits.
if [ -d "$APP" ]; then
  open -na "$APP" --args \
    --remote-debugging-port="$PORT" \
    --user-data-dir="$CDP_DIR" \
    --profile-directory="$PROFILE" \
    --no-first-run \
    --no-default-browser-check
else
  # Fallback if the .app bundle can't be resolved: launch the binary detached.
  say "App bundle not found at '$APP' — launching the binary via nohup."
  nohup "$CHROME" \
    --remote-debugging-port="$PORT" \
    --user-data-dir="$CDP_DIR" \
    --profile-directory="$PROFILE" \
    --no-first-run \
    --no-default-browser-check >/dev/null 2>&1 &
fi

# 4) Confirm it came up (for the log). Don't fail the job if it's slow — the next tick retries.
for _ in $(seq 1 12); do
  if cdp_up; then
    say "CDP port $PORT is up."
    exit 0
  fi
  sleep 1
done
say "WARNING: CDP port $PORT still not answering after relaunch — cookies expired / manual login needed?"
exit 0
