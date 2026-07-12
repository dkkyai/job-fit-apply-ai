package com.jd.notifier.health

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Heartbeat")
class HeartbeatTest {

    @Test
    @DisplayName("ageMillis is null before any beat (no file)")
    fun ageNullBeforeBeat(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        assertNull(hb.ageMillis(System.currentTimeMillis()))
    }

    @Test
    @DisplayName("isFresh is false before any beat")
    fun notFreshBeforeBeat(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        assertFalse(hb.isFresh(120_000L, System.currentTimeMillis()))
    }

    @Test
    @DisplayName("beat then ageMillis reflects elapsed time")
    fun beatThenAge(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        hb.beat(1_000L)
        assertEquals(500L, hb.ageMillis(1_500L))
    }

    @Test
    @DisplayName("beat creates parent directories")
    fun beatCreatesParentDirs(@TempDir dir: Path) {
        val path = dir.resolve("nested/sub/hb")
        val hb = Heartbeat(path)
        hb.beat(42L)
        assertTrue(Files.exists(path))
    }

    @Test
    @DisplayName("isFresh true when age is within maxAgeMs")
    fun freshWithinMax(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        hb.beat(1_000L)
        assertTrue(hb.isFresh(1_000L, 1_500L))   // age=500 <= max=1000
    }

    @Test
    @DisplayName("isFresh false when age exceeds maxAgeMs")
    fun staleBeyondMax(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        hb.beat(1_000L)
        assertFalse(hb.isFresh(100L, 5_000L))   // age=4000 > max=100
    }

    @Test
    @DisplayName("isFresh true for slightly-future timestamps (clock skew tolerance)")
    fun freshForSmallClockSkew(@TempDir dir: Path) {
        val hb = Heartbeat(dir.resolve("hb"))
        hb.beat(2_000L)
        // now < recorded ts by 500ms — within the -1_000 tolerance
        assertTrue(hb.isFresh(120_000L, 1_500L))
    }

    @Test
    @DisplayName("corrupt heartbeat file yields null age / not fresh")
    fun corruptFile(@TempDir dir: Path) {
        val path = dir.resolve("hb")
        Files.writeString(path, "not-a-number")
        val hb = Heartbeat(path)
        assertNull(hb.ageMillis(System.currentTimeMillis()))
        assertFalse(hb.isFresh(120_000L, System.currentTimeMillis()))
    }

    @Test
    @DisplayName("fromConfig builds a Heartbeat rooted at the given path")
    fun fromConfigBuildsAtPath(@TempDir dir: Path) {
        val target = dir.resolve("cfg-hb")
        val hb = Heartbeat.fromConfig(target.toString())
        hb.beat(10L)
        assertTrue(Files.exists(target))
    }
}
