package com.jd.jobbot.cli

import com.jd.jobbot.actions.ActionStore
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
    val taps = TapService(
        lookup = lookup,
        store = store,
        handlers = mapOf("apply" to ApplyNotImplemented),
        allowedUsers = Config.ALLOWED_USERS,
        handledVerbs = Config.HANDLED_VERBS,
        dryRun = Config.DRY_RUN,
        cardTtl = Duration.ofDays(Config.CARD_TTL_DAYS),
    )
    val status = StatusReport(bridge, store, Config.HANDLED_VERBS, Config.DRY_RUN)
    val tools = JobbotTools(
        lookup = lookup,
        bridge = bridge,
        files = OutputFiles(Config.OUTPUT_ROOT),
        profile = ProfileReader(Config.RESUME_YAML, Config.CANDIDATE_PROFILE_YAML),
        fitThreshold = Config.FIT_THRESHOLD,
        highFitScan = Config.HIGH_FIT_SCAN,
    )
    log.info("jobbot-mcp on :{} (dry_run={}, verbs={})", Config.PORT, Config.DRY_RUN, Config.HANDLED_VERBS)
    embeddedServer(CIO, port = Config.PORT) {
        jobbotModule(Config.API_TOKEN, Config.ALLOWED_HOSTS, taps, status, tools)
    }.start(wait = true)
}
