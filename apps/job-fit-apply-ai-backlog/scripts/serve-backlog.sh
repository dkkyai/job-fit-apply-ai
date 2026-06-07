#!/bin/bash

set -euo pipefail

# Source .env file if it exists
if [ -f .env ]; then
    set -a
    source .env
    set +a
fi

# Configuration (override via .env or environment)
PROJECT_DIR="${BACKLOG_PROJECT_DIR:-$(pwd)}"
LOG_DIR="${BACKLOG_LOG_DIR:-./logs}"
PORT="${BACKLOG_PORT:-8081}"
SERVE_BIN="${BACKLOG_SERVE_BIN:-$(which serve)}"
TAILSCALE_BIN="${TAILSCALE_BIN:-/usr/local/bin/tailscale}"

# Ensure log directory exists
mkdir -p "$LOG_DIR"

# Define log files
OUT_LOG="$LOG_DIR/backlog.out.log"
ERR_LOG="$LOG_DIR/backlog.err.log"

# Function to log messages
log() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $1"
    echo "$msg" >> "$OUT_LOG"
}

log_error() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $1"
    echo "$msg" >> "$ERR_LOG"
    echo "$msg" >&2
}

cd "$PROJECT_DIR"

# Get Tailscale IP - exit if not available
log "Fetching Tailscale IP..."
TAILSCALE_IP=$("$TAILSCALE_BIN" ip -4 2>&1)
TAILSCALE_STATUS=$?

if [ $TAILSCALE_STATUS -ne 0 ]; then
    log_error "Failed to obtain Tailscale IP. Is Tailscale running?"
    log_error " tailscale ip -4 returned: $TAILSCALE_IP"
    exit 1
fi

# Validate Tailscale IP format (must be 100.x.x.x for MagicDNS)
if ! [[ "$TAILSCALE_IP" =~ ^100\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    log_error "Invalid Tailscale IP: $TAILSCALE_IP (expected 100.x.x.x format)"
    exit 1
fi

log "Tailscale IP validated: $TAILSCALE_IP"

# Rebuild the React app
log "Building job-backlog..."
if ! npm run build >> "$OUT_LOG" 2>> "$ERR_LOG"; then
    log_error "Build failed. Check $ERR_LOG for details."
    exit 1
fi

log "Build completed successfully"

# Start serve on Tailscale IP
log "Starting serve on $TAILSCALE_IP:$PORT..."

# Use exec to replace the shell process
exec "$SERVE_BIN" -s dist -l "tcp:${TAILSCALE_IP}:${PORT}" >> "$OUT_LOG" 2>> "$ERR_LOG"
