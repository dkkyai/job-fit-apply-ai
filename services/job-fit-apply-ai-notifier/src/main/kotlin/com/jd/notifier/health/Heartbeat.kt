package com.jd.notifier.health

import java.nio.file.Files
import java.nio.file.Path

/** Liveness marker for the container healthcheck (touched each loop iteration; `--health` reads it). */
class Heartbeat(private val path: Path) {

    fun beat(now: Long) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, now.toString())
        }
    }

    fun ageMillis(now: Long): Long? {
        if (!Files.exists(path)) return null
        val ts = runCatching { Files.readString(path).trim().toLong() }.getOrNull() ?: return null
        return now - ts
    }

    fun isFresh(maxAgeMs: Long, now: Long): Boolean {
        val age = ageMillis(now) ?: return false
        return age in -1_000..maxAgeMs
    }

    companion object {
        fun fromConfig(path: String) = Heartbeat(Path.of(path))
    }
}
