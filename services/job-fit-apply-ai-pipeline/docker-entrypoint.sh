#!/bin/sh
# Processor container entrypoint.
#
# Chrome DevTools rejects HTTP requests whose Host header is not an IP address or
# `localhost`, and Playwright's connectOverCDP starts with `GET /json/version`. The
# compose file points CHROME_CDP_ENDPOINT at host.docker.internal (a socat shim on the
# Mac host forwards to the loopback-bound CDP Chrome), so resolve the hostname to its
# IP before the JVM starts. MLX/Ollama are ordinary HTTP servers — the hostname form is
# fine for them.
#
# Note: the IP is resolved once per container start. If Docker Desktop's gateway IP ever
# changes (e.g. after a Docker restart), restart this container.
set -eu

case "${CHROME_CDP_ENDPOINT:-}" in
  *host.docker.internal*)
    IP="$(getent hosts host.docker.internal | awk '{print $1; exit}')" || IP=""
    if [ -n "$IP" ]; then
      CHROME_CDP_ENDPOINT="$(printf '%s' "$CHROME_CDP_ENDPOINT" | sed "s/host\.docker\.internal/$IP/")"
      export CHROME_CDP_ENDPOINT
      echo "[entrypoint] CHROME_CDP_ENDPOINT -> $CHROME_CDP_ENDPOINT"
    else
      echo "[entrypoint] WARN: host.docker.internal unresolvable — CDP scraping will be unavailable" >&2
    fi
    ;;
esac

exec /app/bin/job-fit-apply-ai-pipeline "$@"
