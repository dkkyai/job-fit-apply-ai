package com.jd.pipeline.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persists a Steel `sessionContext` (cookies + localStorage + sessionStorage + indexedDB) to a
 * single JSON file on disk. Bind-mounted so the logged-in session survives Steel container and
 * profile restarts, and is portable/backup-able — the DIY equivalent of Steel Cloud's Profiles.
 *
 * Best-effort: every operation swallows failures (returns null / logs) so a missing or corrupt
 * store can never break scraping — the session just starts logged out, triggering a re-auth alert.
 */
class SteelStorageStore(private val path: Path) {
    private val log = LoggerFactory.getLogger(SteelStorageStore::class.java)
    private val mapper = ObjectMapper()

    /** Load the persisted context, or null if absent/unreadable (first run, cleared, or corrupt). */
    fun load(): JsonNode? = runCatching {
        if (Files.exists(path)) mapper.readTree(Files.readString(path)) else null
    }.getOrElse {
        log.warn("Could not read Steel storage state at {}: {}", path, it.message)
        null
    }

    /** Persist [context] atomically-ish (write then move). Best-effort; logs on failure. */
    fun save(context: JsonNode) {
        runCatching {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(tmp, mapper.writeValueAsString(context))
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            log.info("Persisted Steel storage state to {}", path)
        }.onFailure {
            log.warn("Failed to persist Steel storage state to {}: {}", path, it.message)
        }
    }
}
