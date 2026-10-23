package com.jd.jobbot.actions

import com.jd.jobbot.support.ResumeFiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResumeUploadTest {
    private fun ok(name: String?, bytes: ByteArray) = assertIs<ResumeUpload.Check.Ok>(ResumeUpload.validate(name, bytes)).upload
    private fun rejected(name: String?, bytes: ByteArray) = assertIs<ResumeUpload.Check.Rejected>(ResumeUpload.validate(name, bytes)).reason

    @Test
    fun `a real PDF is accepted under its own name`() {
        val pdf = ResumeFiles.pdf()
        val u = ok("Richard Hatcher - Resume.pdf", pdf)
        assertEquals("Richard Hatcher - Resume.pdf", u.fileName)
        assertEquals("application/pdf", u.mime)
        assertContentEquals(pdf, u.bytes)
    }

    @Test
    fun `a real docx is accepted with the Word mime type`() {
        val u = ok("resume_v2.docx", ResumeFiles.docx())
        assertEquals("resume_v2.docx", u.fileName)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", u.mime)
    }

    @Test
    fun `the extension match ignores case and the name keeps a lowercase extension`() {
        assertEquals("CV.pdf", ok("CV.PDF", ResumeFiles.pdf()).fileName)
        assertEquals("CV.docx", ok("CV.DocX", ResumeFiles.docx()).fileName)
    }

    @Test
    fun `a PDF with a few bytes before the header is still a PDF`() {
        ok("r.pdf", "\n\n  ".toByteArray() + ResumeFiles.pdf())
    }

    @Test
    fun `only pdf and docx are accepted`() {
        listOf("r.doc", "r.txt", "r.pages", "r.rtf", "r.exe", "r.html", "r.pdf.exe", "r", "").forEach { name ->
            assertTrue(rejected(name, ResumeFiles.pdf()).contains("PDF or a Word .docx"), name)
        }
        assertTrue(rejected(null, ResumeFiles.pdf()).contains("PDF or a Word .docx"))
    }

    @Test
    fun `a pdf extension on something that is not a PDF is refused`() {
        assertEquals("That file isn't a real PDF.", rejected("r.pdf", "MZ\u0090\u0000not a pdf".toByteArray()))
        assertEquals("That file isn't a real PDF.", rejected("r.pdf", ResumeFiles.docx()))
    }

    @Test
    fun `a docx extension on something that is not a Word file is refused`() {
        assertEquals("That file isn't a real .docx.", rejected("r.docx", ResumeFiles.pdf()))
        assertEquals("That file isn't a real .docx.", rejected("r.docx", "PK\u0003\u0004 garbage, not a zip directory".toByteArray()))
        assertEquals("That file isn't a real .docx.", rejected("r.docx", ResumeFiles.docx("notes.txt")))
        assertEquals("That file isn't a real .docx.", rejected("r.docx", ResumeFiles.docx("word/document.xml")))
    }

    @Test
    fun `a macro-enabled document renamed to docx is refused`() {
        val macro = ResumeFiles.docx("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin")
        assertTrue(rejected("r.docx", macro).contains("macros"))
    }

    @Test
    fun `an empty file and an oversize file are refused`() {
        assertEquals("That file is empty.", rejected("r.pdf", ByteArray(0)))
        val big = ResumeFiles.pdf() + ByteArray(ResumeUpload.MAX_BYTES)
        assertTrue(rejected("r.pdf", big).contains("over 10 MB"))
    }

    @Test
    fun `a file exactly at the size limit is accepted`() {
        val head = ResumeFiles.pdf()
        ok("r.pdf", head + ByteArray(ResumeUpload.MAX_BYTES - head.size))
    }

    @Test
    fun `file names lose paths and anything that is not plain text`() {
        assertEquals("evil.pdf", ResumeUpload.cleanName("../../etc/evil.pdf"))
        assertEquals("evil.pdf", ResumeUpload.cleanName("C:\\Users\\r\\evil.pdf"))
        assertEquals("a_b_c.pdf", ResumeUpload.cleanName("a<b>c.pdf"))
        assertEquals("Résumé (final).pdf", ResumeUpload.cleanName("Résumé (final).pdf"))
        assertEquals("resume.pdf", ResumeUpload.cleanName(".pdf"))
        assertEquals("a_ b.pdf", ResumeUpload.cleanName("a\u0000 b\u202E.pdf"))
    }

    @Test
    fun `a long name is shortened but keeps its extension`() {
        val name = ResumeUpload.cleanName("x".repeat(300) + ".docx")
        assertEquals(100, name.length)
        assertTrue(name.endsWith(".docx"))
    }

    @Test
    fun `an upload never holds a path separator`() {
        val u = ok("sub/dir\\r.pdf", ResumeFiles.pdf())
        assertTrue('/' !in u.fileName && '\\' !in u.fileName, u.fileName)
    }

    @Test
    fun `a docx with an absurd number of entries is refused`() {
        val names = (1..5_001).map { "word/media/image$it.png" }.toTypedArray() + arrayOf("[Content_Types].xml", "word/document.xml")
        assertEquals("That .docx is malformed.", rejected("r.docx", ResumeFiles.docx(*names)))
    }

    @Test
    fun `a docx with the real number of entries is fine`() {
        ok("r.docx", ResumeFiles.docx("[Content_Types].xml", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels", "docProps/core.xml"))
    }

    @Test
    fun `the PDF header is found anywhere in the first kilobyte and nowhere after`() {
        val pdf = ResumeFiles.pdf()
        ok("r.pdf", ByteArray(1019) { ' '.code.toByte() } + pdf)
        assertEquals("That file isn't a real PDF.", rejected("r.pdf", ByteArray(1100) { ' '.code.toByte() } + pdf))
    }

    @Test
    fun `names are kept readable and safe`() {
        assertEquals("resume.pdf", ResumeUpload.cleanName("..pdf"))
        assertEquals("My Résumé (2026).pdf", ResumeUpload.cleanName("  My Résumé (2026).pdf  "))
        assertEquals("a_b.pdf", ResumeUpload.cleanName("a|b.pdf"))
        assertEquals("日本語.pdf", ResumeUpload.cleanName("日本語.pdf"))
        assertEquals("resume.pdf", ResumeUpload.cleanName("///.pdf"))
        assertEquals("resume", ResumeUpload.cleanName(null))
    }

    @Test
    fun `the accepted upload does not alias the caller's buffer name or mime for other types`() {
        val u = ok("x.docx", ResumeFiles.docx())
        assertTrue(u.mime.endsWith("wordprocessingml.document"))
        assertTrue(ok("x.pdf", ResumeFiles.pdf()).mime == "application/pdf")
    }
}
