package com.jd.jobbot.actions

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `items are delivered in order, once, with buttons and photos`() {
        Outbox(dir.resolve("o.db").toString(), dir.resolve("shots")).use { o ->
            val a = o.post("first")
            val b = o.post("second", listOf(InlineButton("✅ Submit", "submit:3")), byteArrayOf(1, 2))
            assertEquals(listOf(a, b), o.pending().map { it.id })
            assertEquals("submit:3", o.pending()[1].buttons.single().callbackData)
            assertTrue(o.pending()[1].hasPhoto)
            assertContentEquals(byteArrayOf(1, 2), o.photo(b))
            assertNull(o.photo(a))
            o.delivered(a)
            assertEquals(listOf(b), o.pending().map { it.id })
        }
    }

    @Test
    fun `long texts are capped so Telegram accepts them`() {
        Outbox(dir.resolve("o.db").toString(), dir.resolve("shots")).use { o ->
            o.post("x".repeat(10_000))
            assertEquals(Outbox.MAX_TEXT, o.pending().single().text.length)
        }
    }

    @Test
    fun `documents are queued with their name and delivered once`() {
        Outbox(dir.resolve("o.db").toString(), dir.resolve("shots")).use { o ->
            val text = o.post("plain")
            val doc = o.postDocument("Résumé for Acme", "Richard-Hatcher-Resume.pdf", byteArrayOf(37, 80, 68, 70))
            val item = o.pending().single { it.id == doc }
            assertTrue(item.hasDocument)
            assertEquals("Richard-Hatcher-Resume.pdf", item.documentName)
            assertEquals("Résumé for Acme", item.text)
            assertContentEquals(byteArrayOf(37, 80, 68, 70), o.document(doc))
            assertTrue(!o.pending().single { it.id == text }.hasDocument)
            assertNull(o.document(text))
            o.delivered(doc)
            assertEquals(listOf(text), o.pending().map { it.id })
        }
    }

    @Test
    fun `the queued file is a copy, unaffected by later changes to its source`() {
        val source = dir.resolve("source.pdf")
        java.nio.file.Files.write(source, byteArrayOf(1, 2, 3))
        Outbox(dir.resolve("o.db").toString(), dir.resolve("shots")).use { o ->
            val id = o.postDocument("x", "r.pdf", java.nio.file.Files.readAllBytes(source))
            java.nio.file.Files.write(source, byteArrayOf(9))
            assertContentEquals(byteArrayOf(1, 2, 3), o.document(id))
        }
    }

    @Test
    fun `an outbox database from before documents existed is upgraded in place`() {
        val db = dir.resolve("old.db").toString()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, buttons TEXT, photo TEXT, created_at INTEGER NOT NULL, delivered_at INTEGER)")
                it.execute("INSERT INTO outbox(text, buttons, created_at) VALUES ('queued before the upgrade', '[]', 1)")
            }
        }
        Outbox(db, dir.resolve("shots")).use { o ->
            assertEquals("queued before the upgrade", o.pending().single().text)
            o.postDocument("new", "a.pdf", byteArrayOf(1))
            assertEquals(2, o.pending().size)
        }
        // Opening it a second time must not trip over the columns that now exist.
        Outbox(db, dir.resolve("shots")).use { o -> assertEquals(2, o.pending().size) }
    }
}
