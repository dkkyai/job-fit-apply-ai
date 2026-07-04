#!/usr/bin/env bash
#
# launch-chrome-cdp.sh — start a DEDICATED Chrome instance with a remote-debugging port so the
# pipeline can connect to it over CDP (CHROME_CDP_ENDPOINT) and reuse a warm, logged-in session
# instead of copying the profile + launching a throwaway browser per job.
#
# IMPORTANT: current Chrome refuses --remote-debugging-port on the DEFAULT profile dir
# ("DevTools remote debugging requires a non-default data directory"). So this uses a dedicated
# user-data-dir (CHROME_CDP_USER_DATA_DIR), which can run ALONGSIDE your everyday Chrome.
# Sign into the job boards once in this dedicated window — the login persists in that dir.
#
# Usage:   scripts/launch-chrome-cdp.sh
# Verify:  curl -s http://localhost:9222/json/version
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/../.env}"

# Read a single KEY=value from .env, preserving values that contain spaces. Env vars win.
read_env() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2- || true; }

PORT="${CHROME_DEBUG_PORT:-$(read_env CHROME_DEBUG_PORT)}";                  PORT="${PORT:-9222}"
CHROME="${CHROME_EXECUTABLE_PATH:-$(read_env CHROME_EXECUTABLE_PATH)}";      CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
CDP_DIR="${CHROME_CDP_USER_DATA_DIR:-$(read_env CHROME_CDP_USER_DATA_DIR)}"; CDP_DIR="${CDP_DIR:-$HOME/Library/Application Support/Google/Chrome-CDP}"
PROFILE="${CHROME_PROFILE_DIRECTORY:-$(read_env CHROME_PROFILE_DIRECTORY)}"; PROFILE="${PROFILE:-Default}"

# Idempotent: if the CDP port already answers, there is nothing to do.
if curl -s -o /dev/null --max-time 2 "http://localhost:$PORT/json/version"; then
  echo "[launch-chrome-cdp] Chrome already exposing CDP on port $PORT — nothing to do."
  exit 0
fi

mkdir -p "$CDP_DIR"
echo "[launch-chrome-cdp] Launching dedicated debug Chrome on CDP port $PORT"
echo "[launch-chrome-cdp]   user-data-dir: $CDP_DIR  (separate from your everyday profile)"
echo "[launch-chrome-cdp] First run? Sign into LinkedIn etc. in this window — the login persists here."
echo "[launch-chrome-cdp] Verify with: curl -s http://localhost:$PORT/json/version"

# A dedicated user-data-dir is required: Chrome will NOT expose the debug port on the Default dir.
exec "$CHROME" \
  --remote-debugging-port="$PORT" \
  --user-data-dir="$CDP_DIR" \
  --profile-directory="$PROFILE" \
  --no-first-run \
  --no-default-browser-check
