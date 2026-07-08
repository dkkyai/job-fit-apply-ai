package com.jd.pipeline.config

import io.github.cdimascio.dotenv.Dotenv
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Config - Central configuration for the JD pipeline.
 * All tunables live here; nodes import from this module.
 */
object Config {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private val PROJECT_DIR: Path = Paths.get(System.getProperty("user.dir"))

    // ── API Keys ────────────────────────────────────────────────────────────────
    val ANTHROPIC_API_KEY: String = get("ANTHROPIC_API_KEY", "")
    val LANGSMITH_API_KEY: String = get("LANGSMITH_API_KEY", "")
    val SUPABASE_PROJECT_URL: String = get("SUPABASE_PROJECT_URL", "")
    val SUPABASE_SERVICE_ROLE_KEY: String = get("SUPABASE_SERVICE_ROLE_KEY", get("SUPABASE_KEY", ""))

    // LLM concurrency gate (per physical resource). Local backends (oMLX + Ollama-local)
    // share ONE permit; cloud backends get a larger pool. See client/LlmGate.kt.
    val LOCAL_LLM_MAX_CONCURRENCY: Int = get("LOCAL_LLM_MAX_CONCURRENCY", "1").toInt()
    val CLOUD_LLM_MAX_CONCURRENCY: Int = get("CLOUD_LLM_MAX_CONCURRENCY", "4").toInt()

    // Database backend selection (Supabase → self-hosted Postgres migration).
    //   DB_BACKEND=supabase → SupabaseClient (REST/PostgREST, default)
    //   DB_BACKEND=postgres → PostgresGateway (direct JDBC to the container)
    val DB_BACKEND: String = get("DB_BACKEND", "supabase")
    // libpq-style URL; containers use host "db", host apps use "localhost".
    val DATABASE_URL: String = get("DATABASE_URL", "postgresql://jobfit:jobfit@localhost:5432/jobfit")
    val MINIMAX_API_KEY: String = get("MINIMAX_API_KEY", "")
    val GOOGLE_API_KEY: String = get("GOOGLE_API_KEY", "")
    val DEEPSEEK_API_KEY: String = get("DEEPSEEK_API_KEY", "")

    // ── oMLX (local MLX inference, OpenAI-compatible) ──────────────────────────────
    // Default backend for no-suffix model strings. Served by oMLX on this host.
    val MLX_LOCAL_BASE_URL: String = get("MLX_LOCAL_BASE_URL", "http://127.0.0.1:11436/v1")
    val MLX_API_KEY: String = get("MLX_API_KEY", "11436")

    // ── Ollama ───────────────────────────────────────────────────────────────────
    // Local Ollama endpoint (":ollama-local" suffix escape hatch).
    val OLLAMA_LOCAL_BASE_URL: String = get("OLLAMA_LOCAL_BASE_URL", "http://localhost:11434")
    // Ollama Cloud endpoint (:ollama-cloud suffix model strings).
    val OLLAMA_CLOUD_BASE_URL: String = get("OLLAMA_CLOUD_BASE_URL", "https://ollama.com")
    // Bearer token for Ollama Cloud. Leave empty for local-only pipelines.
    val OLLAMA_API_KEY: String = get("OLLAMA_API_KEY", "")

    // ── MiniMax cloud ─────────────────────────────────────────────────────────────
    val MINIMAX_BASE_URL: String = get("MINIMAX_BASE_URL", "https://api.minimaxi.chat/v1")

    // ── DeepSeek ─────────────────────────────────────────────────────────────────
    val DEEPSEEK_BASE_URL: String = get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")

    // ── Model configuration ───────────────────────────────────────────────────────
    // Scan / scrape: structured JSON extraction — small, fast, deterministic (temp=0)
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: deepseek-v4-flash:ollama-cloud
    val SCAN_MODEL: String = get("SCAN_MODEL", "Qwen3.5-9B-OptiQ-4bit")
    val SCRAPE_MODEL: String = get("SCRAPE_MODEL", SCAN_MODEL)

    // Score: rubric-based fit scoring — needs chain-of-thought reasoning (thinking enabled)
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: deepseek-v4-pro:ollama-cloud
    val SCORE_MODEL: String = get("SCORE_MODEL", "Qwen3.5-9B-OptiQ-4bit")

    // Cover letter: prose writing quality — a dedicated prose model pays off here
    // Best local (oMLX): gemma-4-12B-it-qat-4bit   Cloud: glm-5.1:ollama-cloud
    val COVER_LETTER_MODEL: String = get("COVER_LETTER_MODEL", "gemma-4-12B-it-qat-4bit")

