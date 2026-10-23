package com.jd.jobbot.actions

import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * A resume Richard sent from Telegram to use for one application instead of the tailored one.
 * Only a PDF or a Word .docx is accepted, and the bytes must really be one: the extension alone
 * proves nothing. The file is untrusted data — it is uploaded to the site's file input and never read.
 */
class ResumeUpload private constructor(val fileName: String, val mime: String, val bytes: ByteArray) {
    sealed interface Check {
        data class Ok(val upload: ResumeUpload) : Check
        data class Rejected(val reason: String) : Check
    }

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        private const val MAX_NAME = 100
        private const val MAX_ZIP_ENTRIES = 5_000
        private const val PDF_MIME = "application/pdf"
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val UNSAFE_NAME_CHARS = Regex("""[^\p{L}\p{N} ._()\-]""")

        fun validate(rawName: String?, bytes: ByteArray): Check {
            val name = cleanName(rawName)
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext != "pdf" && ext != "docx") return Check.Rejected("Only a PDF or a Word .docx resume can be used.")
            if (bytes.isEmpty()) return Check.Rejected("That file is empty.")
            if (bytes.size > MAX_BYTES) return Check.Rejected("That file is over ${MAX_BYTES / (1024 * 1024)} MB.")
            return when (ext) {
                "pdf" -> if (looksLikePdf(bytes)) Check.Ok(ResumeUpload(name, PDF_MIME, bytes)) else Check.Rejected("That file isn't a real PDF.")
                else -> docxProblem(bytes)?.let { Check.Rejected(it) } ?: Check.Ok(ResumeUpload(name, DOCX_MIME, bytes))
            }
        }

        /** The file name the site will see: the sender's own name, minus anything that is not plain text. */
        fun cleanName(raw: String?): String {
            val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
            val ext = base.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
            val stem = base.substringBeforeLast('.', base).replace(UNSAFE_NAME_CHARS, "_").trim(' ', '.', '_').ifBlank { "resume" }
            val room = (MAX_NAME - ext.length - 1).coerceAtLeast(1)
            return if (ext.isEmpty()) stem.take(MAX_NAME) else "${stem.take(room)}.$ext"
        }

        // Some generators put a few bytes before the header; the spec allows it within the first 1 KB.
        private fun looksLikePdf(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, 1024) - PDF_MAGIC.size
            return (0..limit).any { i -> PDF_MAGIC.indices.all { bytes[i + it] == PDF_MAGIC[it] } }
        }

        /** Null when [bytes] is a plain Word document; otherwise why not. Reads the zip directory only. */
        private fun docxProblem(bytes: ByteArray): String? {
            if (bytes.size < ZIP_MAGIC.size || !ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }) return "That file isn't a real .docx."
            val tmp = Files.createTempFile("jobbot-resume", ".docx")
            try {
                Files.write(tmp, bytes)
                ZipFile(tmp.toFile()).use { zip ->
                    if (zip.size() > MAX_ZIP_ENTRIES) return "That .docx is malformed."
                    val names = zip.entries().asSequence().map { it.name }.toSet()
                    if ("word/document.xml" !in names || "[Content_Types].xml" !in names) return "That file isn't a real .docx."
                    if (names.any { it.startsWith("word/vbaProject") }) return "That .docx contains macros, so I won't upload it."
                }
            } catch (e: java.io.IOException) {
                return "That file isn't a real .docx."
            } finally {
                Files.deleteIfExists(tmp)
            }
            return null
        }
    }
}
