package com.jd.poller.gmail

import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.MessagePartBody
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EmailParser is pure MIME decoding — no Gmail auth/network. These build Gmail API [Message]
 * objects by hand and assert the decoded plain text / HTML / script extraction.
 */
@DisplayName("EmailParserTest")
class EmailParserTest {

    private fun b64(s: String): String = Base64.getUrlEncoder().encodeToString(s.toByteArray())

    private fun part(mimeType: String, data: String? = null, parts: List<MessagePart>? = null): MessagePart {
        val p = MessagePart().setMimeType(mimeType)
        if (data != null) p.body = MessagePartBody().setData(b64(data)).setSize(data.length)
        if (parts != null) p.parts = parts
        return p
    }

    private fun message(payload: MessagePart): Message = Message().setId("m1").setPayload(payload)

    @Test
    @DisplayName("decodes a text/plain body")
    fun decodesPlainText() {
        val parsed = EmailParser.parse(message(part("text/plain", "Hello recruiter, we have a role.")))
        assertEquals("Hello recruiter, we have a role.", parsed.plainText)
    }

    @Test
    @DisplayName("strips HTML tags and expands anchor hrefs to 'text url'")
    fun cleansHtmlAndExpandsLinks() {
        val html = """<p>Apply here: <a href="https://jobs.example.com/123">Staff SDET</a></p>"""
        val parsed = EmailParser.parse(message(part("text/html", html)))
        assertContains(parsed.plainText, "Apply here:")
        assertContains(parsed.plainText, "Staff SDET")
        assertContains(parsed.plainText, "https://jobs.example.com/123")   // href surfaced as text
        assertTrue(!parsed.plainText.contains("<a"), "tags should be stripped")
        assertEquals(1, parsed.htmlBodies.size)
    }

    @Test
    @DisplayName("multipart/alternative prefers the text/plain part")
    fun multipartPrefersPlainText() {
        val payload = part(
            "multipart/alternative",
            parts = listOf(
                part("text/html", "<p>ignore me</p>"),
                part("text/plain", "prefer this plain text"),
            ),
        )
        val parsed = EmailParser.parse(message(payload))
        assertEquals("prefer this plain text", parsed.plainText)
        assertEquals(1, parsed.htmlBodies.size)
    }

    @Test
    @DisplayName("collects inline scripts and script src URLs from HTML")
    fun collectsScripts() {
        val html = """
            <html><body>
              <script src="https://evil.example.com/track.js"></script>
              <script>window.__x = 1;</script>
              Legit content
            </body></html>
        """.trimIndent()
        val parsed = EmailParser.parse(message(part("text/html", html)))
        assertContains(parsed.scriptUrls, "https://evil.example.com/track.js")
        assertTrue(parsed.inlineScripts.any { it.contains("window.__x") }, "should capture inline script body")
    }

    @Test
    @DisplayName("empty payload yields empty plain text, not a crash")
    fun emptyPayloadIsSafe() {
        val parsed = EmailParser.parse(Message().setId("m2"))
        assertEquals("", parsed.plainText)
        assertTrue(parsed.htmlBodies.isEmpty())
    }

    @Test
    @DisplayName("part summary lists mime types with sizes")
    fun partSummaryListsParts() {
        val payload = part(
            "multipart/mixed",
            parts = listOf(part("text/plain", "hi"), part("text/html", "<p>hi</p>")),
        )
        val parsed = EmailParser.parse(message(payload))
        assertContains(parsed.partSummary, "multipart/mixed")
        assertContains(parsed.partSummary, "text/plain")
        assertContains(parsed.partSummary, "text/html")
    }
}
