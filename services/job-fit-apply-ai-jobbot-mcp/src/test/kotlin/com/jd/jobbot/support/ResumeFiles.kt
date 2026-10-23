package com.jd.jobbot.support

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Resume files for tests: minimal but structurally real PDFs and .docx archives. */
object ResumeFiles {
    fun pdf(extra: String = ""): ByteArray = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n$extra".toByteArray()

    fun docx(vararg entries: String = arrayOf("[Content_Types].xml", "word/document.xml")): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("<xml/>".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
}
