package com.jd.notifier.notify

import com.jd.notifier.config.Config
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import org.slf4j.LoggerFactory

/**
 * Registers an `Apply` button's label so the agent can resolve the tap.
 *
 * The agent's protocol is label-based: a tap arrives as a plain message containing the button's
 * text, and the agent looks that exact string up in its pending-actions file. So the notifier
 * must write the same label it sent — a mismatch means a tap that does nothing.
 *
 * ## Why the label is a hash, not `/`-separated text
 *
 * The obvious label (`Apply: Company / Title`) **collides** with the sweep's own reply labels
 * for recruiter-sourced jobs, where the sweep derives the label from the sender and subject.
 * Two different jobs can produce identical text, and since the agent keys its lookup on the
 * label, a collision would resolve one job's tap to another job's action — the wrong
 * application. Appending a short digest of the immutable job id makes the label unique while
 * staying inside Telegram's 64-byte `callback_data` cap.
 *
 * Writes are atomic and the file is re-read before each merge, because the sweep on the other
 * host owns this file too.
 */
open class ApplyRegistrar(
    private val enabled: Boolean = Config.APPLY_REGISTRAR_ENABLED,
    private val pendingPath: Path = Paths.get(Config.JOBBOT_PENDING_ACTIONS_PATH),
    private val ttlSeconds: Long = Config.APPLY_LABEL_TTL_SECONDS,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val log = LoggerFactory.getLogger(ApplyRegistrar::class.java)

    /** What we stored, for logging/tests. */
    data class Registration(val label: String, val path: Path?)

    /**
     * Record [label] → an `apply` action for [event]. Returns the label actually stored, or null
     * when registration is disabled or impossible.
     *
     * Never throws: losing an Apply registration must not cost the user the whole notification.
     */
    open fun register(
        label: String,
        dirName: String?,
        company: String?,
        title: String?,
        postingUrl: String?,
    ): Registration? {
        if (!enabled) return null
        val dir = dirName?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            mergeIntoFile(label, dir, company, title, postingUrl)
            Registration(label, pendingPath)
        } catch (e: Exception) {
            log.warn("could not register Apply label '{}': {}", label, e.message)
            null
        }
    }

    private fun mergeIntoFile(
        label: String,
        dirName: String,
        company: String?,
        title: String?,
        postingUrl: String?,
    ) {
        Files.createDirectories(pendingPath.parent)
        val existing = readJsonObject()
        pruneExpired(existing)
        existing[label] = linkedMapOf<String, Any?>(
            "type" to "apply",
            "source" to "jfaa-notifier",
            "dirname" to dirName,
            "company" to company,
            "title" to title,
            "url" to postingUrl,
            "action_id" to shortId(label),
            "label" to label,
            "created_at" to now(),
        )
        writeAtomically(existing)
    }

    /** Drop entries past their TTL so the file cannot grow without bound. */
    private fun pruneExpired(existing: MutableMap<String, Any?>) {
        val cutoff = now() - ttlSeconds
        val stale = existing.filter { (_, v) ->
            val created = (v as? Map<*, *>)?.get("created_at")
            val ts = when (created) {
                is Number -> created.toDouble()
                is String -> created.toDoubleOrNull()
                else -> null
            }
            ts != null && ts < cutoff
        }.keys
        stale.forEach { existing.remove(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readJsonObject(): MutableMap<String, Any?> {
        if (!Files.exists(pendingPath)) return mutableMapOf()
        val text = Files.readString(pendingPath).trim()
        if (text.isEmpty()) return mutableMapOf()
        return try {
            MAPPER.readValue(text, MutableMap::class.java) as MutableMap<String, Any?>
        } catch (e: Exception) {
            // A half-written file from the other writer must not erase their state; skip this one.
            log.warn("pending-actions unreadable, not merging: {}", e.message)
            throw IllegalStateException("pending-actions unreadable", e)
        }
    }

    private fun writeAtomically(obj: Map<String, Any?>) {
        val tmp = Files.createTempFile(pendingPath.parent, "pending", ".tmp")
        Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj))
        Files.move(tmp, pendingPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** 8 hex chars, matching the sweep's `uuid.uuid4().hex[:8]` action_id shape. */
    internal fun shortId(seed: String): String =
        seed.hashCode().toUInt().toString(16).padStart(8, '0').take(8)

    private companion object {
        val MAPPER: com.fasterxml.jackson.databind.ObjectMapper =
            com.fasterxml.jackson.databind.ObjectMapper()
    }
}
