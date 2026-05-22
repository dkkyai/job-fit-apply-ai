package com.jd.pipeline.utils

import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Utility class for output directory operations.
 * Provides centralized logic for generating output directory paths.
 */
object OutputUtils {

    // Pre-compiled regexes for performance
    private val CONTROL_CHARS = Regex("[\u0000-\u001f\u007f]")
    private val UNSAFE_CHARS = Regex("[^\\w\\s.-]")
    private val UNDERSCORE_SPACES = Regex("[ _]+")

    // Windows reserved filenames — these names cannot be used as files or directories
    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * Sanitize a string for safe use as a filesystem path component (directory or filename).
     *
     * Rules:
     * - Remove characters unsafe for filesystems: / \ : * ? " < > | and control chars
     * - Replace newlines, tabs, and control characters with underscore
     * - Collapse multiple consecutive underscores/spaces into a single underscore
     * - Trim leading/trailing underscores and spaces
     * - Replace Windows reserved names with "unknown"
     * - If empty after sanitization, return "unknown"
     */
    fun sanitizeFileName(input: String, lowercase: Boolean = true): String {
        if (input.isBlank()) return "unknown"

        var result = input
            // Step 1: replace control characters (0x00-0x1f and 0x7f) with underscore
            .replace(CONTROL_CHARS, "_")
            // Step 2: replace filesystem-unsafe and non-alphanumeric chars (except space, hyphen, period)
            .replace(UNSAFE_CHARS, "_")
            // Step 3: collapse multiple spaces/underscores into single underscore
            .replace(UNDERSCORE_SPACES, "_")
            .trim('_')

        if (lowercase) {
            result = result.lowercase()
        }

        // Guard against Windows reserved filenames
        if (result.uppercase() in WINDOWS_RESERVED) {
            result = "unknown"
        }

        return result.ifBlank { "unknown" }
    }

    /**
     * Create an output path based on company and role title.
     * Format: output/yyyyMMdd_HHmmss_company_role
     */
    fun createOutputPath(company: String, roleTitle: String): Path {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val companySlug = sanitizeFileName(company)
        val roleSlug = sanitizeFileName(roleTitle)
        return Config.OUTPUT_DIR.resolve("${timestamp}_${companySlug}_${roleSlug}")
    }

    /**
     * Get the output directory path from JDState.
     * Returns the existing output path if set, otherwise creates a new one.
     */
    fun getOutputDirectory(state: JDState): Path {
        val outputPath = state.outputPath
        return if (outputPath.isNotEmpty()) {
            Path.of(outputPath)
        } else {
            createOutputPath(state.company, state.roleTitle)
        }
    }
}
