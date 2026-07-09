# Phase 5 — Processor containerization (PM2 → Compose)

> **Status: built on branch, awaiting cutover.** The Processor (`jd-processor`) becomes the
> `processor` Compose service — the LAST PM2 process. After this, the entire system is
> `make up` + two host-side prerequisites (the CDP Chrome and the local LLM servers), and the
> whole stack can be deployed and tested E2E locally (`make e2e`) before pushing to main.
> Supersedes the "deliberately deferred" verdict in
> [processor-containerization-challenges.md](processor-containerization-challenges.md).

## What changed vs the challenges doc

The five blockers all fell, two of them differently than the doc predicted:

1. **Host-service reachability — NO socat needed.** The doc's premise (container can't reach a
   `127.0.0.1`-bound host port through `host.docker.internal`) no longer holds: on Docker
   Desktop **29.2.1** the host-side proxy connects to loopback directly (verified empirically:
   a socat listener saw container traffic arrive *from* `127.0.0.1`, and a `--network` test hit
   a `127.0.0.1`-bound python server straight through `host.docker.internal`). oMLX `:11436`,
   Ollama `:11434`, and Chrome CDP `:9222` are all dialed directly. socat was evaluated,
   proven unnecessary, and dropped.
   - **CDP wrinkle (real):** Chrome DevTools rejects HTTP requests whose `Host` header isn't an
     IP or `localhost`. `docker-entrypoint.sh` resolves `host.docker.internal` → IP and rewrites
     `CHROME_CDP_ENDPOINT` before the JVM starts. If Docker Desktop restarts and its gateway IP
     changes, restart the container (the JVM holds the resolved IP).
2. **Chromium + fonts in the image — eliminated, not solved.** PDF rendering moved from
   `HTML → headless Chromium` to **`tailored_resume.yaml → yaml_to_tex.py (jinja2) →
   tectonic`** with Roboto TTFs committed in-repo. The image has **no browser at all**; PDF
   fonts are deterministic and identical on any machine.
3. **Slow bind-mount IO** — accepted; a handful of small files per multi-minute LLM-bound job.
4. **The logged-in Chrome** stays on the host (launchd watchdog `com.jd.chrome-cdp` keeps it
   warm); the container only attaches over CDP.
5. **macOS→Linux Chromium parity** — moot: **all browser scraping goes through the host CDP
   Chrome** (the profile-copy and clean-launch fallbacks were deleted). When CDP is down,
   browser-needing scrapes fail cleanly with an alert; plain-HTTP scraping is unaffected.

## The image (`services/job-fit-apply-ai-pipeline/Dockerfile`)

Two-stage temurin-21 (poller pattern) + the LaTeX toolchain:
- `python3 python3-yaml python3-jinja2` + pinned **tectonic 0.16.9** (musl, arm64/x86_64).
- Committed resources under `/app` in repo-relative layout (`Config` resolves everything from
  `PROJECT_DIR` = `user.dir` = `/app`) — zero Config path surgery.
- **Warm-up compile as build gate:** the build compiles `resume.template.yaml` → PDF; a broken
  template/fonts/toolchain fails the *build*, not a job, and leaves tectonic's package cache
  warm (`XDG_CACHE_HOME=/opt/cache`) — runtime compiles verified working with `--network none`.
- `HEALTHCHECK` = `--health` (heartbeat freshness; the loop beats between jobs, threshold
  `HEALTH_MAX_AGE_MIN` default 90 min).
- `.dockerignore` is **load-bearing**: it keeps the gitignored personal files (`resume.yaml`,
  `candidate_profile.yaml`, `.env`, personal tex/pdf/html) out of image layers when building
  from a checkout where they exist on disk.

## Compose service (`processor` in `docker-compose.yml`)

- Bridge/db by service name; host LLMs + CDP via `host.docker.internal` (see above).
- Mounts: `output/` RW (markserv serves the same dir RO), pipeline `.env` RO (compose
  `environment:` wins over dotenv via `Config.get` precedence), `resume.yaml` +
  `candidate_profile.yaml` RO at their default paths.
- No published ports.

**Mount-source gotcha:** if a bind-mounted FILE is missing on the host, Docker silently creates
a *directory* in its place. `make doctor` pre-flights all three sources.

## Runbook

### A. Pre-flight (non-destructive; PM2 processor keeps running — bridge claims are exclusive)

```bash
# 0. Host prerequisites up: CDP Chrome + LLMs (doctor checks all three)
make doctor                        # expect: pipeline host ports ok; processor container "not running"

# 1. Build + toolchain gate (the warm-up compile IS the LaTeX/toolchain test)
docker compose build processor

# 2. In-container pipeline smoke on a sample JD (host LLMs + LaTeX PDF, no bridge)
make processor-test

# 3. Full E2E through the bridge (submits a fixture JD, waits for the PDF)
make e2e                           # E2E_REQUIRE_CDP=1 make e2e  to also require the CDP path
```

### B. Backup for rollback

The new code **removed the scrape fallbacks and swapped the PDF path**, so rollback must run
the *old* dist, not a rebuild:

```bash
cd /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline
cp -r build/install/job-fit-apply-ai-pipeline build/install/job-fit-apply-ai-pipeline.pre-phase5
```

### C. Cutover

```bash
# 1. Wait for idle (no job mid-flight), then stop — do NOT delete yet
pm2 logs jd-processor --lines 5    # confirm idle polling
pm2 stop jd-processor

# 2. Merge the branch, then update MAIN. The branch commits files that exist UNTRACKED in
#    MAIN (they'd block the pull) — remove those copies first:
cd /Users/dkkyai/projects/job-fit-apply-ai
rm -rf services/job-fit-apply-ai-pipeline/src/main/resources/resume/fonts \
       services/job-fit-apply-ai-pipeline/src/main/resources/resume/yaml_to_tex.py \
       services/job-fit-apply-ai-pipeline/src/main/resources/resume/resume_template.tex.jinja
git pull

# 3. Start the container and watch it claim a real job end-to-end
docker compose up -d --build processor
docker logs -f jobfit-processor
```

### D. Verify (before deleting anything)

- `make doctor` — processor container healthy; PM2 section shows the "still online" warning
  only until step E.
- **PDF fidelity:** open the newest `output/<job>/…pdf`; `pdffonts` (or Preview → Inspector)
  shows embedded Roboto; content matches `tailored_resume.html`. Compare
  `tailored_resume.tex` against the pre-cutover reference copy kept in the resume dir.
- **Live CDP scrape:** submit a job whose `job_url` is on a `CDP_FORCE_DOMAINS` site and
  confirm `scrapePath=cdp_forced|cdp_profile|cdp_fallback` in the logs.
- markserv still serves the new job dir; report/artifact links intact.

### E. Rollback (if needed)

```bash
docker compose stop processor
cd services/job-fit-apply-ai-pipeline
rm -rf build/install/job-fit-apply-ai-pipeline
mv build/install/job-fit-apply-ai-pipeline.pre-phase5 build/install/job-fit-apply-ai-pipeline
pm2 start jd-processor && pm2 save
```

### F. Post-soak (~1 week of clean runs)

```bash
pm2 delete jd-processor && pm2 save     # the last PM2 process is gone
```
Then update: README process table, `docs/processor-containerization-challenges.md` already
carries the superseded banner, and delete the `.pre-phase5` dist backup.

## Local E2E development loop (the point of all this)

```bash
make up              # whole system
make e2e             # fixture JD through bridge → processor → PDF → assertions
make doctor          # health of everything, incl. container→host reachability
```
Change code → `docker compose build processor && make e2e` → merge with confidence.
