package com.jd.jsearch.cli

import com.jd.jsearch.IngestRunner
import com.jd.jsearch.bridge.JsearchBridgeClient
import com.jd.jsearch.client.JSearchClient
import com.jd.jsearch.config.Config
import com.jd.jsearch.state.RunState
import java.nio.file.Path

/**
 * JSearch ingestion entrypoint. Designed as a one-shot (`--once`) invoked by a shell loop, so the
 * JVM only lives for the ~5s of a run and exits — steady-state memory is the sleeping shell, not a
 * 24/7 JVM. `--once` self-gates on the persisted last-run, so frequent shell ticks are cheap no-ops.
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        when (parse(args)) {
            Command.Once -> {
                val runner = IngestRunner(
                    clientFactory = { JSearchClient() },
                    bridge = JsearchBridgeClient(),
                    state = RunState(Path.of(Config.STATE_FILE)),
                )
                runner.runOnce(System.currentTimeMillis())
            }
            Command.Usage -> printUsage()
        }
    }

    internal fun parse(args: Array<String>): Command =
        if (args.contains("--once")) Command.Once else Command.Usage

    private fun printUsage() {
        println(
            """
            Usage: jsearch <command>

              --once   Run one ingest pass (self-gated: fetches only if >= JSEARCH_INTERVAL_MS
                       since the last run; otherwise a no-op). Invoked by the container's shell loop.
            """.trimIndent()
        )
    }
}

enum class Command { Once, Usage }
