# Job-Fit-Apply-AI — Architecture Diagram

A monorepo AI pipeline that automates the job-search workflow: intake → classify →
scrape → score → tailor → render → draft reply → track → surface in the dashboard.

The **datastore + app tier** runs as **Docker Compose** (tailnet-only via **Tailscale
Serve**); the **LLM/browser pipeline worker** runs on the **host** and reaches the
bridge over a published loopback port.

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
            POLLER["poller (jobfit-poller)<br/>Gmail intake + write-back<br/>Kotlin, continuous"]
            JSEARCH["jsearch (jobfit-jsearch)<br/>JSearch intake<br/>Kotlin, daily / self-gated"]
        end

        BRIDGE["bridge (jobfit-bridge) — Ktor · 127.0.0.1:8765<br/>SQLite job queue (claim/result) + artifact API<br/>+ Postgres-backed /api/tracks"]

        DB[("db (jobfit-db)<br/>Postgres · :5432<br/>tracks / resume_tailoring")]

        FRONT["frontend (jobfit-frontend)<br/>nginx · :3030<br/>React 18 dashboard"]

        MARK["markserv (jobfit-markserv)<br/>:8081 · renders output dir<br/>(report.md, artifacts)"]

        NOTIFIER["notifier (jobfit-notifier)<br/>completed-event consumer<br/>Discord / Telegram"]
    end

    %% ---------------- Host worker ----------------
    subgraph HOST["Host — PM2"]
        direction TB
        PROC["jd-processor — processing pipeline (no Gmail)"]
        subgraph GRAPH["Pipeline graph"]
            direction TB
            N1["CheckDuplicate"] --> N2["ScoreFit"]
            N2 --> N3["ResumeTailoringSubgraph (6 nodes):<br/>JdExtraction · GapAnalysis · SummaryRewrite<br/>BulletRewrite · SkillsRestructure · AtsScoring"]
            N3 --> N4["GenerateCoverLetter"]
            N4 --> N5["RenderResumePdf<br/>(Playwright)"]
            N5 --> N6["AddArtifactUrl"]
            N6 --> N7["Track → Postgres"]
            N7 --> N8["postResult()"]
        end
        PROC --- GRAPH
    end

    subgraph DEPS["Host dependencies"]
        direction LR
        CDP["Chrome / CDP<br/>:9222"]
        MLX["MLX / oMLX<br/>:11436"]
        OLL["Ollama<br/>:11434"]
    end

    %% ---------------- Edges ----------------
    GM -->|IMAP/OAuth| POLLER
    JS_API --> JSEARCH
    EXT -->|submit JdRecord HTTP| BRIDGE
    FRONT -.->|browser| EXT

    POLLER -->|submit JdRecord| BRIDGE
    JSEARCH -->|submit JdRecord| BRIDGE

    PROC <-->|"poll claim() / postResult()<br/>http://127.0.0.1:8765"| BRIDGE
    N7 -->|JDBC| DB
    POLLER -.->|write-back: Gmail labels,<br/>recruiter draft| GM

    BRIDGE <-->|JDBC| DB
    FRONT -->|GET/POST /api/tracks| BRIDGE
    MARK -.->|reads output/ bind-mount| GRAPH
    BRIDGE -->|completed event stream| NOTIFIER
    NOTIFIER -->|alerts| CHAT["Discord / Telegram"]

    N3 --> MLX
    N3 --> OLL
    N1 --> CDP
    N2 --> MLX

    %% ---------------- Styling ----------------
    classDef container fill:#1f6feb22,stroke:#1f6feb,color:#c9d1d9;
    classDef host fill:#8957e522,stroke:#8957e5,color:#c9d1d9;
    classDef src fill:#23863622,stroke:#238636,color:#c9d1d9;
    classDef store fill:#9e6a0322,stroke:#d29922,color:#c9d1d9;

    class POLLER,JSEARCH,BRIDGE,FRONT,MARK,NOTIFIER container;
    class PROC,N1,N2,N3,N4,N5,N6,N7,N8,CDP,MLX,OLL host;
    class GM,EXT,JS_API src;
    class DB store;
```

## Reading the diagram

- **Intake** — three sources feed one queue. `poller` (Gmail) and `jsearch` (RapidAPI)
  are containers that `submit JdRecord` to the bridge; the Chrome extension submits
  directly from the browser.
- **Bridge** is the hub: a SQLite job queue (`claim()`/`result()`), an artifact API, and
  the Postgres-backed `/api/tracks` API the dashboard reads.
- **Host worker** (`jd-processor`, PM2) polls the bridge over loopback, runs the node
  graph (dedup → score → tailor → cover letter → PDF → track), and posts results back.
  It depends on Chrome/CDP for scraping and MLX/Ollama for the LLM nodes.
- **Write-back** — the Gmail poller applies labels and creates recruiter reply drafts
  (nothing is sent automatically).
- **Surfaces** — `frontend` (React dashboard), `markserv` (rendered reports), and
  `notifier` (Discord/Telegram from the completed-event stream). Every container binds
  host loopback only and is reached over the tailnet via Tailscale Serve.
