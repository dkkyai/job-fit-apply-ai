package com.jd.jobbot.cli

import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.AD_HOC_SEQ
import com.jd.jobbot.actions.IdVerbHandler
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.apply.ApplyService
import com.jd.jobbot.apply.CargoStatusListener
import com.jd.jobbot.cargo.CargoRepo
import com.jd.jobbot.cargo.ProcessGit
import com.jd.jobbot.mcp.CargoTools
import java.nio.file.Files
import com.jd.jobbot.apply.Credentials
import com.jd.jobbot.apply.FillExecutor
import com.jd.jobbot.apply.FillSessions
import com.jd.jobbot.hermes.HermesFillDriver
import com.jd.jobbot.hermes.HermesRuns
import com.jd.jobbot.mcp.FillTools
import com.jd.jobbot.apply.JobContext
import com.jd.jobbot.apply.PlaywrightApplyBrowser
import com.jd.jobbot.apply.Verifier
import com.jd.jobbot.bridge.str
import com.jd.jobbot.jobs.JobRef
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.jd.jobbot.actions.ArchiveSupport
import com.jd.jobbot.actions.ReplySupport
import com.jd.jobbot.actions.TailorSupport
import com.jd.jobbot.templates.AlertTemplates
import com.jd.jobbot.jobs.JobIdentity
import com.jd.jobbot.actions.VerbHandler
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.bridge.TailorRequester
import com.jd.jobbot.gmail.GmailAuth
import com.jd.jobbot.gmail.GmailClient
import com.jd.jobbot.actions.ApplyNotImplemented
import com.jd.jobbot.actions.StatusReport
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.config.Config
import com.jd.jobbot.files.OutputFiles
import com.jd.jobbot.http.jobbotModule
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.mcp.JobbotTools
import com.jd.jobbot.profile.ProfileReader
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Duration