    // Draft reply: short recruiter email — any capable small model is fine
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: deepseek-v4-flash:ollama-cloud
    val DRAFT_REPLY_MODEL: String = get("DRAFT_REPLY_MODEL", "Qwen3.5-9B-OptiQ-4bit")

    // Resume tailoring subgraph — reasoning (summary rewrite, bullet rewrite)
    // Creative (temp=0.4): prose quality matters
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: glm-5.1:ollama-cloud
    val RESUME_REASONING_MODEL: String = get("RESUME_REASONING_MODEL", "Qwen3.5-9B-OptiQ-4bit")
    // When false (default), /no_think is prepended to qwen3 reasoning calls to avoid 300s+ thinking timeouts.
    // Set to true only if the local model is fast enough to finish thinking within the 300s timeout.
    val RESUME_REASONING_THINKING: Boolean = get("RESUME_REASONING_THINKING", "false").toBoolean()

    // Skills restructure: judgment-heavy (category ordering, JD phrasing match) but factually grounded.
    // temp=0.2 — enough flexibility to follow multi-constraint instructions without hallucinating skills.
    // Best local (oMLX): Qwen3.5-9B-OptiQ-4bit   Cloud: deepseek-v4-pro:ollama-cloud
    val SKILLS_MODEL: String = get("SKILLS_MODEL", RESUME_REASONING_MODEL)

    // ── Scoring thresholds ───────────────────────────────────────────────────────
    val FIT_THRESHOLD: Float = get("FIT_THRESHOLD", "50").toFloat()
    // ATS refinement pass: when the validation report is actionable (missing supported
    // must-haves, leaked unsupported terms, doubled words) or the overall score lands below
    // the threshold, the subgraph re-runs the rewrite nodes once with the concrete gap list
    // in the prompts and keeps the better-scoring pass. Adds up to one extra rewrite/score
    // round per job (~several minutes on local models) — disable for latency-sensitive runs.
    val ATS_REFINE_ENABLED: Boolean = get("ATS_REFINE_ENABLED", "true").toBoolean()
    val ATS_REFINE_THRESHOLD: Int = get("ATS_REFINE_THRESHOLD", "80").toInt()
    // Bullet caps applied by the deterministic reorder node (recruiter-skim readability):
    // the most recent role keeps up to CAP_RECENT bullets, older roles CAP_OLDER. The
    // lowest-scoring bullets are dropped whole — text is never truncated.
    val TAILOR_BULLET_CAP_ENABLED: Boolean = get("TAILOR_BULLET_CAP_ENABLED", "true").toBoolean()
    val TAILOR_BULLET_CAP_RECENT: Int = get("TAILOR_BULLET_CAP_RECENT", "6").toInt()
    val TAILOR_BULLET_CAP_OLDER: Int = get("TAILOR_BULLET_CAP_OLDER", "3").toInt()
    // Duplicate detection: jobs seen within this window are considered duplicates.
    // Re-opened positions after the window expires are treated as new.
    val DUPLICATE_WINDOW_DAYS: Int = get("DUPLICATE_WINDOW_DAYS", "30").toInt()

    // Gmail intake/auth moved to the Poller service (Phase 1) — no Gmail config here.

    // ── Skills paths ─────────────────────────────────────────────────────────
    val SKILLS_DIR: Path = PROJECT_DIR.resolve("src/main/resources/skills")
    val SCAN_SKILL: Path = SKILLS_DIR.resolve("SCAN_SKILL.md")
    val SCRAPE_SKILL: Path = SKILLS_DIR.resolve("SCRAPE_SKILL.md")
    val SCORE_SKILL: Path = SKILLS_DIR.resolve("SCORE_SKILL.md")
    // Resume tailoring subgraph skill files — all under skills/tailor/, sharing one rubric
    val TAILOR_SKILLS_DIR: Path = SKILLS_DIR.resolve("tailor")
    /** Shared ATS/recruiter/HM-depth/integrity rules prepended to every tailor node prompt. */
    val RESUME_RUBRIC: Path = TAILOR_SKILLS_DIR.resolve("RESUME_RUBRIC.md")
    val JD_EXTRACTION_SKILL: Path = TAILOR_SKILLS_DIR.resolve("JD_EXTRACTION_SKILL.md")
    val GAP_ANALYSIS_SKILL: Path = TAILOR_SKILLS_DIR.resolve("GAP_ANALYSIS_SKILL.md")
    val SUMMARY_REWRITE_SKILL: Path = TAILOR_SKILLS_DIR.resolve("SUMMARY_REWRITE_SKILL.md")
    val BULLET_REWRITE_SKILL: Path = TAILOR_SKILLS_DIR.resolve("BULLET_REWRITE_SKILL.md")
    val SKILLS_RESTRUCTURE_SKILL: Path = TAILOR_SKILLS_DIR.resolve("SKILLS_RESTRUCTURE_SKILL.md")
    val ATS_VALIDATION_SKILL: Path = TAILOR_SKILLS_DIR.resolve("ATS_VALIDATION_SKILL.md")

