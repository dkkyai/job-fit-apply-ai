package com.jd.watchlist.cli

import com.jd.watchlist.WatchlistRunner
import com.jd.watchlist.adapters.Adapters
import com.jd.watchlist.bridge.SubmitJobRequest
import com.jd.watchlist.bridge.SubmitJobResponse
import com.jd.watchlist.bridge.WatchlistBridgeClient
import com.jd.watchlist.config.Config
import com.jd.watchlist.filter.PostingFilter
import com.jd.watchlist.http.JdkHttp
import com.jd.watchlist.model.EmployerCatalog
import com.jd.watchlist.state.HealthReport
import com.jd.watchlist.state.RunState
import java.nio.file.Files
import java.nio.file.Path

/**
 * Watchlist ingestion entrypoint. A one-shot (`--once`) invoked by a shell loop, like jsearch: the
 * JVM lives only for the minutes of a sweep and exits. `--once` self-gates on the persisted
 * last-run, so frequent shell ticks are cheap no-ops.
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        when (parse(args)) {
            Command.Once -> runner(
                bridge = WatchlistBridgeClient(),
                state = RunState(Path.of(Config.STATE_FILE)),
                health = HealthReport(Path.of(Config.HEALTH_FILE)),
                intervalMs = Config.WATCHLIST_INTERVAL_MS,
            ).runOnce(System.currentTimeMillis())
            Command.DryRun -> {
                // Real feeds, no bridge, no gate, no persisted state: shows what a sweep would submit.
                val scratch = Files.createTempDirectory("watchlist-dry-run")
                runner(
                    bridge = DryRunBridgeClient(),
                    state = RunState(scratch.resolve("state.json")),
                    health = HealthReport(scratch.resolve("health.json")),
                    intervalMs = 0,
                ).runOnce(System.currentTimeMillis())
                println("[watchlist] dry run — nothing was submitted; health written to ${scratch.resolve("health.json")}")
            }
            Command.Usage -> printUsage()
        }
    }

    private fun runner(bridge: WatchlistBridgeClient, state: RunState, health: HealthReport, intervalMs: Long) =
        WatchlistRunner(
            employers = EmployerCatalog.load(Config.EMPLOYERS_FILE),
            adapters = Adapters.registry(JdkHttp(), Config.WORKDAY_SEARCH_TERMS),
            filter = PostingFilter(Config.TITLE_INCLUDE, Config.TITLE_EXCLUDE),
            bridge = bridge,
            state = state,
            health = health,
            intervalMs = intervalMs,
        )

    internal fun parse(args: Array<String>): Command = when {
        args.contains("--dry-run") -> Command.DryRun
        args.contains("--once") -> Command.Once
        else -> Command.Usage
    }

    private fun printUsage() {
        println(
            """
            Usage: watchlist <command>

              --once   Run one sweep over employers.yaml (self-gated: polls only if >= WATCHLIST_INTERVAL_MS
                       since the last run; otherwise a no-op). Invoked by the container's shell loop.
              --dry-run  Poll every feed now and print what would be submitted. Ignores the gate;
                         submits nothing and persists nothing.
            """.trimIndent()
        )
    }
}

enum class Command { Once, DryRun, Usage }

/** Prints each would-be submission instead of POSTing it. */
private class DryRunBridgeClient : WatchlistBridgeClient() {
    override fun submit(req: SubmitJobRequest): SubmitJobResponse {
        println("  would submit: ${req.roleTitle} @ ${req.company} [${req.location ?: "?"}] ${req.jdText.length} chars — ${req.jobUrl}")
        return SubmitJobResponse(jobId = "dry-run", status = "PENDING")
    }
}
