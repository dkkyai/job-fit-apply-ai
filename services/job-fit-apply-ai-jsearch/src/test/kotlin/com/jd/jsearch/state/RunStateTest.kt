package com.jd.jsearch.state

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("RunState")
class RunStateTest {

    @Test
    @DisplayName("lastRun is null before any run")
    fun nullWhenAbsent(@TempDir dir: Path) {
        assertNull(RunState(dir.resolve("jsearch.json")).lastRun())
    }

    @Test
    @DisplayName("recordRun then lastRun round-trips the timestamp")
    fun roundTrip(@TempDir dir: Path) {
        val s = RunState(dir.resolve("jsearch.json"))
        s.recordRun(1_725_000_000_000L)
        assertEquals(1_725_000_000_000L, s.lastRun())
    }

    @Test
    @DisplayName("recordRun creates parent directories")
    fun createsParents(@TempDir dir: Path) {
        val s = RunState(dir.resolve("nested/state/jsearch.json"))
        s.recordRun(42L)
        assertEquals(42L, s.lastRun())
    }

    @Test
    @DisplayName("a corrupt state file reads as null, not a crash")
    fun corruptIsNull(@TempDir dir: Path) {
        val p = dir.resolve("jsearch.json")
        java.nio.file.Files.writeString(p, "not json {")
        assertNull(RunState(p).lastRun())
    }
}
