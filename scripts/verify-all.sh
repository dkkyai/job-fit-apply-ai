#!/usr/bin/env bash
# Run every test level in the repo and record a per-test result table, so two runs (e.g.
# origin/main vs a refactor branch) can be diffed with `scripts/verify_results.py compare`.
#
#   scripts/verify-all.sh --out /tmp/verify-main                      # every level
#   scripts/verify-all.sh --out DIR --levels static,unit,build         # a subset
#
# Levels, in order:
#   static   compile every Kotlin module (main + test sources, incl. the e2e suite),
#            TypeScript typecheck, ESLint (dashboard + extension), Python byte-compile
#   unit     every module's own suite: Kotlin (bridge/pipeline DB tests run against a
#            throwaway Postgres), Vitest, Jest, run-analyzer unittest, compose contract
#   build    production artifacts: the dashboard's vite build and a docker image for every
#            first-party service
#   browser  Playwright against the built dashboard bundle
#   e2e      `make e2e` — the isolated compose slice (bridge → processor → notifier, fake LLM)
#
# Output: <out>/results.tsv (level, module, test, status), <out>/steps.tsv (level, step,
# exit code, seconds) and <out>/logs/. Exits non-zero if any step failed.
#
# Never touches the live stack: DB tests get their own Postgres container on a random
# loopback port, images are tagged jfaa-verify/*, and the e2e slice has its own project.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT=""
LEVELS="static,unit,build,browser,e2e"
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --levels) LEVELS="$2"; shift 2 ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$OUT" ] || { echo "--out DIR is required" >&2; exit 2; }
mkdir -p "$OUT/logs" "$OUT/raw"
OUT="$(cd "$OUT" && pwd)"
: > "$OUT/results.tsv"; : > "$OUT/steps.tsv"

RESULTS="$ROOT/scripts/verify_results.py"
KOTLIN_MODULES=(bridge pipeline poller jsearch notifier e2e)
TESTED_MODULES=(bridge pipeline poller jsearch notifier)   # e2e runs in the e2e level
WEB="$ROOT/apps/job-fit-apply-ai-backlog"
EXT="$ROOT/apps/job-fit-apply-ai-extension"
ANALYZER="$ROOT/services/job-fit-apply-ai-pipeline/tuner/run-analyzer"
FAILED=0

want() { [[ ",$LEVELS," == *",$1,"* ]]; }
svc() { echo "$ROOT/services/job-fit-apply-ai-$1"; }

# step <level> <name> <command...> — run, log, and record the exit code.
step() {
  local level="$1" name="$2"; shift 2
  local log="$OUT/logs/$level-${name//[^A-Za-z0-9_.-]/_}.log" start=$SECONDS code
  printf '%-8s %-40s ' "$level" "$name"
  "$@" > "$log" 2>&1; code=$?
  printf 'exit %-3s %4ss\n' "$code" "$((SECONDS - start))"
  printf '%s\t%s\t%s\t%s\n' "$level" "$name" "$code" "$((SECONDS - start))" >> "$OUT/steps.tsv"
  [ "$code" -eq 0 ] || { FAILED=1; tail -n 25 "$log" | sed 's/^/    │ /'; }
  return "$code"
}

