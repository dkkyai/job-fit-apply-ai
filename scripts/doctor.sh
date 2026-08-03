#!/usr/bin/env bash
#
# Preflight / health check for the Dockerized stack. Read-only — changes nothing.
# Exits 0 when all CRITICAL checks pass (warnings allowed), 1 otherwise.
#
# Covers what a setup script cannot automate (secrets, OAuth, model downloads,
# host daemons) by *checking* it and telling you what's missing.
#
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Match Docker Compose's repo-root configuration so path diagnostics cover both the portable
# JFAA_DATA_ROOT layout and legacy per-service overrides. This file is user-owned/gitignored.
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi
# shellcheck disable=SC1091
. "$ROOT/scripts/jfaa-data-root.sh"

FAILS=0
WARNS=0
ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
warn() { printf "  \033[33m!\033[0m %s\n" "$1"; WARNS=$((WARNS + 1)); }
bad()  { printf "  \033[31m✗\033[0m %s\n" "$1"; FAILS=$((FAILS + 1)); }
hdr()  { printf "\n\033[1m%s\033[0m\n" "$1"; }

running()   { docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$1"; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&-; return 0; } || return 1; }

hdr "Docker"
if docker info >/dev/null 2>&1; then ok "Docker daemon running"; else bad "Docker daemon NOT running (start Docker Desktop)"; fi

hdr "Compose services"
if running jobfit-db; then
  if docker exec jobfit-db pg_isready -U "${POSTGRES_USER:-jobfit}" -d "${POSTGRES_DB:-jobfit}" >/dev/null 2>&1; then
    ok "db (jobfit-db) up & accepting connections"
  else bad "db container up but not accepting connections"; fi
else bad "db (jobfit-db) not running  →  make up"; fi
if running jobfit-bridge; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' jobfit-bridge 2>/dev/null)" = "healthy" ]; then
    ok "bridge (jobfit-bridge) healthy"
  else warn "bridge container up but not healthy yet"; fi
else bad "bridge (jobfit-bridge) not running  →  make up"; fi
if running jobfit-markserv; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' jobfit-markserv 2>/dev/null)" = "healthy" ]; then
    ok "markserv (jobfit-markserv) healthy"
  else warn "markserv container up but not healthy yet"; fi
else warn "markserv not running  →  make up"; fi
if running jobfit-frontend; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' jobfit-frontend 2>/dev/null)" = "healthy" ]; then
    ok "frontend (jobfit-frontend) healthy"
  else warn "frontend container up but not healthy yet"; fi
else bad "frontend (jobfit-frontend) not running  →  make up"; fi
if running jobfit-poller; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' jobfit-poller 2>/dev/null)" = "healthy" ]; then
    ok "poller (jobfit-poller) healthy — Gmail intake + write-back"
  else warn "poller container up but not healthy (loops stalled?  →  docker logs jobfit-poller)"; fi
else bad "poller (jobfit-poller) not running  →  make up"; fi
if running jobfit-jsearch; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' jobfit-jsearch 2>/dev/null)" = "healthy" ]; then
    ok "jsearch (jobfit-jsearch) healthy — daily JSearch API intake"
  else warn "jsearch container up but not healthy (needs JSEARCH_API_KEY?  →  docker logs jobfit-jsearch)"; fi
else warn "jsearch (jobfit-jsearch) not running  →  set JSEARCH_API_KEY in .env, then make up"; fi
if running jobfit-notifier; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' jobfit-notifier 2>/dev/null)" = "healthy" ]; then
    ok "notifier (jobfit-notifier) healthy — Discord/Telegram from the completed-event stream"
  else warn "notifier container up but not healthy  →  docker logs jobfit-notifier"; fi
else warn "notifier (jobfit-notifier) not running  →  make up"; fi
if running jobfit-processor; then
  if [ "$(docker inspect -f '{{.State.Health.Status}}' jobfit-processor 2>/dev/null)" = "healthy" ]; then
    ok "processor (jobfit-processor) healthy — scan/scrape/score/tailor"
  else warn "processor container up but not healthy (loop stalled?  →  docker logs jobfit-processor)"; fi
else bad "processor (jobfit-processor) not running  →  make up"; fi

hdr "Postgres data"
CNT="$(docker exec jobfit-db psql -U "${POSTGRES_USER:-jobfit}" -d "${POSTGRES_DB:-jobfit}" -tAc 'SELECT count(*) FROM tracks;' 2>/dev/null || true)"
if [ -n "$CNT" ]; then ok "tracks table present (${CNT} rows)"; else warn "could not query tracks table"; fi

hdr ".env files"
for f in .env \
         services/job-fit-apply-ai-pipeline/.env \
         services/job-fit-apply-ai-bridge/.env \
         services/job-fit-apply-ai-poller/.env \
         apps/job-fit-apply-ai-backlog/.env; do
  if [ -f "$f" ]; then ok "present: $f"; else warn "missing: $f  (copy from $f.example)"; fi
done

hdr "Tailscale"
TS="${TAILSCALE_BIN:-tailscale}"; command -v "$TS" >/dev/null 2>&1 || TS=/usr/local/bin/tailscale
if "$TS" status >/dev/null 2>&1; then ok "Tailscale up & logged in"; else bad "Tailscale not running / not logged in"; fi
if "$TS" serve status 2>/dev/null | grep -qi 'tailnet only'; then ok "Tailscale Serve configured"; else warn "no Tailscale Serve config  →  make serve"; fi

hdr "Tailnet-exposed services (host loopback)"
port_open 8081 && ok "markserv on 127.0.0.1:8081" || warn "nothing on 127.0.0.1:8081"
port_open 8765 && ok "bridge on 127.0.0.1:8765"   || warn "nothing on 127.0.0.1:8765"
port_open 3030 && ok "frontend on 127.0.0.1:3030" || warn "nothing on 127.0.0.1:3030"

hdr "Host services needed by the pipeline"
port_open 11436 && ok "MLX/oMLX :11436" || warn "MLX/oMLX not reachable on :11436 (scoring/tailoring)"
port_open 11434 && ok "Ollama :11434"   || warn "Ollama not reachable on :11434"
port_open 9222  && ok "Chrome CDP :9222" || warn "Chrome CDP not reachable on :9222 (JD scraping)"
# The processor container dials these via host.docker.internal (works on Docker Desktop 29.x —
# its host-side proxy connects to loopback-bound ports). Probe the path from inside the container.
if running jobfit-processor; then
  if docker exec jobfit-processor curl -sf -m 5 http://host.docker.internal:11434/api/version >/dev/null 2>&1; then
    ok "container → host.docker.internal reaches host services"
  else warn "container cannot reach host.docker.internal:11434 — processor's local LLM/CDP calls will fail"; fi
fi

hdr "Processor container inputs (bind-mount sources)"
# Compose bind-mounts these three; if one is absent Docker silently creates a DIRECTORY in its
# place and the processor gets an unreadable/empty input.
for f in services/job-fit-apply-ai-pipeline/.env \
         services/job-fit-apply-ai-pipeline/src/main/resources/resume/resume.yaml \
         services/job-fit-apply-ai-pipeline/config/candidate_profile.yaml; do
  if [ -f "$f" ]; then ok "present: $f"
  elif [ -d "$f" ]; then bad "$f is a DIRECTORY (created by docker before the file existed) — remove it and create the real file"
  else bad "missing: $f — the processor container needs it (bind-mounted read-only)"; fi
done

hdr "Pipeline data root (durable artifacts + browser session state)"
# Same precedence compose uses; see scripts/jfaa-data-root.sh.
PIPELINE_OUTPUT="$(jfaa_pipeline_output)"
PIPELINE_STATE="$(jfaa_pipeline_state)"
for pair in "output:$PIPELINE_OUTPUT:$(jfaa_legacy_pipeline_output)" \
            "state:$PIPELINE_STATE:$(jfaa_legacy_pipeline_state)"; do
  what="${pair%%:*}"; rest="${pair#*:}"; cur="${rest%%:*}"; legacy="${rest#*:}"
  if jfaa_unmigrated "$legacy" "$cur"; then
    bad "pipeline $what NOT migrated — $legacy has data, $cur is empty (see docs/data-root-migration.md)"
  elif [ -d "$cur" ]; then ok "pipeline $what: $cur"
  else warn "pipeline $what dir does not exist yet: $cur (docker will create it on first start)"; fi
done
# Authenticated Steel/browser cookies live here; markserv must never serve them.
if [ -d "$PIPELINE_STATE" ] && [ "$(stat -f '%Lp' "$PIPELINE_STATE" 2>/dev/null || stat -c '%a' "$PIPELINE_STATE" 2>/dev/null)" != "700" ]; then
  warn "pipeline state is not mode 700: $PIPELINE_STATE (holds authenticated browser session state)"
fi

hdr "Gmail (containerized Poller owns all Gmail)"
if [ -n "${JD_POLLER_SECRETS_HOST:-}" ]; then
  POLLER_SECRETS="$JD_POLLER_SECRETS_HOST"
elif [ -n "${JFAA_DATA_ROOT:-}" ]; then
  POLLER_SECRETS="$JFAA_DATA_ROOT/poller-secrets"
else
  POLLER_SECRETS="$HOME/.local/share/jfaa/poller-secrets"
fi
if [ -f "$POLLER_SECRETS/tokens/gmail_token.json" ]; then
  # Warn if the token is stale (Testing-mode refresh token expires ~weekly).
  if [ -n "$(find "$POLLER_SECRETS/tokens/gmail_token.json" -mtime +6 2>/dev/null)" ]; then
    warn "Gmail token >6 days old — refresh soon: docker compose run --rm poller --reauth"
  else ok "Gmail token present in poller secrets  ($POLLER_SECRETS)"; fi
else bad "Gmail token missing in $POLLER_SECRETS/tokens  →  docker compose run --rm poller --reauth"; fi

hdr "PM2 (fully retired — everything is Compose now)"
if command -v pm2 >/dev/null 2>&1; then
  if pm2 list 2>/dev/null | grep -E "\bjd-(worker|poller|processor)\b" | grep -qi online; then
    warn "pm2 jd-worker/jd-poller/jd-processor still online — should be gone (all services are containers now)"
  else
    ok "no pipeline processes under pm2"
  fi
else ok "pm2 not on PATH (expected — fully migrated to Compose)"; fi

echo
warnsuffix=""
[ "$WARNS" -gt 0 ] && warnsuffix="$(printf " \033[33m(%d warning(s))\033[0m" "$WARNS")"
if [ "$FAILS" -eq 0 ]; then
  printf "\033[32mAll critical checks passed.\033[0m%s\n" "$warnsuffix"; exit 0
else
  printf "\033[31m%d critical check(s) failed.\033[0m%s\n" "$FAILS" "$warnsuffix"; exit 1
fi
