# Job Fit to Apply AI Suite - README

A monorepo AI pipeline that automates the complete job search workflow: Gmail inbox scanning → email classification → digest fan-out → job scraping → fit scoring → resume tailoring → cover letter generation → PDF rendering → recruiter reply drafting → Postgres tracking → dashboard management. A Chrome extension extends the same pipeline to job boards encountered during normal browsing.

The datastore and app tier — **Postgres, the HTTP bridge, the artifact/markdown server, and the dashboard** — run as **Docker Compose** services, each exposed to your tailnet (and nothing else) via **Tailscale Serve**. The pipeline **worker** and its browser/LLM dependencies (Chrome/CDP, MLX, Ollama) run on the host and reach the bridge over a published loopback port. Bring the whole stack up with `make up`; check it with `make doctor`.

---

## Architecture

> Rendered diagram: [`docs/architecture-diagram.md`](docs/architecture-diagram.md).

```mermaid
flowchart TB
    %% ---------------- Intake sources ----------------
    subgraph SRC["Intake sources"]
        direction LR
        GM["Gmail Inbox"]
        EXT["Chrome Extension<br/>(MV3) — JD extraction"]
        JS_API["JSearch API<br/>(RapidAPI)"]
    end

    %% ---------------- Docker Compose ----------------
    subgraph DC["Docker Compose — tailnet-only via Tailscale Serve"]
        direction TB

        subgraph INTAKE["Intake services (containers)"]
            direction LR
            POLLER["poller (jobfit-poller)<br/>Gmail intake + write-back"]
            JSEARCH["jsearch (jobfit-jsearch)<br/>JSearch intake · daily / self-gated"]
        end

        BRIDGE["bridge (jobfit-bridge) — Ktor · 127.0.0.1:8765<br/>SQLite job queue (claim/result) + artifact API<br/>+ Postgres-backed /api/tracks"]

        DB[("db (jobfit-db)<br/>Postgres · :5432<br/>tracks / resume_tailoring")]

        FRONT["frontend (jobfit-frontend)<br/>nginx · :3030 · React dashboard"]

        MARK["markserv (jobfit-markserv)<br/>:8081 · renders output dir"]

        NOTIFIER["notifier (jobfit-notifier)<br/>completed-event consumer"]
    end

    %% ---------------- Host worker ----------------
    subgraph HOST["Host — PM2: jd-processor (no Gmail)"]
        direction TB
        N1["CheckDuplicate"] --> N2["ScoreFit"]
        N2 --> N3["ResumeTailoringSubgraph (6 nodes):<br/>JdExtraction · GapAnalysis · SummaryRewrite<br/>BulletRewrite · SkillsRestructure · AtsScoring"]
        N3 --> N4["GenerateCoverLetter"]
        N4 --> N5["RenderResumePdf (Playwright)"]
        N5 --> N6["AddArtifactUrl"] --> N7["Track → Postgres"] --> N8["postResult()"]
    end

    subgraph DEPS["Host dependencies"]
        direction LR
        CDP["Chrome / CDP :9222"]
        MLX["MLX / oMLX :11436"]
        OLL["Ollama :11434"]
    end

    %% ---------------- Edges ----------------
    GM -->|IMAP/OAuth| POLLER
    JS_API --> JSEARCH
    EXT -->|submit JdRecord HTTP| BRIDGE
    POLLER -->|submit JdRecord| BRIDGE
    JSEARCH -->|submit JdRecord| BRIDGE

    N1 <-->|"poll claim() / postResult()<br/>http://127.0.0.1:8765"| BRIDGE
    N7 -->|JDBC| DB
    POLLER -.->|"write-back: Gmail labels, recruiter draft"| GM

    BRIDGE <-->|JDBC| DB
    FRONT -->|GET/POST /api/tracks| BRIDGE
    MARK -.->|reads output/ bind-mount| N7
    BRIDGE -->|completed event stream| NOTIFIER
    NOTIFIER -->|alerts| CHAT["Discord / Telegram"]

    N3 --> MLX
    N3 --> OLL
    N1 --> CDP
    N2 --> MLX

    classDef container fill:#1f6feb22,stroke:#1f6feb;
    classDef host fill:#8957e522,stroke:#8957e5;
    classDef src fill:#23863622,stroke:#238636;
    classDef store fill:#9e6a0322,stroke:#d29922;
    class POLLER,JSEARCH,BRIDGE,FRONT,MARK,NOTIFIER container;
    class N1,N2,N3,N4,N5,N6,N7,N8,CDP,MLX,OLL host;
    class GM,EXT,JS_API src;
    class DB store;
```

