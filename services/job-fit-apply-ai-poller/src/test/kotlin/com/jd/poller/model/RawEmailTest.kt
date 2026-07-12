package com.jd.poller.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@DisplayName("RawEmailTest")
class RawEmailTest {

    private fun sample() = RawEmail(
        messageId = "m1",
        subject = "Staff SDET",
        from = "rec@firm.com",
        body = "plain body",
        htmlBody = "<p>plain body</p>",
        isRecruiterHint = true,
    )

    @Test
    @DisplayName("isRecruiterHint defaults to false when omitted")
    fun defaultsRecruiterHintFalse() {
        val email = RawEmail("m2", "s", "f", "b", "h")
        assertFalse(email.isRecruiterHint)
    }

    @Test
    @DisplayName("equals/hashCode are value-based")
    fun equalsAndHashCode() {
        val a = sample()
        val b = sample()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.copy(messageId = "different"))
    }

    @Test
    @DisplayName("copy overrides only the specified field")
    fun copyOverridesSingleField() {
        val a = sample()
        val copied = a.copy(subject = "New Title")
        assertEquals("New Title", copied.subject)
        assertEquals(a.messageId, copied.messageId)
        assertEquals(a.from, copied.from)
        assertEquals(a.body, copied.body)
        assertEquals(a.htmlBody, copied.htmlBody)
        assertEquals(a.isRecruiterHint, copied.isRecruiterHint)
    }

    @Test
    @DisplayName("toString includes the key identifying fields")
    fun toStringIncludesFields() {
        val s = sample().toString()
        assertTrue(s.contains("m1"))
        assertTrue(s.contains("Staff SDET"))
    }

    @Test
    @DisplayName("component destructuring matches constructor order")
    fun destructuring() {
        val (messageId, subject, from, body, htmlBody, isRecruiterHint) = sample()
        assertEquals("m1", messageId)
        assertEquals("Staff SDET", subject)
        assertEquals("rec@firm.com", from)
        assertEquals("plain body", body)
        assertEquals("<p>plain body</p>", htmlBody)
        assertTrue(isRecruiterHint)
    }
}