fun main() {
    val log = LoggerFactory.getLogger("jobbot-mcp")
    if (Config.API_TOKEN.isBlank()) log.error("JOBBOT_MCP_TOKEN is blank — every /plugin and /mcp request will be refused")
    if (Config.ALLOWED_USERS.isEmpty()) log.error("JOBBOT_TELEGRAM_ALLOWED_USERS is blank — every tap will be refused")

    val bridge = BridgeReadClient(Config.BRIDGE_URL)
    val lookup = JobLookup(bridge)
    val store = ActionStore(Paths.get(Config.STATE_DIR, "actions.db").toString())
    val tracks = TrackWriter(Config.BRIDGE_URL)
    val gmailAuth = if (Config.GMAIL_TOKEN_FILE.isNotBlank() && Config.GMAIL_CREDENTIALS_FILE.isNotBlank()) {
        GmailAuth(Paths.get(Config.GMAIL_TOKEN_FILE), Paths.get(Config.GMAIL_CREDENTIALS_FILE))
    } else {
        log.warn("Gmail not configured (JOBBOT_GMAIL_TOKEN_FILE / JOBBOT_GMAIL_CREDENTIALS_FILE) — archive/undo and get_job_email are off")
        null
    }
    val gmail = gmailAuth?.let { GmailClient(it) }
    val replies = gmail?.let { ReplySupport(it, lookup, bridge, tracks, store, Config.SEND_ENABLED, Config.MAX_SENDS_PER_DAY) }
    val outbox = Outbox(Paths.get(Config.STATE_DIR, "outbox.db").toString(), Paths.get(Config.STATE_DIR, "screenshots"))
    val files = OutputFiles(Config.OUTPUT_ROOT)
    val profile = ProfileReader(Config.RESUME_YAML, Config.CANDIDATE_PROFILE_YAML)
    val credentials = Credentials(Paths.get(Config.CREDENTIALS_FILE), Config.ACCOUNT_EMAIL)
    val cargoRepo = if (Config.CARGO_TOKEN_FILE.isNotBlank()) {
        if (!Files.isRegularFile(Paths.get(Config.CARGO_TOKEN_FILE))) log.error("JOBBOT_CARGO_TOKEN_FILE {} is missing — the cargo repo can't be cloned or pushed", Config.CARGO_TOKEN_FILE)
        CargoRepo(Paths.get(Config.CARGO_DIR), Config.CARGO_REPO_URL, Config.CARGO_BRANCH, ProcessGit(Paths.get(Config.CARGO_TOKEN_FILE)))
    } else {
        log.warn("JOBBOT_CARGO_TOKEN_FILE not set — the jobbot-cargo tools are off")
        null
    }
    // STATUS.md lines are written and pushed off the request path: a git push must never hold up a tap.
    val cargoWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "cargo-status").apply { isDaemon = true } }
    if (Config.APPLY_ENABLED && Config.HERMES_API_KEY.isBlank()) log.error("JOBBOT_HERMES_API_KEY is blank — Hermes cannot be asked to plan application fills")
    val fillSessions = FillSessions()
    val verifier = gmail?.let { Verifier(it) }
    val applyService = if (Config.APPLY_ENABLED) {
        val fillDriver = HermesFillDriver(
            runs = HermesRuns(Config.HERMES_URL, Config.HERMES_API_KEY),
            sessions = fillSessions,
            executor = FillExecutor(credentials, verifier),
            model = Config.FILL_MODEL,
            maxWait = Duration.ofMinutes(Config.FILL_MAX_MINUTES),
            maxActCalls = Config.FILL_MAX_ACT_CALLS,
        )
        ApplyService(
            browser = PlaywrightApplyBrowser(Config.BROWSER_CDP_URL),
            fill = fillDriver::run,
            lookup = lookup,
            contextFor = { seq -> if (seq == AD_HOC_SEQ) adHocJobContext(profile) else jobContext(seq, lookup, bridge, files, profile) },
            store = store,
            outbox = outbox,
            tracks = tracks,
            viewerUrl = Config.BROWSER_VIEWER_URL.ifBlank { null },
            credentials = credentials,
            verifier = verifier,
            verifyWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "apply-verify").apply { isDaemon = true } },
            cargoStatus = cargoRepo?.let { repo ->
                object : CargoStatusListener {
                    override fun record(folder: String, state: String, note: String) {
                        cargoWriter.submit {
                            runCatching { repo.appendStatus(folder, state, note) }
                                .onSuccess { if (!it.pushed) store.recordError("cargo status $folder", it.message) }
                                .onFailure { store.recordError("cargo status $folder", it.message ?: it.javaClass.simpleName) }
                        }
                    }

                    override fun restore(folder: String, note: String) {
                        cargoWriter.submit {
                            runCatching { repo.restoreAfterInterruption(folder, note) }
                                .onSuccess { if (it != null && !it.pushed) store.recordError("cargo restore $folder", it.message) }
                                .onFailure { store.recordError("cargo restore $folder", it.message ?: it.javaClass.simpleName) }
                        }
                    }
                }
            },
            reviewTtl = Duration.ofHours(Config.APPLY_REVIEW_TTL_HOURS),
            reminderAfter = Duration.ofHours(Config.APPLY_REMINDER_HOURS),
        ).also { svc ->
            // Fills an earlier process left open can never finish: close them before any new one starts.
            runCatching { svc.recoverInterrupted() }.onFailure { log.warn("recovering interrupted fills failed: {}", it.message) }
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "apply-sweep").apply { isDaemon = true } }
                .scheduleAtFixedRate({ runCatching { svc.sweep() }.onFailure { log.warn("apply sweep failed: {}", it.message) } }, 1, 1, TimeUnit.MINUTES)
        }
    } else {
        null
    }
    val handlers = buildMap<String, VerbHandler> {
        put("apply", applyService?.apply ?: ApplyNotImplemented)
        gmail?.let {
            val archive = ArchiveSupport(it, lookup, tracks, Config.HANDLED_VERBS, store)
            put("archive", archive.archive)
            put("undo", archive.undo)
        }
        replies?.let { put("reply", it.reply) }
        put("tailor", TailorSupport(TailorRequester(Config.BRIDGE_URL), Config.HANDLED_VERBS).tailor)
    }
    val taps = TapService(
        lookup = lookup,
        store = store,
        handlers = handlers,
        allowedUsers = Config.ALLOWED_USERS,
        handledVerbs = Config.HANDLED_VERBS,
        dryRun = Config.DRY_RUN,
        cardTtl = Duration.ofDays(Config.CARD_TTL_DAYS),
        approvals = replies,
        idHandlers = applyService?.let {
            mapOf("submit" to IdVerbHandler(it::submit), "discard" to IdVerbHandler(it::discard), "resume" to IdVerbHandler(it::resume))
        } ?: emptyMap(),
        resumeApply = applyService?.applyWithResume,
    )
    val status = StatusReport(bridge, store, Config.HANDLED_VERBS, Config.DRY_RUN, gmailAuth)
    val tools = JobbotTools(
        lookup = lookup,
        bridge = bridge,
        files = files,
        profile = profile,
        fitThreshold = Config.FIT_THRESHOLD,
        highFitScan = Config.HIGH_FIT_SCAN,
        tracks = tracks,
        gmail = gmail,
        replies = replies,
        jobKeyOf = { seq -> lookup.event(seq)?.let { JobIdentity.of(it) } },
        templates = Config.TEMPLATE_FILE.takeIf { it.isNotBlank() }?.let { AlertTemplates(Paths.get(it)) },
        cargo = cargoRepo?.let { CargoTools(it, outbox, applyService, dryRun = Config.DRY_RUN) },
    )
    cargoRepo?.let { repo ->
        cargoWriter.submit {
            val s = repo.sync(force = true)
            if (s.ok) log.info("jobbot-cargo: {} ({} roles)", s.message, repo.packets().size) else log.error("jobbot-cargo sync failed: {}", s.message)
        }
    }
    log.info("jobbot-mcp on :{} (dry_run={}, verbs={})", Config.PORT, Config.DRY_RUN, Config.HANDLED_VERBS)
    embeddedServer(CIO, port = Config.PORT) {
        jobbotModule(
            Config.API_TOKEN, Config.ALLOWED_HOSTS, taps, status, tools,
            approvals = { id -> replies?.approvalPrompt(id) },
            outbox = outbox,
            accounts = if (Config.APPLY_ENABLED) ({ accountsText(credentials) }) else null,
            // Always served (its tools need the token of a live fill, so with Apply off it does nothing): Hermes is configured for it.
            fills = FillTools(fillSessions),
        )
    }.start(wait = true)
}