    // ── ScanEmailTuner assets ────────────────────────────────────────────────
    val SCAN_EMAIL_TUNER_DIR: Path = PROJECT_DIR.resolve("tuner").resolve("scan-email-tuner")
    val SCAN_EMAIL_TUNER_DATASET_DIR: Path = SCAN_EMAIL_TUNER_DIR.resolve("data-set")
    val SCAN_EMAIL_TUNER_SKILL: Path = SCAN_EMAIL_TUNER_DIR.resolve("SCAN_EMAIL_TUNER_SKILL.md")

    // ── ScrapeJdUrlTuner assets ──────────────────────────────────────────────
    val SCRAPE_JD_URL_TUNER_DIR: Path = PROJECT_DIR.resolve("tuner").resolve("scrape-jd-url-tuner")
    val SCRAPE_JD_URL_TUNER_DATASET_DIR: Path = SCRAPE_JD_URL_TUNER_DIR.resolve("data-set")
    val SCRAPE_JD_URL_TUNER_SKILL: Path = SCRAPE_JD_URL_TUNER_DIR.resolve("SCRAPE_JD_URL_TUNER_SKILL.md")

    // ── Resume ───────────────────────────────────────────────────────────────────
    /** Canonical, candidate-authored résumé (structured YAML) — the source of all résumé content.
     *  Gitignored (personal). Override with the RESUME_YAML_PATH env var to point elsewhere. */
    val RESUME_YAML_PATH: Path = get("RESUME_YAML_PATH", "").let { override ->
        if (override.isNotBlank()) Paths.get(override)
        else PROJECT_DIR.resolve("src/main/resources/resume").resolve("resume.yaml")
    }
    /** Committed example résumé YAML — reference + `--init-profile` starting point. */
    val RESUME_YAML_TEMPLATE_PATH: Path = PROJECT_DIR.resolve("src/main/resources/resume").resolve("resume.template.yaml")
    /** Committed HTML head+CSS skeleton (with a `<!-- RESUME_BODY -->` sentinel) the deterministic renderer fills in. */
    val BASE_RESUME_TEMPLATE_PATH: Path = PROJECT_DIR.resolve("src/main/resources/resume").resolve("base_resume.template.html")
    /** Personal HTML résumé rendered from resume.yaml. Gitignored — produced by `--init-profile` / `--resume-gen`. */
    val GENERATED_RESUME_HTML_PATH: Path = PROJECT_DIR.resolve("src/main/resources/resume").resolve("generated_resume.html")

    // ── User Profile ─────────────────────────────────────────────────────────────
    /** Slim pipeline config (preferences + scoring aids). Gitignored — produced by `--init-profile`. */
    val CANDIDATE_PROFILE_PATH: Path = PROJECT_DIR.resolve("config").resolve("candidate_profile.yaml")

    // ── Output ───────────────────────────────────────────────────────────────────
    val OUTPUT_DIR: Path = PROJECT_DIR.resolve("output")

