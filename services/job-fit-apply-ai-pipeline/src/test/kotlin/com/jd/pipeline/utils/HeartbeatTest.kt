package com.jd.pipeline.utils

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("HeartbeatTest")
class HeartbeatTest {

    @Test
    @DisplayName("ageMillis returns null when the file does not exist")
    fun ageMillisNullWhenMissing(@TempDir tempDir: Path) {
        val hb = Heartbeat(tempDir.resolve("nested/heartbeat"))
        assertNull(hb.ageMillis(1_000L))
    }

    @Test
    @DisplayName("isFresh is false when the file does not exist")
    fun isFreshFalseWhenMissing(@TempDir tempDir: Path) {
        val hb = Heartbeat(tempDir.resolve("heartbeat"))
        assertFalse(hb.isFresh(60_000L, 1_000L))
    }

    @Test
    @DisplayName("beat writes the timestamp and creates parent directories")
    fun beatWritesTimestamp(@TempDir tempDir: Path) {
        val path = tempDir.resolve("nested/dir/heartbeat")
        val hb = Heartbeat(path)
        hb.beat(5_000L)
        assertTrue(Files.exists(path))
        assertEquals("5000", Files.readString(path).trim())
    }

    @Test
    @DisplayName("ageMillis returns elapsed time since the last beat")
    fun ageMillisReturnsElapsed(@TempDir tempDir: Path) {
        val hb = Heartbeat(tempDir.resolve("heartbeat"))
        hb.beat(1_000L)
        assertEquals(4_000L, hb.ageMillis(5_000L))
    }

    @Test
    @DisplayName("ageMillis returns null when the file contents are unreadable as a long")
    fun ageMillisNullWhenCorrupt(@TempDir tempDir: Path) {
        val path = tempDir.resolve("heartbeat")
        Files.writeString(path, "not-a-number")
        val hb = Heartbeat(path)
        assertNull(hb.ageMillis(1_000L))
    }

    @Test
    @DisplayName("isFresh true/false boundaries around maxAgeMs and future skew")
    fun isFreshBoundaries(@TempDir tempDir: Path) {
        val hb = Heartbeat(tempDir.resolve("heartbeat"))
        hb.beat(10_000L)

        // age = 5000, within max 5000 -> fresh
        assertTrue(hb.isFresh(5_000L, 15_000L))
        // age = 5001, exceeds max 5000 -> stale
        assertFalse(hb.isFresh(5_000L, 15_001L))
        // small negative age (beat slightly in the future) within -1000 skew tolerance -> fresh
        assertTrue(hb.isFresh(5_000L, 9_500L))
        // large negative age beyond skew tolerance -> stale
        assertFalse(hb.isFresh(5_000L, 5_000L))
    }

    @Test
    @DisplayName("fromConfig builds a Heartbeat rooted at the given path string")
    fun fromConfigBuildsHeartbeat(@TempDir tempDir: Path) {
        val path = tempDir.resolve("heartbeat")
        val hb = Heartbeat.fromConfig(path.toString())
        hb.beat(2_000L)
        assertTrue(Files.exists(path))
    }
}
