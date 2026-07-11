package com.jd.pipeline.utils

import java.nio.file.Files
import java.nio.file.Path

/**
 * Liveness marker for the container healthcheck (same shape as the Poller's). The processor
 * loop calls [beat] each iteration, writing the current epoch-millis to a file; `--health`
 * reads it via [isFresh]. The path is injectable so tests don't touch a shared location.
 *
 * The freshness window must tolerate long jobs: the loop only beats between jobs, and a
 * queue of local-LLM jobs can hold one iteration for tens of minutes.
 */
class Heartbeat(private val path: Path) {

    /** Record a beat. Best-effort — a failed write must never crash the processor loop. */
    fun beat(now: Long = System.currentTimeMillis()) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, now.toString())
        }
    }

    /** Age of the last beat in ms, or null if the file is missing/unreadable. */
    fun ageMillis(now: Long): Long? {
        if (!Files.exists(path)) return null
        val ts = runCatching { Files.readString(path).trim().toLong() }.getOrNull() ?: return null
        return now - ts
    }

    /** True when the last beat is within [maxAgeMs] (and not in the future beyond a small skew). */
    fun isFresh(maxAgeMs: Long, now: Long): Boolean {
        val age = ageMillis(now) ?: return false
        return age in -1_000..maxAgeMs
    }

    companion object {
        fun fromConfig(path: String) = Heartbeat(Path.of(path))
    }
}
