# Phase 1 cutover runbook — `jd-worker` → `jd-processor` + `jd-poller`

Splits the monolithic `jd-worker` into two PM2 processes:

- **`jd-processor`** — pipeline `--processor`: claims work items from the bridge, scans/scrapes/scores/tailors, posts results. **No Gmail.**
- **`jd-poller`** — poller `--poll`: the only Gmail-touching service. Intake loop (Gmail → bridge) + write-back loop (bridge completed-feed → Gmail labels + drafts).

The Poller reuses the existing Gmail token/credentials (see `services/job-fit-apply-ai-poller/.env`), so **no re-auth is needed** at cutover.

## Paths

```
PIPELINE_DIR=/Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline
POLLER_DIR=/Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-poller
PIPELINE_BIN=$PIPELINE_DIR/build/install/job-fit-apply-ai-pipeline/bin/job-fit-apply-ai-pipeline
POLLER_BIN=$POLLER_DIR/build/install/job-fit-apply-ai-poller/bin/job-fit-apply-ai-poller
```

## Pre-flight (non-destructive)

1. Bridge healthy: `curl -s http://127.0.0.1:8765/health` → `{"status":"ok"...}`
2. Poller dist built: `ls $POLLER_BIN`
3. Confirm the queue is idle so nothing is mid-flight during the swap:
   `curl -s http://127.0.0.1:8765/api/queue/claim -o /dev/null -w '%{http_code}\n'` → `204`

## Cutover

```bash
# 0. Back up the current (Gmail-capable) processor dist for rollback, THEN build the new one.
cp -r "$PIPELINE_DIR/build/install/job-fit-apply-ai-pipeline" \
      "$PIPELINE_DIR/build/install/job-fit-apply-ai-pipeline.pre-phase1"
( cd "$PIPELINE_DIR" && ./gradlew installDist )   # Gmail-free --processor

# 1. Verify real Gmail from the Poller (seam #4 — the one not covered by automated tests).
( cd "$POLLER_DIR" && ./gradlew run --args='--check-token' )   # expect: VALID + account email

# 2. Swap PM2 processes.
pm2 stop jd-worker
pm2 start "$PIPELINE_BIN" --name jd-processor --cwd "$PIPELINE_DIR" --interpreter bash -- --processor
pm2 start "$POLLER_BIN"   --name jd-poller    --cwd "$POLLER_DIR"   --interpreter bash -- --poll
pm2 delete jd-worker
pm2 save

# 3. Verify.
pm2 logs jd-processor --lines 20 --nostream
pm2 logs jd-poller    --lines 20 --nostream
bash scripts/doctor.sh
```

**Acceptance:** `jd-processor` logs `[processor] Starting`; `jd-poller` logs `[poller] starting` + `Gmail account: …`; doctor shows both online and no `jd-worker`. Then confirm one real email flows intake → process → write-back (label applied, draft created for a recruiter email).

## Rollback

```bash
pm2 delete jd-processor jd-poller
rm -rf "$PIPELINE_DIR/build/install/job-fit-apply-ai-pipeline"
mv "$PIPELINE_DIR/build/install/job-fit-apply-ai-pipeline.pre-phase1" \
   "$PIPELINE_DIR/build/install/job-fit-apply-ai-pipeline"
pm2 start "$PIPELINE_BIN" --name jd-worker --cwd "$PIPELINE_DIR" --interpreter bash -- --worker
pm2 save
```

(The restored binary is the pre-Phase-1 `--worker` with Gmail intact.)
