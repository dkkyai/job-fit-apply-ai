#!/usr/bin/env bash
#
# Configure Tailscale Serve for the tailnet-exposed containers.
# See docs/tailscale-serve.md. Idempotent — safe to re-run.
#
# Each container binds 127.0.0.1:<port> (compose); the host's already-signed-in
# tailscaled proxies the tailnet to it. No Tailscale runs inside Docker.
#
set -euo pipefail

# Resolve the tailscale CLI (override with TAILSCALE_BIN).
TS="${TAILSCALE_BIN:-}"
if [ -z "$TS" ]; then
  if command -v tailscale >/dev/null 2>&1; then TS="tailscale"
  elif [ -x /usr/local/bin/tailscale ]; then TS="/usr/local/bin/tailscale"
  else echo "ERROR: tailscale CLI not found (set TAILSCALE_BIN)"; exit 1; fi
fi

# One line per tailnet-exposed service: "<tailnet-port>|<local-target>|<label>".
SERVICES="
8081|http://127.0.0.1:8081|markserv
8765|http://127.0.0.1:8765|bridge
3030|http://127.0.0.1:3030|frontend
"

# TCP connect check on the host loopback (bash /dev/tcp).
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&-; return 0; } || return 1; }

echo "$SERVICES" | while IFS='|' read -r port target label; do
  [ -z "${port:-}" ] && continue
  if port_open "$port"; then
    echo "→ serve $label: http://<tailnet-name>:$port  →  $target"
    "$TS" serve --bg --http="$port" "$target"
  else
    echo "⚠ skip $label: nothing listening on 127.0.0.1:$port (start its container first)"
  fi
done

echo
echo "Current Tailscale Serve config:"
"$TS" serve status
