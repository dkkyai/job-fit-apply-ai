package com.jd.jobbot.files

import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reads a job's pipeline output files, read-only, from the mounted pipeline-output root.
 *
 * The folder comes from the tail of the event's artifact_url, and a name must be on [ALLOWED]:
 * the model picks the name, so the path must never be free-form. The resolved path is checked
 * against the root after canonicalization so `..` and symlinks cannot escape it.
 */
class OutputFiles(root: String, private val maxBytes: Int = 64 * 1024) {
    private val root: Path = Paths.get(root).toAbsolutePath().normalize()

    sealed interface Result {
        data class Text(val name: String, val text: String, val truncated: Boolean) : Result
        data class Missing(val reason: String) : Result
    }

    fun folderOf(artifactUrl: String?): String? =
        artifactUrl?.trim()?.trimEnd('/')?.substringAfterLast('/')
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it }

    fun available(artifactUrl: String?): List<String> {
        val dir = dirOf(artifactUrl) ?: return emptyList()
        return ALLOWED.filter { Files.isRegularFile(dir.resolve(it)) }
    }

    fun read(artifactUrl: String?, name: String): Result {
        if (name !in ALLOWED) return Result.Missing("'$name' is not a readable job file; allowed: ${ALLOWED.joinToString()}")
        val dir = dirOf(artifactUrl) ?: return Result.Missing("this job has no output folder")
        val file = dir.resolve(name)
        if (!Files.isRegularFile(file)) return Result.Missing("$name does not exist for this job")
        val real = file.toRealPath()
        if (!real.startsWith(root.toRealPath())) return Result.Missing("$name resolves outside the output root")
        val bytes = Files.newInputStream(real).use { it.readNBytes(maxBytes + 1) }
        val truncated = bytes.size > maxBytes
        val text = String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8)
        return Result.Text(name, text, truncated)
    }

    private fun dirOf(artifactUrl: String?): Path? {
        val folder = folderOf(artifactUrl) ?: return null
        val dir = root.resolve(folder).normalize()
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) return null
        return dir
    }

    companion object {
        val ALLOWED = listOf(
            "report.md", "score_fit.txt", "cover_letter.txt", "metadata.json", "meta.json",
            "job_description.txt", "gap_analysis.json", "ats_report.txt", "tailored_summary.txt",
            "tailored_bullets.txt", "tailored_resume.yaml",
        )
    }
}
