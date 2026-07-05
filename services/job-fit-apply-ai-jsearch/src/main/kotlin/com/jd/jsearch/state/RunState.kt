package com.jd.jsearch.state

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persists the last successful run time so restarts don't re-fetch (and burn API quota). Lives on a
 * small mounted volume. Best-effort — a read/write failure must not crash the run.
 */
class RunState(private val path: Path) {
    private val mapper = ObjectMapper()

    /** Epoch-millis of the last successful fetch, or null if never run / unreadable. */
    fun lastRun(): Long? {
        if (!Files.exists(path)) return null
        return runCatching { mapper.readTree(Files.readString(path)).path("last_run").asLong(0L).takeIf { it > 0 } }
            .getOrNull()
    }

    fun recordRun(now: Long) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, """{"last_run":$now}""")
        }.onFailure { System.err.println("[state] failed to write $path: ${it.message}") }
    }
}