**Containerized services** (all bind host loopback only; reach them on your tailnet via Tailscale Serve — see [`docs/tailscale-serve.md`](docs/tailscale-serve.md)):

| Service | Container | Host bind | Tailnet URL |
|---|---|---|---|
| Postgres | `jobfit-db` | `127.0.0.1:5432` | — (internal only) |
| Bridge (Ktor) | `jobfit-bridge` | `127.0.0.1:8765` | `http://<tailscale-name>:8765` |
| Dashboard (nginx) | `jobfit-frontend` | `127.0.0.1:3030` | `http://<tailscale-name>:3030` |
| Artifact server (markserv) | `jobfit-markserv` | `127.0.0.1:8081` | `http://<tailscale-name>:8081` |

**On the host:** `jd-processor` (PM2) plus the local model servers and Chrome/CDP. Gmail intake + write-back now runs in the containerized `jobfit-poller`; the host processor no longer touches Gmail. The processor reaches the bridge at `http://127.0.0.1:8765`; the browser/extension reach the bridge and dashboard over Tailscale.

### Queue concurrency guards

The `--max-emails` cron run is protected against re-entrant overlap at two levels:

1. **`heartbeat_check.sh`** — checks the PID file before starting; exits immediately with `ALREADY_RUNNING` if the previous run is still polling the bridge.
2. **`-label:Processing`** — the Gmail search query excludes emails already labeled in-flight, so a second run that slips past the PID check still won't re-ingest the same email.

---

## Repos

| Repo | Language | Description |
|---|---|---|
| `services/job-fit-apply-ai-pipeline` | Kotlin / JVM 21 | Email ingestion pipeline + processing pipeline + worker; CLI entry point for all modes. Runs on the host (needs Chrome/CDP + local LLMs). |
| `services/job-fit-apply-ai-bridge` | Kotlin / JVM 21 | Ktor bridge — SQLite job queue, claim/result/artifact API, and the Postgres-backed `tracks` API for the dashboard. **Containerized** (`jobfit-bridge`). |
| `apps/job-fit-apply-ai-extension` | JavaScript (MV3) | Chrome extension — JD extraction from job boards, real-time progress UI. |
| `apps/job-fit-apply-ai-backlog` | TypeScript / React 18 | Vite dashboard — live job table, status management, artifact downloads. **Containerized** (`jobfit-frontend`, served by nginx). |

---

## Prerequisites

### Everyone
- **Docker Desktop** (with Compose v2) — runs Postgres, the bridge, the dashboard, and markserv. Enable **Settings → General → Start Docker Desktop when you sign in** so the stack returns after a reboot.
- **Tailscale** — the containers are tailnet-only; `tailscale serve` (host-side) exposes them. No Tailscale runs inside Docker.
- **GNU Make** — for the `make up` / `make doctor` bootstrap.

### Host worker — `services/job-fit-apply-ai-pipeline`
- JDK 21 + Gradle (wrapper included)
- **MLX/oMLX** (`:11436`) and/or **Ollama** (`:11434`) with models, or cloud API keys (MiniMax / DeepSeek / Anthropic)
- **Playwright** with Chromium; a Chrome profile logged in to LinkedIn (for LinkedIn scraping) reachable over **CDP** (`:9222`)
- **Gmail OAuth credentials** — `gmail_credentials.json` from Google Cloud Console (Gmail API enabled, OAuth 2.0 desktop client)

### Dashboard / bridge development
- Node.js 20+ (only needed for local dashboard dev; the container build handles production)
- JDK 21 (only needed for local bridge dev / running the test suite)

> **No Supabase.** The `tracks` / `resume_tailoring` schema is created automatically by the Postgres container from `db/init/001_schema.sql` on first boot.

---

## Setup

### 1. Bring up the containers

```bash
# From the repo root:
cp .env.example .env          # optional — every value has a compose default
make up                       # docker compose up -d  +  tailscale serve
make doctor                   # verify the whole stack (read-only)
```