collect_junit() { # collect_junit <level> <module> <results-dir>
  local files=("$3"/*.xml)
  [ -e "${files[0]}" ] || return 0
  python3 "$RESULTS" junit --level "$1" --module "$2" "${files[@]}" >> "$OUT/results.tsv"
}

gradle() { (cd "$1" && shift && ./gradlew --console=plain "$@"); }
# DB-backed tests default to localhost:5432 — the LIVE database. Always hand them an explicit
# URL: the throwaway container's, or an unreachable port (so they skip) if it isn't up.
gradle_db() { # gradle_db <db> <dir> <tasks...>
  local db="$1"; shift
  (cd "$1" && shift && DATABASE_URL="${PG_BASE:-postgresql://jobfit:jobfit@127.0.0.1:1}/$db" ./gradlew --console=plain "$@")
}

# ── throwaway Postgres for DB-backed tests ────────────────────────────────────────
# One database per module, like a dev host (bridge → jobfit_test, pipeline → jobfit): the
# bridge's TracksApiTest TRUNCATEs tracks RESTART IDENTITY and inserts explicit ids, which
# would collide with the pipeline's inserts if the two shared a database.
PG_NAME="jfaa-verify-pg-$$"
PG_BASE=""
start_pg() {
  docker run -d --rm --name "$PG_NAME" -p 127.0.0.1::5432 \
    -e POSTGRES_USER=jobfit -e POSTGRES_PASSWORD=jobfit -e POSTGRES_DB=jobfit \
    -v "$ROOT/db/init:/docker-entrypoint-initdb.d:ro" postgres:16-alpine >/dev/null || return 1
  local port i
  port="$(docker port "$PG_NAME" 5432/tcp | head -1 | awk -F: '{print $NF}')"
  for i in $(seq 1 60); do
    # pg_isready over TCP, so the init scripts (unix-socket phase) have finished.
    docker exec "$PG_NAME" pg_isready -h 127.0.0.1 -U jobfit -d jobfit >/dev/null 2>&1 && break
    sleep 1
  done
  # jobfit_test must exist up front: TracksApiTest connects to the DATABASE_URL database
  # first, then provisions its schema there and redirects TracksStore to it — a redirect an
  # explicit DATABASE_URL env var outranks, so the env var must name jobfit_test too.
  docker exec "$PG_NAME" psql -q -U jobfit -d jobfit -c "CREATE DATABASE jobfit_test" || return 1
  PG_BASE="postgresql://jobfit:jobfit@127.0.0.1:$port"
  echo "throwaway postgres $PG_NAME at 127.0.0.1:$port"
}
stop_pg() { docker rm -f "$PG_NAME" >/dev/null 2>&1 || true; }
trap stop_pg EXIT

echo "verify-all: $ROOT @ $(git -C "$ROOT" rev-parse --short HEAD)$(git -C "$ROOT" diff --quiet HEAD || echo '+dirty') → $OUT"
git -C "$ROOT" rev-parse HEAD > "$OUT/HEAD"

# ── static ────────────────────────────────────────────────────────────────────────
if want static; then
  for m in "${KOTLIN_MODULES[@]}"; do
    step static "kotlin-compile-$m" gradle "$(svc "$m")" compileKotlin compileTestKotlin
  done
  step static "web-npm-ci" bash -c "cd '$WEB' && npm ci --no-audit --no-fund"
  step static "web-typecheck" bash -c "cd '$WEB' && npx tsc --noEmit -p tsconfig.app.json"
  step static "web-eslint" bash -c "cd '$WEB' && npm run lint"
  step static "ext-npm-ci" bash -c "cd '$EXT' && npm ci --no-audit --no-fund"
  step static "ext-eslint" bash -c "cd '$EXT' && npm run lint"
  step static "python-compile" python3 -m compileall -q "$ANALYZER" "$ROOT/scripts"
fi

# ── unit (+ module integration) ───────────────────────────────────────────────────
if want unit; then
  step unit "postgres-start" start_pg
  for m in "${TESTED_MODULES[@]}"; do
    dir="$(svc "$m")"
    # cleanTest forces a real run; DATABASE_URL points DB tests at the throwaway Postgres
    # (they skip themselves when it's unreachable, so a missing DB shows up as skips).
    db=jobfit; [ "$m" = bridge ] && db=jobfit_test
    step unit "kotlin-test-$m" gradle_db "$db" "$dir" cleanTest test
    collect_junit unit "$m" "$dir/build/test-results/test"
  done
  step unit "web-vitest" bash -c "cd '$WEB' && npx vitest run --reporter=default --reporter=junit --outputFile.junit='$OUT/raw/vitest.xml'"
  [ -f "$OUT/raw/vitest.xml" ] && python3 "$RESULTS" junit --level unit --module web "$OUT/raw/vitest.xml" >> "$OUT/results.tsv"
  step unit "ext-jest" bash -c "cd '$EXT' && npx jest --json --outputFile='$OUT/raw/jest.json'"
  [ -f "$OUT/raw/jest.json" ] && python3 "$RESULTS" jest --level unit --module extension "$OUT/raw/jest.json" >> "$OUT/results.tsv"
  # The analyzer's contract tests read the LIVE bridge and jobfit-db when reachable; point them
  # at nothing so they skip (and results don't drift with live data between runs).
  step unit "run-analyzer" env JD_BRIDGE_URL=http://127.0.0.1:1 JD_DB_CONTAINER=jfaa-verify-no-such-container \
    python3 "$RESULTS" pyunit --level unit --module run-analyzer "$ANALYZER" --out "$OUT/raw/pyunit.tsv"
  cat "$OUT/raw/pyunit.tsv" >> "$OUT/results.tsv" 2>/dev/null
  step unit "compose-data-root" make -C "$ROOT" compose-data-root-test
  python3 "$RESULTS" passlines --level unit --module compose "$OUT/logs/unit-compose-data-root.log" >> "$OUT/results.tsv"
  stop_pg
fi

# ── build (production artifacts) ──────────────────────────────────────────────────
if want build; then
  step build "web-vite-build" bash -c "cd '$WEB' && npm run build"
  for pair in bridge:services/job-fit-apply-ai-bridge processor:services/job-fit-apply-ai-pipeline \
              poller:services/job-fit-apply-ai-poller jsearch:services/job-fit-apply-ai-jsearch \
              notifier:services/job-fit-apply-ai-notifier frontend:apps/job-fit-apply-ai-backlog \
              markserv:docker/markserv; do
    name="${pair%%:*}"; ctx="$ROOT/${pair#*:}"
    step build "docker-$name" docker build -q -t "jfaa-verify/$name:latest" "$ctx"
  done
fi

# ── browser (Playwright against the built bundle) ─────────────────────────────────
if want browser; then
  [ -d "$WEB/dist" ] || step browser "web-vite-build" bash -c "cd '$WEB' && npm run build"
  step browser "playwright-install" bash -c "cd '$WEB' && npx playwright install chromium"
  step browser "playwright" bash -c "cd '$WEB' && PLAYWRIGHT_JUNIT_OUTPUT_NAME='$OUT/raw/playwright.xml' npx playwright test --reporter=junit"
  [ -f "$OUT/raw/playwright.xml" ] && python3 "$RESULTS" junit --level browser --module web "$OUT/raw/playwright.xml" >> "$OUT/results.tsv"
fi

# ── e2e (isolated compose slice) ──────────────────────────────────────────────────
if want e2e; then
  rm -rf "$(svc e2e)/build/test-results/test"
  step e2e "make-e2e" make -C "$ROOT" e2e
  collect_junit e2e e2e "$(svc e2e)/build/test-results/test"
fi

echo
python3 - "$OUT/results.tsv" <<'PY'
import sys, collections
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1]) if l.strip()]
by = collections.defaultdict(collections.Counter)
for level, module, _, status in rows:
    by[(level, module)][status] += 1
for (level, module), c in sorted(by.items()):
    print(f"  {level:<8} {module:<14} " + "  ".join(f"{k}={v}" for k, v in sorted(c.items())))
print(f"  total tests: {len(rows)}")
PY
[ "$FAILED" -eq 0 ] && echo "verify-all: all steps passed" || echo "verify-all: SOME STEPS FAILED (see $OUT/logs)"
exit "$FAILED"
