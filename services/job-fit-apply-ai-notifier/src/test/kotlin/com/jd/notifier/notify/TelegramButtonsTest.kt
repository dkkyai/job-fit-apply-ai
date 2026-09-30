package com.jd.notifier.notify

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TelegramButtons (labels, byte caps, markup)")
class TelegramButtonsTest {

    @Test
    @DisplayName("Apply label stays within Telegram's 64-byte callback_data cap")
    fun applyLabelFitsByteCap() {
        val label = TelegramButtons.applyLabel(
            "Yorkshire Global Solutions Incorporated Holdings",
            "Senior Staff Software Development Engineer in Test, Mobile Platform",
        )
        assertTrue(
            label.toByteArray(Charsets.UTF_8).size <= TelegramButtons.CALLBACK_DATA_LIMIT,
            "label was ${label.toByteArray(Charsets.UTF_8).size} bytes: $label",
        )
        assertTrue(label.startsWith(TelegramButtons.APPLY_PREFIX))
    }

    @Test
    @DisplayName("byte cap counts UTF-8 bytes, not characters")
    fun byteCapNotCharCap() {
        // Each of these is 2 bytes; 40 chars = 80 bytes, so a char-based cap would overflow.
        val label = TelegramButtons.applyLabel("Ünïcödé Cömpany Ñame Ltd", "Rôle with áccents")
        assertTrue(label.toByteArray(Charsets.UTF_8).size <= 64, "got: $label")
    }

    @Test
    @DisplayName("truncation never splits a codepoint")
    fun noSplitCodepoint() {
        val fitted = TelegramButtons.fit("Apply: " + "é".repeat(60), 64)
        assertTrue(fitted.toByteArray(Charsets.UTF_8).size <= 64)
        // A split codepoint would surface as the replacement char.
        assertTrue(!fitted.contains('\uFFFD'), "replacement char in $fitted")
        assertTrue(fitted.none { it.code in 0x80..0xBF })
    }

    @Test
    @DisplayName("unique label differs for jobs that share company + title")
    fun uniqueDisambiguates() {
        val a = TelegramButtons.applyLabelUnique("Acme", "Staff SDET", TelegramButtons.discriminator("20260901_a"))
        val b = TelegramButtons.applyLabelUnique("Acme", "Staff SDET", TelegramButtons.discriminator("20260901_b"))
        assertTrue(a != b, "identical labels would let one job's tap resolve to the other's action")
        assertTrue(a.toByteArray(Charsets.UTF_8).size <= 64)
        assertTrue(b.toByteArray(Charsets.UTF_8).size <= 64)
    }

    @Test
    @DisplayName("unique label is stable for the same dirname")
    fun uniqueIsStable() {
        val d = TelegramButtons.discriminator("20260906_190952_qaunlocked_software_qa_engineer")
        assertEquals(d, TelegramButtons.discriminator("20260906_190952_qaunlocked_software_qa_engineer"))
    }

    @Test
    @DisplayName("unique label survives a maximally long dirname-derived job")
    fun uniqueFitsWorstCase() {
        val dir = "20260718_003402_" + "x".repeat(240)
        val label = TelegramButtons.applyLabelUnique(
            "Some Very Long Company Name Incorporated Holdings LLC",
            "Senior Staff Software Development Engineer in Test",
            TelegramButtons.discriminator(dir),
        )
        assertTrue(label.toByteArray(Charsets.UTF_8).size <= 64, "got ${label.toByteArray(Charsets.UTF_8).size}b: $label")
    }

    @Test
    @DisplayName("report + resume + apply produce two rows, links first")
    fun buildsRows() {
        val rows = TelegramButtons.forHighFit(
            reportUrl = "http://host:8081/job/report.md",
            resumeUrl = "http://host:8765/api/jobs/abc/resume.pdf",
            applyLabel = "Apply: Acme / Staff SDET #deadbeef",
        )
        assertEquals(2, rows.size)
        assertEquals(listOf("View Report", "View Resume"), rows[0].map { it.text })
        assertEquals(listOf("Apply"), rows[1].map { it.text })
        assertTrue(rows[0][0].url != null && rows[0][1].url != null)
        assertEquals("Apply: Acme / Staff SDET #deadbeef", rows[1][0].callbackData)
        assertNull(rows[1][0].url, "Apply must be a callback, not a URL")
    }

    @Test
    @DisplayName("missing links drop only that button")
    fun missingLinksDropButtons() {
        // Report absent -> the link row holds Resume only; Apply keeps its own row.
        val rows = TelegramButtons.forHighFit(null, "http://host/r.pdf", "Apply: A / B #1")
        assertEquals(2, rows.size)
        assertEquals(listOf("View Resume"), rows[0].map { it.text })
        assertEquals(listOf("Apply"), rows[1].map { it.text })

        val noApply = TelegramButtons.forHighFit("http://host/report.md", null, null)
        assertEquals(1, noApply.size)
        assertEquals(listOf("View Report"), noApply[0].map { it.text })
    }

    @Test
    @DisplayName("blank/whitespace URLs are treated as absent")
    fun blanksAreAbsent() {
        val rows = TelegramButtons.forHighFit("   ", "", "  ")
        assertTrue(rows.isEmpty(), "expected no buttons, got $rows")
    }

    @Test
    @DisplayName("markup JSON has Telegram's inline_keyboard shape and escapes text")
    fun markupShape() {
        val rows = TelegramButtons.forHighFit(
            "http://host/r.md",
            null,
            "Apply: Acme \"Quoted\" / R&D #abc12345",
        )
        val json = TelegramButtons.replyMarkupJson(rows)!!
        assertTrue(json.startsWith("{\"inline_keyboard\":[["), json)
        assertTrue(json.contains("\"url\":\"http://host/r.md\""), json)
        assertTrue(json.contains("\\\"Quoted\\\""), "quotes must be escaped: $json")
        assertTrue(json.contains("\\u0026") || json.contains("R&D"), json)
    }

    @Test
    @DisplayName("no buttons yields null markup rather than an empty keyboard")
    fun noRowsNoMarkup() {
        assertNull(TelegramButtons.replyMarkupJson(emptyList()))
    }
}
