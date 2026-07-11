package com.jd.pipeline.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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

    /**
     * Merge [incoming] into the persisted store instead of replacing it. Cookies are unioned by
     * (name, domain, path) with the incoming value winning on conflict, and existing cookies for
     * domains **absent** from [incoming] are KEPT. localStorage / sessionStorage / indexedDB take
     * the incoming array when it's non-empty, else the existing one.
     *
     * Why: each scrape batch re-exports only the context its session touched; a plain overwrite
     * would erode cookies for boards the batch didn't visit — including the shared Google-SSO
     * session that backs every board's login. Merging keeps them.
     */
    fun merge(incoming: JsonNode) {
        val existing = load()
        if (existing == null || !existing.isObject) {
            save(incoming)
            return
        }
        save(mergeContexts(existing, incoming))
    }

    private fun mergeContexts(existing: JsonNode, incoming: JsonNode): ObjectNode {
        val out = existing.deepCopy<ObjectNode>()
        // Cookies: union by (name|domain|path); incoming wins, existing-only kept.
        val byKey = LinkedHashMap<String, JsonNode>()
        (existing.get("cookies") as? ArrayNode)?.forEach { byKey[cookieKey(it)] = it }
        (incoming.get("cookies") as? ArrayNode)?.forEach { byKey[cookieKey(it)] = it }
        val cookies = mapper.createArrayNode()
        byKey.values.forEach { cookies.add(it) }
        out.set<JsonNode>("cookies", cookies)
        // Origin-keyed stores: prefer incoming when it carries data, else keep existing.
        for (field in listOf("localStorage", "sessionStorage", "indexedDB")) {
            val inc = incoming.get(field)
            if (inc != null && inc.isArray && inc.size() > 0) out.set<JsonNode>(field, inc)
        }
        return out
    }

    private fun cookieKey(c: JsonNode): String =
        "${c.path("name").asText()}|${c.path("domain").asText()}|${c.path("path").asText("/")}"

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
