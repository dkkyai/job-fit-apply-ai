package com.jd.watchlist

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docker-entrypoint.sh drives the healthcheck: the container is healthy while the heartbeat file
 * is fresh. A full sweep takes ~10–15 min, longer than the healthcheck's start period, so the loop
 * must mark itself alive before the first sweep — not only after it (PR #140).
 */
class EntrypointTest {
    @TempDir lateinit var dir: Path

    private fun runEntrypoint(stubBody: String): Pair<Process, Path> {
        val heartbeat = dir.resolve("heartbeat")
        val marker = dir.resolve("marker")
        val stub = dir.resolve("watchlist-stub").also {
            Files.writeString(it, "#!/bin/sh\n$stubBody\n")
            it.toFile().setExecutable(true)
        }
        val proc = ProcessBuilder("sh", Path.of("docker-entrypoint.sh").toAbsolutePath().toString())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("out.log").toFile())
            .also {
                it.environment()["HEARTBEAT_FILE"] = heartbeat.toString()
                it.environment()["WATCHLIST_BIN"] = stub.toString()
                it.environment()["WATCHLIST_CHECK_INTERVAL_S"] = "3600"
                it.environment()["MARKER"] = marker.toString()
            }
            .start()
        return proc to marker
    }

    /**
     * Kill the loop, then its `sleep` child, so no orphaned sleep outlives the test. Loop first:
     * killing the sleep first lets the loop run another pass while @TempDir is being deleted.
     */
    private fun stop(proc: Process) {
        val children = proc.descendants().toList()
        proc.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        children.forEach { it.destroyForcibly(); it.onExit().get(5, TimeUnit.SECONDS) }
    }

    private fun awaitFile(path: Path, timeoutMs: Long = 10_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path) && Files.size(path) > 0) return Files.readString(path).trim()
            Thread.sleep(50)
        }
        error("timed out waiting for $path")
    }

    @Test
    fun `heartbeat exists before the first sweep runs`() {
        // The stub records whether the heartbeat was already there when the sweep started.
        val (proc, marker) = runEntrypoint("""if [ -f "${'$'}HEARTBEAT_FILE" ]; then echo present > "${'$'}MARKER"; else echo missing > "${'$'}MARKER"; fi""")
        try {
            assertEquals("present", awaitFile(marker))
        } finally {
            stop(proc)
        }
    }

    @Test
    fun `a failing sweep does not stop the loop and still refreshes the heartbeat`() {
        val (proc, marker) = runEntrypoint("""rm -f "${'$'}HEARTBEAT_FILE"; echo ran > "${'$'}MARKER"; exit 1""")
        try {
            assertEquals("ran", awaitFile(marker))
            val deadline = System.currentTimeMillis() + 10_000
            while (!Files.exists(dir.resolve("heartbeat")) && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(Files.exists(dir.resolve("heartbeat")), "the loop must touch the heartbeat after a failed --once")
            assertTrue(proc.isAlive, "the loop keeps running (now sleeping until the next tick)")
            assertTrue(Files.readString(dir.resolve("out.log")).contains("--once exited non-zero"))
        } finally {
            stop(proc)
        }
    }
}
