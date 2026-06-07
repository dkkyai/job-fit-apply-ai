# Job Fit to Apply AI Suite - README

A monorepo AI pipeline that automates the complete job search workflow: Gmail inbox scanning → email classification → digest fan-out → job scraping → fit scoring → resume tailoring → cover letter generation → PDF rendering → recruiter reply drafting → Supabase tracking → dashboard management. A Chrome extension extends the same pipeline to job boards encountered during normal browsing.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────────────────┐
│  Gmail Inbox                                                                   │
│  Recruiter emails + job board digests (LinkedIn, Glassdoor,                    │
│  Indeed, Lensa, Monster, JobLeads, JobRight, WTTJ, ...)                        │
└──────────────────────┬─────────────────────────────────────────────────────────┘
                       │ OAuth2 scan (batch, CLI-driven)
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  services/langgraph-ai-pipeline  (Kotlin LangGraph, JVM 21)                    │
│                                                                                │
│  classify → fan-out → scrape → dedup → score → tailor                          │
│  → cover letter → PDF → draft reply → label/archive → track                    │
│                                                                                │
│  Main graph (16+ nodes):                                                       │
│    ScanEmail → [fan-out digest jobs] → ScrapeJd                                │
│    → SaveJD → CheckDuplicate → ScoreFit                                        │
│    → [if tailor] ResumeTailoringSubgraph (6 nodes)                             │
│    → GenerateCoverLetter → RenderResumePdf (Playwright)                        │
│    → AddArtifactUrl → SupabaseTrack                                            │
│    → [if recruiter email] CreateDraftReply                                     │
│    → EmailLabelingService (label / archive / star)                             │
│                                                                                │
│  Tailoring subgraph:                                                           │
│    JdExtraction → GapAnalysis → SummaryRewrite                                 │
│    → BulletRewrite → SkillsRestructure → AtsScoring                            │
│                                                                                │
│  Output per job: output/{timestamp}_{company}_{role}/                          │
│    tailored_resume.html, <Name>_<Role>.pdf, cover_letter.txt,                  │
│    score_fit.txt, gap_analysis.json, ats_score.txt, ...                        │
└──────────────────────┬─────────────────────────────────────────────────────────┘
                       │ POST /api/jobs  (HTTP, loopback)
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  services/job-fit-apply-ai-bridge  (Kotlin Ktor, port 8765)      │
│  SQLite job queue — submit / claim / result / artifact endpoints               │
│  Bound to: 127.0.0.1:8765  +  <tailscale-ip>:8765                             │
└──────────┬──────────────────────────────────────────────────┬──────────────────┘
           │ claim()  (worker polls)                          │ POST /api/jobs
           ▼                                                  │ (Tailscale)
┌──────────────────────────────────────┐                      │
│  services/job-fit-apply-ai-pipeline  │             ┌────────┴───────────────────┐
│  --worker  (pm2: jd-worker)          │             │  Chrome Browser            │
│                                      │             │  apps/job-description-to-  │
│  ProcessingPipeline:                 │             │  ai-pipeline-browser-      │
│  CheckDuplicate → ScoreFit           │             │  extension  (MV3)          │
│  → ResumeTailoringSubgraph (6 nodes) │             │  13 ATS extractors         │
│  → GenerateCoverLetter               │             └────────────────────────────┘
│  → RenderResumePdf (Playwright)      │
│  → AddArtifactUrl → SupabaseTrack    │       JSearch API  (cron: daily 5 AM)
│  → postResult()                      │         --jsearch → bridge.submit()
└──────────────────────────────────────┘              (same queue, same worker)


                       │ INSERT/UPDATE tracks table
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  Supabase (PostgreSQL)  —  tracks table                                        │
└──────────────────────┬─────────────────────────────────────────────────────────┘
                       │ reads + writes (status updates only)
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  apps/job-fit-apply-ai-backlog  (React + TypeScript + Vite, port 8080)              │
│  Live dashboard: fit-score filter, status management,                          │
│  collapsible rows, direct PDF + cover letter downloads                         │
└────────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────────┐
│  Chrome Browser  (browsing path, separate from email)                          │
│  apps/job-description-to-ai-pipeline-browser-extension  (Chrome MV3)           │
│  13 ATS extractors + heuristic fallback                                        │
└──────────────────────┬─────────────────────────────────────────────────────────┘
                       │ POST /api/jobs (Tailscale)
                       ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│  services/job-description-to-ai-pipeline-bridge  (Kotlin Ktor, port 8765)      │
