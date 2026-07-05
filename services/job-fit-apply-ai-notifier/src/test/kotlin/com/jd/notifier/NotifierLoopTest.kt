package com.jd.notifier

import com.jd.notifier.bridge.CompletedEvent
import com.jd.notifier.bridge.NotifierBridgeClient
import com.jd.notifier.notify.Notifier
import com.jd.notifier.state.Cursor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import kotlin.test.assertEquals

@DisplayName("NotifierLoop")
class NotifierLoopTest {

    private fun ev(seq: Long) = CompletedEvent(jobId = "j$seq", completedSeq = seq, company = "C", roleTitle = "R", fitScore = 50)

    @Test
    @DisplayName("cold start seeds the cursor at the bridge head and fetches from there")
    fun coldStartSeedsHead(@TempDir dir: Path) {
        val bridge = mock<NotifierBridgeClient> {
            on { headSeq() } doReturn 100L
            on { fetchEvents(any(), any()) } doReturn emptyList()
        }
        val cursor = Cursor(dir.resolve("cur"))
        NotifierLoop(bridge, mock<Notifier>(), cursor).drainOnce()

        assertEquals(100L, cursor.read(), "cold-start cursor seeded at head")
        verify(bridge).fetchEvents(eq(100L), any())   // fetched since head, not 0
    }

    @Test
    @DisplayName("existing cursor is used as-is (no head reseed)")
    fun existingCursorNotReseeded(@TempDir dir: Path) {
        val cursor = Cursor(dir.resolve("cur")).apply { write(5L) }
        val bridge = mock<NotifierBridgeClient> { on { fetchEvents(any(), any()) } doReturn emptyList() }
        NotifierLoop(bridge, mock<Notifier>(), cursor).drainOnce()

        verify(bridge, never()).headSeq()
        verify(bridge).fetchEvents(eq(5L), any())
    }

    @Test
    @DisplayName("notifies each event and advances the cursor to the last seq")
    fun notifiesAndAdvances(@TempDir dir: Path) {
        val cursor = Cursor(dir.resolve("cur")).apply { write(5L) }
        val bridge = mock<NotifierBridgeClient> {
            on { fetchEvents(eq(5L), any()) } doReturn listOf(ev(6), ev(7))   // 2 < pageSize → one page
        }
        val notifier = mock<Notifier> { on { notify(any()) } doReturn true }

        val sent = NotifierLoop(bridge, notifier, cursor).drainOnce()

        assertEquals(2, sent)
        verify(notifier, times(2)).notify(any())
        assertEquals(7L, cursor.read(), "cursor advanced to the last event's seq")
    }

    @Test
    @DisplayName("pages through multiple full pages until a short page ends the drain")
    fun paginates(@TempDir dir: Path) {
        val cursor = Cursor(dir.resolve("cur")).apply { write(0L) }
        val bridge = mock<NotifierBridgeClient>()
        whenever(bridge.fetchEvents(eq(0L), eq(2))).doReturn(listOf(ev(1), ev(2)))   // full page
        whenever(bridge.fetchEvents(eq(2L), eq(2))).doReturn(listOf(ev(3)))          // short → stop
        val notifier = mock<Notifier> { on { notify(any()) } doReturn true }

        val sent = NotifierLoop(bridge, notifier, cursor).drainOnce(pageSize = 2)

        assertEquals(3, sent)
        verify(notifier, times(3)).notify(any())
        assertEquals(3L, cursor.read())
    }
}