`make up` starts `db`, `bridge`, `frontend`, and `markserv`, then configures Tailscale Serve for `:8765`, `:3030`, and `:8081`. Config lives in the root `.env` (see `.env.example`); the `DATABASE_URL` the containers use is derived from `POSTGRES_*` and points at the compose service `db`.

Migrating existing rows from a previous Supabase project? See `scripts/migrate_supabase_to_postgres.py` (idempotent REST → Postgres copy).

### 2. Host worker — `services/job-fit-apply-ai-pipeline`

```bash
cd services/job-fit-apply-ai-pipeline

# Initialize your profile (generates candidate_profile.json & generated_resume.html)
./gradlew run --args="--init-profile path/to/your_resume.pdf"

# First-time Gmail OAuth, then verify (Gmail lives in the Poller — Phase 1)
( cd ../job-fit-apply-ai-poller && ./gradlew run --args="--reauth" )
( cd ../job-fit-apply-ai-poller && ./gradlew run --args="--check-token" )

# Test end-to-end on a sample JD (no Gmail required)
./gradlew run --args="--test"

# Build both dists and start the two Phase 1 processes under PM2.
# (Cutting over from an existing jd-worker? Follow docs/phase1-cutover-runbook.md.)
./gradlew installDist
( cd ../job-fit-apply-ai-poller && ./gradlew installDist )
pm2 start --name jd-processor --cwd "$PWD" --interpreter bash \
  build/install/job-fit-apply-ai-pipeline/bin/job-fit-apply-ai-pipeline -- --processor
pm2 start --name jd-poller --cwd "$PWD/../job-fit-apply-ai-poller" --interpreter bash \
  ../job-fit-apply-ai-poller/build/install/job-fit-apply-ai-poller/bin/job-fit-apply-ai-poller -- --poll
pm2 save
```

The worker's `.env` selects the database backend: `DB_BACKEND=postgres` with `DATABASE_URL=postgresql://jobfit:jobfit@localhost:5432/jobfit` (the published Postgres port). It writes `tracks` directly over JDBC.

### 3. Chrome Extension

1. Chrome → `chrome://extensions` → enable Developer mode
2. Load unpacked → select `apps/job-fit-apply-ai-extension/`
3. Point it at the bridge's tailnet URL in `config.js`:

```js
export const BRIDGE_API_URL = 'http://your-machine.ts.net:8765';
```

### 4. Dashboard — local development (optional)

The production dashboard is the `jobfit-frontend` container. For local dev against the live bridge:

```bash
cd apps/job-fit-apply-ai-backlog
npm install
echo "VITE_API_BASE_URL=http://localhost:8765" > .env   # or your tailnet bridge URL
npm run dev                     # http://localhost:3001
```

`VITE_API_BASE_URL` is the bridge URL the browser calls; it's baked into the container bundle at build time via the compose build arg.

---

## Automation (Docker + PM2 + cron)

| Process | Runs as | Command | Schedule |
|---|---|---|---|
| `db` / `bridge` / `frontend` / `markserv` / `poller` / `jsearch` / `notifier` | Docker Compose | `make up` (`restart: unless-stopped`) | continuous |
| Tailscale Serve (`:8765`,`:3030`,`:8081`) | host `tailscaled` | `scripts/setup-tailscale-serve.sh` | persisted across reboot |
| `jd-processor` | PM2 (always-on) | pipeline `--processor` (LLM pipeline, no Gmail) | continuous |
| `jobfit-poller` | Docker Compose | poller `--poll` (Gmail intake + write-back) | continuous |
| `jobfit-jsearch` | Docker Compose | jsearch `--once` (JSearch API intake) | daily (self-gated) |
| `jobfit-notifier` | Docker Compose | notifier `--poll` (Discord/Telegram from the completed-event stream) | continuous |

The containers and worker must be up before the cron jobs fire — run `make doctor` to confirm.

### Make targets

| Target | Action |
|---|---|
| `make up` | `docker compose up -d` + configure Tailscale Serve |
| `make down` | Stop & remove containers (named volumes / data kept) |
| `make restart` | Recreate containers from current compose config |
| `make status` | Container status + Tailscale Serve config |
| `make serve` | (Re)configure Tailscale Serve only |
| `make doctor` | Read-only health check of the whole stack |
| `make logs` | Tail container logs |

---

## LLM Configuration (`Config.kt`)