│  SQLite job lifecycle → subprocess → services/langgraph-ai-pipeline            │
│  Serves artifacts: GET /api/jobs/{id}/resume.pdf                               │
└──────────────────────┬─────────────────────────────────────────────────────────┘
                       │ ./gradlew run --jd-json-file
                       ▼
                  services/langgraph-ai-pipeline  (same pipeline)
```

---

## Repos

| Repo | Language | Description |
|---|---|---|
| `services/job-fit-apply-ai-pipeline` | Kotlin / JVM 21 | Email ingestion pipeline + processing pipeline + worker; CLI entry point for all modes |
| `services/job-fit-apply-ai-bridge` | Kotlin / JVM 21 | Ktor bridge — SQLite job queue, claim/result/artifact API, artifact file server |
| `apps/job-fit-apply-ai-extension` | JavaScript (MV3) | Chrome extension — JD extraction from job boards, real-time progress UI |
| `apps/job-fit-apply-ai-backlog` | TypeScript / React 18 | Vite dashboard — live job table, status management, artifact downloads |

---

## Prerequisites

### services/langgraph-ai-pipeline
- JDK 21
- Gradle (wrapper included)
- **Ollama** running locally with models pulled, or cloud API keys for MiniMax / DeepSeek
- **Playwright** with Chromium installed
- A Chrome profile logged in to LinkedIn (for LinkedIn scraping)
- **Gmail OAuth credentials** — `credentials.json` from Google Cloud Console (Gmail API enabled, OAuth 2.0 client for desktop app)
- **Supabase** project with `tracks` table (schema below)

### services/job-fit-apply-ai-bridge
- JDK 21
- **Tailscale** (optional — required only for Chrome extension access)

### apps/job-fit-apply-ai-extension
- Chrome with Developer Mode enabled
- **Tailscale** — extension communicates with bridge over MagicDNS address

### apps/job-fit-apply-ai-backlog
- Node.js 18+ or Bun 1.0+
- Same Supabase project as the pipeline

---

## Setup

### 1. Supabase

Create a project at [supabase.com](https://supabase.com) and run:

```sql
create table tracks (
  id              bigserial primary key,
  company         text not null,
  role_title      text not null,
  location        text,
  remote_policy   text,
  fit_score       integer,
  job_url         text,
  artifact_url    text,
  tech_stack      text[],
  status          text default 'backlog',
  duplicate       boolean default false,
  duplicate_id    bigint,
  created_at      timestamptz default now()
);
```

Save your project URL and anon key — used by both the pipeline and the dashboard.

---

### 2. services/job-fit-apply-ai-bridge

```bash
cd services/job-fit-apply-ai-bridge

# Edit Config.kt — set models, Supabase credentials, Gmail file paths
# src/main/kotlin/com/jd/pipeline/config/Config.kt

# Initialize your profile (generates candidate_profile.json & generated_resume.html)
./gradlew run --args="--init-profile"

# First-time Gmail OAuth
./gradlew run --args="--reauth"

# Verify token
./gradlew run --args="--check-token"

# Test with a sample job URL end-to-end
./gradlew run --args="--test"

# Test resume tailoring in isolation
./gradlew run --args="--test-resume"

# Test cover letter generation
./gradlew run --args="--test-coverletter"

# List Gmail inbox (no processing)
./gradlew run --args="--test-gmail"

# Process Gmail inbox (default 3 emails)
./gradlew run

# Process up to 10 emails
./gradlew run --args="--max-emails 10"

# Process one email by subject
./gradlew run --args='--email "Staff SDET opportunity at Acme"'
```

---

### 3. services/job-description-to-ai-pipeline-bridge

```bash
cd services/job-description-to-ai-pipeline-bridge

cat > .env << EOF
JD_BRIDGE_PIPELINE_DIR=/absolute/path/to/services/langgraph-ai-pipeline
EOF

# Development
./gradlew run

# macOS LaunchAgent (auto-start on login)
cp scripts/ai.openclaw.jd-bridge.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/ai.openclaw.jd-bridge.plist
```

---

### 4. Chrome Extension

1. Chrome → `chrome://extensions` → enable Developer mode
2. Load unpacked → select `apps/job-fit-apply-ai-extension/`
3. Set your bridge address in `config.js`:

```js
export const BRIDGE_API_URL = 'http://your-machine.ts.net:8765'; // or http://localhost:8765
```

---

### 5. apps/job-fit-apply-ai-backlog

```bash
cd apps/job-fit-apply-ai-backlog
npm install

cat > .env << EOF
VITE_SUPABASE_URL=https://your-project.supabase.co
VITE_SUPABASE_ANON_KEY=your-anon-key
EOF

npm run dev           # http://localhost:8080
npm run build         # production build → dist/
```

