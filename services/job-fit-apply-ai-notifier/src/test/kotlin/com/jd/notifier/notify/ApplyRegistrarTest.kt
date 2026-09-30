package com.jd.notifier.notify

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("ApplyRegistrar (agent hand-off for the Apply button)")
class ApplyRegistrarTest {

    private val mapper = ObjectMapper()

    private fun registrar(dir: Path, enabled: Boolean = true, now: () -> Long = { 1000L }) =
        ApplyRegistrar(
            enabled = enabled,
            pendingPath = dir.resolve("pending_actions.json"),
            ttlSeconds = 604800,
            now = now,
        )

    @Test
    @DisplayName("registers an apply action keyed by the exact label sent")
    fun registersLabel() {
        val dir = Files.createTempDirectory("jb")
        val reg = registrar(dir)
        val label = "Apply: Acme / Staff SDET #abc12345"
        val res = reg.register(label, "20260901_acme_staff_sdet", "Acme", "Staff SDET", "https://x/y")
        assertNotNull(res)
        assertEquals(label, res.label)

        val parsed = mapper.readTree(Files.readString(dir.resolve("pending_actions.json")))
        val entry = parsed.get(label)
        assertNotNull(entry, "agent looks the label up verbatim")
        assertEquals("apply", entry.get("type").asText())
        assertEquals("20260901_acme_staff_sdet", entry.get("dirname").asText())
        assertEquals(label, entry.get("label").asText())
        assertEquals("jfaa-notifier", entry.get("source").asText())
    }

    @Test
    @DisplayName("merges with existing sweep state instead of clobbering it")
    fun mergesExisting() {
        val dir = Files.createTempDirectory("jb")
        val path = dir.resolve("pending_actions.json")
        // The sweep (other host) already wrote its own registrations.
        Files.writeString(path, """{"Reply: Perchwell / Senior QA":{"type":"reply_highfit","created_at":999}}""")

        registrar(dir).register("Apply: X / Y #1", "dir1", "X", "Y", null)

        val parsed = mapper.readTree(Files.readString(path))
        assertNotNull(parsed.get("Reply: Perchwell / Senior QA"), "sweep entry must survive")
        assertNotNull(parsed.get("Apply: X / Y #1"))
    }

    @Test
    @DisplayName("prunes entries past TTL and keeps fresh ones")
    fun prunesExpired() {
        val dir = Files.createTempDirectory("jb")
        val path = dir.resolve("pending_actions.json")
        Files.writeString(
            path,
            """{"old":{"type":"reply","created_at":100},"fresh":{"type":"reply","created_at":950}}""",
        )
        // now=1000, ttl=604800 -> cutoff is negative, so neither is stale; use a tiny ttl instead.
        ApplyRegistrar(
            enabled = true,
            pendingPath = path,
            ttlSeconds = 100,
            now = { 1000L },
        ).register("Apply: A / B #2", "d", "A", "B", null)

        val parsed = mapper.readTree(Files.readString(path))
        assertNull(parsed.get("old"), "stale entry should be pruned")
        assertNotNull(parsed.get("fresh"), "fresh entry should survive")
        assertNotNull(parsed.get("Apply: A / B #2"))
    }

    @Test
    @DisplayName("disabled registrar writes nothing")
    fun disabledIsInert() {
        val dir = Files.createTempDirectory("jb")
        val res = registrar(dir, enabled = false).register("Apply: A / B #3", "d", "A", "B", null)
        assertNull(res)
        assertTrue(!Files.exists(dir.resolve("pending_actions.json")))
    }

    @Test
    @DisplayName("missing dirname means no registration")
    fun noDirnameNoRegistration() {
        val dir = Files.createTempDirectory("jb")
        assertNull(registrar(dir).register("Apply: A / B #4", null, "A", "B", null))
        assertNull(registrar(dir).register("Apply: A / B #5", "   ", "A", "B", null))
    }

    @Test
    @DisplayName("a corrupt pending-actions file does not erase the agent's state")
    fun corruptFileDoesNotClobber() {
        val dir = Files.createTempDirectory("jb")
        val path = dir.resolve("pending_actions.json")
        Files.writeString(path, "{ not json")
        // Registration fails soft (returns null) and leaves the file untouched.
        assertNull(registrar(dir).register("Apply: A / B #6", "d", "A", "B", null))
        assertEquals("{ not json", Files.readString(path))
    }

    @Test
    @DisplayName("creates the state directory when absent")
    fun createsParent() {
        val dir = Files.createTempDirectory("jb")
        val nested = dir.resolve("state/deep")
        val reg = ApplyRegistrar(
            enabled = true,
            pendingPath = nested.resolve("pending_actions.json"),
            ttlSeconds = 604800,
            now = { 1000L },
        )
        assertNotNull(reg.register("Apply: A / B #7", "d", "A", "B", null))
        assertTrue(Files.exists(nested.resolve("pending_actions.json")))
    }

    @Test
    @DisplayName("action_id is 8 hex chars, matching the sweep's shape")
    fun actionIdShape() {
        val id = registrar(Files.createTempDirectory("jb")).shortId("Apply: Acme / Staff SDET #abc")
        assertEquals(8, id.length)
        assertTrue(id.all { it in "0123456789abcdef" }, id)
    }
}