By default, the pipeline uses local models via MLX/oMLX (`:11436`) and Ollama (`:11434`). You can route individual nodes to cloud providers (Ollama Cloud, DeepSeek, MiniMax, Anthropic) by setting the respective `*_MODEL`, `*_BASE_URL`, and `*_API_KEY` in `.env`. See `tuner/env-llm-tuner/` for curated per-node model presets.

```kotlin
val SCAN_MODEL               // fast classification
val SCRAPE_MODEL
val SCORE_MODEL              // rubric-based fit scoring
val RESUME_REASONING_MODEL   // deep reasoning (prefer a dense ≥27B model)
val COVER_LETTER_MODEL
val DRAFT_REPLY_MODEL
val SKILLS_MODEL             // skills restructure

val FIT_THRESHOLD         = 50   // below this: tracked but not tailored
val DUPLICATE_WINDOW_DAYS = 30   // dedup window: company × role × location
val GMAIL_MAX_EMAILS      = 3    // emails per batch run
```

---

## Gmail Search Query

The default query fetches emails from the last 7 days, from INBOX only, excluding already-processed or in-flight messages:

```
newer_than:7d in:inbox -label:JD_Not_Found -label:Recruiter_Response_Required -label:Processing
```

Override `GMAIL_SEARCH_QUERY` in `.env` or `Config.kt` to target different senders or date ranges.

**Gmail labels applied by the pipeline:**

| Outcome | Label | Inbox Action |
|---|---|---|
| Submitted to bridge, awaiting worker | `Processing` | Kept in INBOX |
| Recruiter draft created | `Recruiter_Response_Required` | Star, mark unread, keep in INBOX |
| Not a job posting | `JD_Not_Found` | Mark unread, keep in INBOX |
| Digest processed | `JD_Processed_Digest` | Archive |
| Job processed | `JD_Processed` | Archive |

---

## Skill Files (Prompt Templates)

All LLM prompts live in `src/main/resources/skills/` as `.md` files. Loaded at runtime — edit without recompiling.

| File | Node |
|---|---|
| `SCAN_SKILL.md` | ScanEmailNode — recruiter email extraction |
| `SCRAPE_SKILL.md` | ScrapeJdNode — job page structured extraction |
| `SCORE_SKILL.md` | ScoreFitNode — fit scoring + JD field extraction |
| `JD_EXTRACTION_SKILL.md` | JdExtractionNode |
| `GAP_ANALYSIS_SKILL.md` | GapAnalysisNode |
| `SUMMARY_REWRITE_SKILL.md` | SummaryRewriteNode |
| `BULLET_REWRITE_SKILL.md` | BulletRewriteNode |
| `SKILLS_RESTRUCTURE_SKILL.md` | SkillsRestructureNode |
| `ATS_SCORING_SKILL.md` | AtsScoringNode |
| `DRAFT_REPLY_SKILL.md` | CreateDraftReply — recruiter reply generation |

---

## Adapting for Your Own Job Search

1. **Initialize your profile** — Run `./gradlew run --args="--init-profile path/to/resume.pdf"` to generate your `candidate_profile.json` and personal `generated_resume.html`.
2. **Update `SCORE_SKILL.md`** — rewrite the scoring rubric to reflect your background and target roles.
3. **Tune `FIT_THRESHOLD`** — lower for more tailoring, raise to be more selective.
4. **Tune `DUPLICATE_WINDOW_DAYS`** — how far back to look when deduplicating.
5. **Update `GMAIL_SEARCH_QUERY`** — adjust to match your inbox structure.
6. **Update `DRAFT_REPLY_SKILL.md`** — personalize the recruiter reply tone and signature.

---

## Digest Fan-Out: Supported Platforms

The pipeline extracts individual jobs from digest emails sent by:

| Platform | Parser |
|---|---|
| LinkedIn | Line-block splitting on `---` separators |
| Glassdoor | HTML anchor scraping with salary extraction |
| Lensa | `<table>` card extraction |
| Monster | `<a strong>` element matching |
| JobLeads | `View job:` line extraction |
| JobRight | `jobright.ai/jobs/info/` URL extraction + match-percentage parsing |
| Welcome to the Jungle | SendGrid click URL extraction |

Max 25 jobs per digest. Each child job is independently scraped, deduplicated, scored, and — if fit qualifies — tailored.

---

## Recruiter Reply Draft Flow

