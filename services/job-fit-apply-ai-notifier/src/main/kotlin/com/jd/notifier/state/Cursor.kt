package com.jd.notifier.state

import java.nio.file.Files
import java.nio.file.Path

/**
 * Persisted consumer cursor: the max `completed_seq` already notified. Lives on a small mounted
 * volume so restarts resume where they left off (rather than re-notifying history). Best-effort.
 */
class Cursor(private val path: Path) {

    /** Last notified seq, or null if never set (cold start). */
    fun read(): Long? {
        if (!Files.exists(path)) return null
        return runCatching { Files.readString(path).trim().toLong() }.getOrNull()
    }

    fun write(seq: Long) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, seq.toString())
        }.onFailure { System.err.println("[cursor] failed to write $path: ${it.message}") }
    }
}
