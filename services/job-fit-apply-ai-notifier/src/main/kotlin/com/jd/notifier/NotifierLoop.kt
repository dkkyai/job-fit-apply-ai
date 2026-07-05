package com.jd.notifier

import com.jd.notifier.bridge.NotifierBridgeClient
import com.jd.notifier.config.Config
import com.jd.notifier.health.Heartbeat
import com.jd.notifier.notify.Notifier
import com.jd.notifier.state.Cursor

/**
 * Drains the bridge's completed-event stream and notifies. Tracks a client-side cursor; on cold
 * start it seeds the cursor at the bridge head so it doesn't re-notify job history. At-least-once:
 * a crash mid-batch re-sends a few messages on restart (harmless for chat pings).
 */
class NotifierLoop(
    private val bridge: NotifierBridgeClient,
    private val notifier: Notifier,
    private val cursor: Cursor,
    private val heartbeat: Heartbeat? = null,
) {
    /** Return the current cursor, seeding it at the bridge head on cold start (skip history). */
    fun ensureCursor(): Long {
        cursor.read()?.let { return it }
        val head = bridge.headSeq()
        cursor.write(head)
        println("[notifier] cold start — seeding cursor at head seq $head (skipping history)")
        return head
    }

    /** One drain pass: page through events > cursor, notifying each and advancing the cursor. */
    fun drainOnce(pageSize: Int = 50): Int {
        var cur = ensureCursor()
        var sent = 0
        while (true) {
            val events = bridge.fetchEvents(cur, limit = pageSize)
            if (events.isEmpty()) break
            for (e in events) {
                runCatching { if (notifier.notify(e)) sent++ }
                    .onFailure { System.err.println("[notifier] notify failed for ${e.jobId}: ${it.message}") }
                cur = e.completedSeq
                cursor.write(cur)   // advance after each — at-least-once on crash
            }
            if (events.size < pageSize) break
        }
        return sent
    }

    fun runForever(intervalMs: Long = Config.POLL_INTERVAL_MS) {
        while (!Thread.currentThread().isInterrupted) {
            runCatching { drainOnce() }.onFailure { System.err.println("[notifier] drain error: ${it.message}") }
            heartbeat?.beat(System.currentTimeMillis())
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