For recruiter emails that complete the tailor path:

1. `DRAFT_REPLY_SKILL.md` template is filled with role, company, fit score, and strengths
2. LLM generates a reply (temp=0.3 for natural prose variation)
3. Recruiter email body is **sanitized** before reaching the LLM — lines matching prompt injection patterns are stripped
4. RFC 2822 MIME message built with threading headers (In-Reply-To, References)
5. Tailored resume PDF and cover letter attached
6. Gmail Draft created via Compose API
7. Original email labeled `Recruiter_Response_Required`, starred, marked unread

**Nothing is sent automatically.** The user reviews and sends the draft manually.

---

## Output Structure

```
output/
└── 20260405_143022_acme_corp_staff_sdet/
    ├── tailored_resume.html          # Tailored HTML (source for PDF)
    ├── YourName_Staff_SDET.pdf       # Playwright-rendered PDF
    ├── cover_letter.txt              # Cover letter
    ├── score_fit.txt                 # Fit score + reasoning
    ├── gap_analysis.json             # Skills gap table
    ├── tailored_summary.txt          # Rewritten professional summary
    ├── tailored_bullets.txt          # Rewritten experience bullets
    ├── restructured_skills.txt       # Reordered skills section
    └── ats_score.txt                 # ATS composite scorecard
```

The pipeline writes into `services/job-fit-apply-ai-pipeline/output/`, which is bind-mounted read-only into `jobfit-markserv` and rendered at `http://<tailscale-name>:8081/<job-dir>/report.md`. Browser-triggered jobs also expose artifacts via the bridge at `GET /api/jobs/{id}/resume.pdf` and `/cover_letter.txt`.

---

## Testing

```bash
# Pipeline (Kotlin) — unit + gateway/live-Postgres tests
cd services/job-fit-apply-ai-pipeline && ./gradlew test

# Bridge — unit + integration (spins an isolated jobfit_test DB in the running Postgres)
cd services/job-fit-apply-ai-bridge && ./gradlew test

# Dashboard — unit tests
cd apps/job-fit-apply-ai-backlog && npm run test:unit

# Dashboard — E2E (Playwright, requires the running app)
cd apps/job-fit-apply-ai-backlog && npm run test:e2e

# Extension
cd apps/job-fit-apply-ai-extension && npm test

# Whole-stack health (read-only)
make doctor
```

DB-backed tests (`PostgresGatewayLiveTest`, `TracksApiTest`) connect over TCP to the running `jobfit-db` container and **skip automatically** when it isn't up, so they're CI-safe. `TracksApiTest` self-provisions an isolated `jobfit_test` database so it never touches real data.

CI runs the test suites on every push to `main` and publishes a combined Allure report to GitHub Pages.

---

## Known Constraints

- **The stack relies on Docker Desktop being set to start on login** — with `restart: unless-stopped`, the containers return after a reboot only if Docker Desktop auto-starts. `tailscale serve` config is persisted by `tailscaled` and restored automatically.
- **`jd-processor` runs on the host (PM2); the Poller is a container.** The Processor needs Chrome/CDP + MLX/Ollama, so it stays on the host and reaches the bridge over the published loopback port. The `jobfit-poller` container owns all Gmail and reaches the bridge over the Compose network (`http://bridge:8765`); its token lives in a mounted secrets volume.
- **LinkedIn scraping requires a logged-in Chrome profile** reachable over CDP. Set `CHROME_PROFILE_DIRECTORY` / `CHROME_CDP_ENDPOINT` in `.env`.
- **Local LLM quality scales with model size.** The 6-node tailoring subgraph produces significantly better results with dense ≥27B models; smaller models tend to hallucinate resume content.
- **Containers are tailnet-only.** They bind `127.0.0.1` and are exposed via Tailscale Serve — not reachable from the LAN or the public internet. Run `make serve` if a service isn't reachable on the tailnet.
- **Gmail OAuth tokens expire.** Refresh from the Poller container: `docker compose run --rm poller --reauth` (browser-free — it prints the consent URL, you paste the redirect URL back); `--check-token` verifies status without a full run.
- **Fit scores are LLM-generated and model-dependent.** Tune the scoring rubric in `SCORE_SKILL.md` until scores feel calibrated to your profile.
- **Draft replies are not sent automatically.** Review every draft in Gmail before sending.