/** Everything the fill loop needs about one job; null when there is no posting URL to apply at. */
internal fun jobContext(seq: Long, lookup: JobLookup, bridge: BridgeReadClient, files: OutputFiles, profile: ProfileReader): JobContext? {
    val e = lookup.event(seq) ?: return null
    val url = e.str("job_url") ?: return null
    val artifact = e.str("artifact_url")
    fun file(name: String) = (files.read(artifact, name) as? OutputFiles.Result.Text)?.text
    val p = profile.read()
    return JobContext(
        ref = JobRef.format(seq), company = e.str("company"), title = e.str("role_title"), jobUrl = url,
        accountEmail = Config.ACCOUNT_EMAIL,
        profileYaml = listOfNotNull(p.candidateProfileYaml, p.resumeYaml).joinToString("\n---\n").ifBlank { null },
        resumeYaml = file("tailored_resume.yaml"),
        coverLetter = file("cover_letter.txt"),
        resumePdf = e.str("job_id")?.let { runCatching { bridge.resumePdf(it) }.getOrNull() },
    )
}

/**
 * The profile half of a pasted-link application. The posting URL, ref and resume are filled in by ApplyService;
 * there is no JFAA event, company, tailored resume or cover letter for a link Richard supplied himself.
 */
internal fun adHocJobContext(profile: ProfileReader): JobContext {
    val p = profile.read()
    return JobContext(
        ref = "", company = null, title = null, jobUrl = "", accountEmail = Config.ACCOUNT_EMAIL,
        profileYaml = listOfNotNull(p.candidateProfileYaml, p.resumeYaml).joinToString("\n---\n").ifBlank { null },
        resumeYaml = null, coverLetter = null, resumePdf = null,
    )
}

/** /jdaccounts: every site JobBot has an account on — never a password. */
internal fun accountsText(credentials: Credentials): String {
    val all = credentials.list()
    if (all.isEmpty()) return "JobBot hasn't created any site accounts yet."
    return "Site accounts (${all.size}):\n" + all.entries.joinToString("\n") { (site, e) ->
        "• $site — ${if (e.method == "google") "Sign in with Google" else "password"} (${e.status})" +
            (e.created_for?.let { " for $it" } ?: "") + ", ${e.created_at.take(10)}"
    }
}