    // ── Playwright / Chrome ──────────────────────────────────────────────────────
    val PLAYWRIGHT_TIMEOUT_MS: Double = get("PLAYWRIGHT_TIMEOUT_MS", "45000").toDouble()
    val CHROME_EXECUTABLE_PATH: String = get(
        "CHROME_EXECUTABLE_PATH",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    )
    val CHROME_USER_DATA_DIR: String = get(
        "CHROME_USER_DATA_DIR",
        Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Google", "Chrome").toString()
    )
    val CHROME_PROFILE_DIRECTORY: String = get("CHROME_PROFILE_DIRECTORY", "Default")
    // CDP (Chrome DevTools Protocol) connection to a long-lived, user-launched Chrome.
    // When set (e.g. http://localhost:9222), the scraper connects to that already-running
    // Chrome and reuses its logged-in profile + warm session instead of launching a fresh,
    // profile-copied browser per job. Empty = disabled (legacy per-job launch path).
    // Launch the target Chrome with scripts/launch-chrome-cdp.sh.
    val CHROME_CDP_ENDPOINT: String = get("CHROME_CDP_ENDPOINT", "")
    // Remote-debugging port the launch script opens; also used to surface a helpful endpoint hint.
    val CHROME_DEBUG_PORT: String = get("CHROME_DEBUG_PORT", "9222")
    // Dedicated user-data-dir for the CDP debug Chrome (used by scripts/launch-chrome-cdp.sh).
    // Must NOT be the Default profile — current Chrome refuses remote debugging on the default dir
    // ("DevTools remote debugging requires a non-default data directory"). This profile can run
    // alongside your everyday Chrome; sign into the job boards in it once (the login persists here).
    val CHROME_CDP_USER_DATA_DIR: String = get(
        "CHROME_CDP_USER_DATA_DIR",
        Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Google", "Chrome-CDP").toString()
    )
    // Comma-separated domains that skip the HTTP fetch and scrape via the CDP browser directly
    // (proactive — for sites that soft-block or challenge plain HTTP, e.g. Glassdoor's Cloudflare).
    // Suffix match (a domain also matches its subdomains). Requires the debug Chrome to be up.
    val CDP_FORCE_DOMAINS: String = get("CDP_FORCE_DOMAINS", "")
    val PLAYWRIGHT_HEADLESS: Boolean = get("PLAYWRIGHT_HEADLESS", "false").toBoolean()
    // When true, sites blocked by HTTP (403, CAPTCHA, Cloudflare) are retried with a clean Playwright session.
    val PLAYWRIGHT_FALLBACK_ON_CAPTCHA: Boolean = get("PLAYWRIGHT_FALLBACK_ON_CAPTCHA", "true").toBoolean()
    // When true, pages that return fewer than PLAYWRIGHT_FALLBACK_MIN_CONTENT_LENGTH chars via HTTP
    // (JS-rendered SPAs like Jobright/Next.js) are retried with a clean Playwright session.
    val PLAYWRIGHT_FALLBACK_ON_THIN_CONTENT: Boolean = get("PLAYWRIGHT_FALLBACK_ON_THIN_CONTENT", "true").toBoolean()
    val PLAYWRIGHT_FALLBACK_MIN_CONTENT_LENGTH: Int = get("PLAYWRIGHT_FALLBACK_MIN_CONTENT_LENGTH", "500").toInt()

    // ── Candidate profile / résumé onboarding (--init-profile) ──────────────────
    // Résumé HTML is now rendered deterministically from resume.yaml (no LLM), and the
    // profile is authored as structured YAML — so RESUME_GEN / PROFILE_GEN models are gone.
    /** Committed slim YAML template for `candidate_profile.yaml` (scoring aids + preferences). */
    val CANDIDATE_PROFILE_TEMPLATE_PATH: Path = PROJECT_DIR.resolve("config").resolve("candidate_profile.template.yaml")

    // ── Digest LLM fallback ───────────────────────────────────────────────────────
    val DIGEST_LLM_FALLBACK_ENABLED: Boolean = get("DIGEST_LLM_FALLBACK_ENABLED", "true").toBoolean()
    val DIGEST_SKILL: Path = SKILLS_DIR.resolve("DIGEST_SKILL.md")

    // ── Notifications (Discord + Telegram) ───────────────────────────────────────
    val DISCORD_BOT_TOKEN: String  = get("DISCORD_BOT_TOKEN", "")
    val DISCORD_CHANNEL_ID: String = get("DISCORD_CHANNEL_ID", "")
    val TELEGRAM_BOT_TOKEN: String = get("TELEGRAM_BOT_TOKEN", "")
    val TELEGRAM_CHAT_ID: String   = get("TELEGRAM_CHAT_ID", "")
    val NOTIFICATION_FIT_THRESHOLD: Int = get("NOTIFICATION_FIT_THRESHOLD", "50").toInt()

    // ── Artifacts URL (Tailscale file server) ──────────────────────────────────────
    val ARTIFACT_BASE_URL: String = get("ARTIFACT_BASE_URL", "")
    // Local directory corresponding to the server's URL root (e.g., /path/to/your/markserv/output).
    val ARTIFACT_BASE_PATH: String = get("ARTIFACT_BASE_PATH", "")

    /**
     * Get environment variable with default.
     */
    private fun get(key: String, defaultValue: String): String {
        val value = System.getenv(key)
        return if (value.isNullOrEmpty()) {
            DOTENV[key] ?: defaultValue
        } else {
            value
        }
    }

    /**
     * Check if a required API key is configured.
     */
    fun hasApiKey(key: String): Boolean = !get(key, "").isEmpty()

    /**
     * Get required config or throw.
     */
    fun require(key: String): String {
        val value = get(key, "")
        require(value.isNotEmpty()) { "Required config missing: $key" }
        return value
    }
}