For cloud hosting, deploy to Vercel: set the two `VITE_SUPABASE_*` environment variables and it auto-deploys on push to main.

**macOS LaunchAgent (optional):**
```bash
cp scripts/com.dkkytech.backlog.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.dkkytech.backlog.plist
# Serves on Tailscale IP — accessible from any device on the private network
```

---

## LLM Configuration (`Config.kt`)

By default, the pipeline uses local Qwen models via Ollama. You can route to cloud providers (Ollama Cloud, DeepSeek, MiniMax) by setting the respective `*_BASE_URL` and `*_API_KEY` in `.env`.

```kotlin
val SCAN_MODEL               = "qwen3.5:9b-q4_K_M"   // fast classification, local
val SCRAPE_MODEL             = "qwen3.5:9b-q4_K_M"
val SCORE_MODEL              = "qwen3.5:9b-q4_K_M"   // rubric-based fit scoring
val RESUME_REASONING_MODEL   = "qwen3.5:9b-q4_K_M"   // deep reasoning
val COVER_LETTER_MODEL       = "qwen3.5:9b-q4_K_M"
val DRAFT_REPLY_MODEL        = "qwen3.5:9b-q4_K_M"
val SKILLS_MODEL             = "qwen3.5:9b-q4_K_M"   // skills restructure

val FIT_THRESHOLD            = 50   // below this: tracked but not tailored
val DUPLICATE_WINDOW_DAYS    = 30   // dedup window: company × role × location
val GMAIL_MAX_EMAILS         = 3    // emails per batch run
```

---

## Gmail Search Query

The default query fetches emails from the last 7 days, from INBOX only, excluding already-processed messages:

```
newer_than:7d in:inbox -label:JD_Not_Found -label:Recruiter_Response_Required
```

Override `GMAIL_SEARCH_QUERY` in `Config.kt` to target different senders or date ranges.

**Gmail labels applied by the pipeline:**

| Outcome | Label | Inbox Action |
|---|---|---|
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
| `DRAFT_REPLY_SKILL.md` | CreateDraftReplyNode — recruiter reply generation |

---

## Adapting for Your Own Job Search

1. **Initialize your profile** — Run `./gradlew run --args="--init-profile"` to generate your `candidate_profile.json` and personal `generated_resume.html` from the base template.
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
3. Recruiter email body is **sanitized** before reaching the LLM — lines matching prompt injection patterns are stripped (`ignore previous instructions`, `you are now`, `act as`, `system prompt`, etc.)
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

For browser-triggered jobs, artifacts are also copied to `~/.openclaw/jd-bridge/jobs/{job_id}/` and served by the bridge API.

---

## Testing

```bash
# Pipeline (Kotlin)
cd services/langgraph-ai-pipeline && ./gradlew test

# Bridge — unit + integration tests
cd services/job-fit-apply-ai-bridge && ./gradlew test

# Dashboard — unit tests
cd apps/job-fit-apply-ai-backlog && npm run test:unit

# Dashboard — E2E (Playwright, requires built app on :8080)
cd apps/job-fit-apply-ai-backlog && npm run test:e2e

# Dashboard — full CI suite locally
cd apps/job-backlog-web-app && npm run test:ci

# Extension
cd apps/job-fit-apply-ai-extension && npm test
```

The dashboard CI (`.github/workflows/ci.yml`) runs lint → unit tests on Node 18 and 20 → production build → Playwright E2E in sequence, then deploys to Vercel on main branch merge.

---

## Known Constraints

- **LinkedIn scraping requires a logged-in Chrome profile.** Set `PLAYWRIGHT_CHROME_PROFILE` in `Config.kt` to a Chrome profile already authenticated to LinkedIn.
- **Local LLM quality scales with model size.** The 6-node tailoring subgraph produces significantly better results with 70B+ models. Smaller models tend to hallucinate resume content.
- **The Kotlin pipeline must be buildable before the bridge can use it.** Run `./gradlew build` in the pipeline directory first to resolve Gradle dependencies.
- **Tailscale is required for the extension → bridge connection by default.** To run on localhost instead, update `BRIDGE_API_URL` in `config.js` and adjust CORS settings in `Application.kt`.
- **Gmail OAuth tokens expire.** Run `./gradlew run --args="--reauth"` to refresh. Use `--check-token` to verify status without triggering a full run.
- **Fit scores are LLM-generated and model-dependent.** Tune the scoring rubric in `SCORE_SKILL.md` until scores feel calibrated to your profile.
- **Draft replies are not sent automatically.** Review every draft in Gmail before sending.
