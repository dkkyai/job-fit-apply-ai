package com.jd.pipeline.utils

import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest

/**
 * Data class representing column widths for job output formatting.
 */
data class ColumnWidths(
    val company: Int,
    val title: Int,
    val fit: Int = 8,       // "Fit: N/A" length
    val artifact: Int
) {
    companion object {
        fun default(): ColumnWidths = ColumnWidths(
            company = 10,
            title = 20,
            fit = 8,
            artifact = 30
        )
    }
}

/**
 * Utility object for formatting job output in aligned columns.
 */
object JobFormatter {

    /**
     * Enum representing skip reason codes for jobs that were not scored.
     */
    enum class SkipReasonCode(val abbreviation: String) {
        DUPLICATE("Dupe"),
        NO_JD("NoJD"),
        LOW_FIT("LowFit"),
        UNKNOWN("?");

        companion object {
            /**
             * Maps a JDState to a skip reason code if the job was skipped.
             * Returns null if the job has a valid fit score.
             */
            fun fromState(state: JDState): SkipReasonCode? {
                if (state.fitScore != null) return null
                return when {
                    state.isDuplicate -> DUPLICATE
                    state.skippedReason.contains("No JD text", ignoreCase = true) -> NO_JD
                    state.skippedReason.contains("Fit score below threshold", ignoreCase = true) -> LOW_FIT
                    state.skippedReason.isNotEmpty() -> UNKNOWN
                    else -> null
                }
            }
        }
    }

    private const val SEPARATOR = " | "
    private const val SEPARATOR_LENGTH = 3
    private const val DEFAULT_URL_MAX_LENGTH = 40

    /**
     * Compute column widths based on the maximum length of each field across all jobs.
     */
    fun computeColumnWidths(jobs: List<JDState>): ColumnWidths {
        var maxCompany = 10
        var maxTitle = 20
        var maxFit = 8
        var maxArtifact = 30

        for (job in jobs) {
            val company = job.company.ifBlank { "Unknown Company" }
            val title = job.roleTitle.ifBlank { "Unknown Role" }
            val fitStr = "Fit: ${job.fitScore?.toInt() ?: "N/A"}"
            val artifact = job.metadataUrl

            if (company.length > maxCompany) maxCompany = company.length
            if (title.length > maxTitle) maxTitle = title.length
            if (fitStr.length > maxFit) maxFit = fitStr.length
            if (artifact.length > maxArtifact) maxArtifact = artifact.length
        }

        return ColumnWidths(maxCompany, maxTitle, maxFit, maxArtifact)
    }

    /**
     * Format a single job line with the given column widths.
     * 
     * @param state The JDState representing the job
     * @param widths The column widths to use
     * @param color Optional ANSI color code to wrap the line with
     * @return Formatted line with separators
     */
    fun formatJobLine(state: JDState, widths: ColumnWidths, color: String = ""): String {
        val company = state.company.ifBlank { "Unknown Company" }
        val title = state.roleTitle.ifBlank { "Unknown Role" }
        val fitStr = "Fit: ${state.fitScore?.toInt() ?: "N/A"}"
        val artifact = truncateUrl(state.metadataUrl, widths.artifact)

        val companyPadded = company.padEnd(widths.company)
        val titlePadded = title.padEnd(widths.title)
        val fitPadded = fitStr.padEnd(widths.fit)

        val line = "$companyPadded$SEPARATOR$titlePadded$SEPARATOR$fitPadded$SEPARATOR$artifact"

        return if (color.isNotEmpty()) {
            "$color$line\u001B[0m"
        } else {
            line
        }
    }

    /**
     * Format a list of jobs into a box-drawing table for scored jobs output.
     * 
     * @param jobs List of JDState objects representing scored jobs
     * @return List of formatted lines forming the box-drawing table
     */
    fun formatScoredJobsTable(jobs: List<JDState>): List<String> {
        if (jobs.isEmpty()) return emptyList()

        // Sort: non-null fitScore descending, nulls last
        val sorted = jobs.sortedByDescending { it.fitScore ?: Float.MIN_VALUE }

        // Compute column widths using existing logic
        val widths = computeColumnWidths(sorted)
        
        // Define minimum widths and column configurations
        val companyWidth = maxOf(widths.company, 15)
        val titleWidth = maxOf(widths.title, 30)
        val fitWidth = 4  // Fixed width for fit score (right-aligned)
        val artifactWidth = widths.artifact
        
        val colWidths = listOf(companyWidth, titleWidth, fitWidth, artifactWidth)
        val rightAlign = listOf(false, false, true, false)  // company, title left; fit right; artifact left

        // Helper to build horizontal rule
        fun hRule(l: Char, sep: Char, r: Char): String {
            return l + colWidths.joinToString("$sep") { "─".repeat(it + 2) } + r
        }

        // Helper to build a table row
        fun tableRow(cols: List<String>): String {
            val cells = cols.zip(colWidths).zip(rightAlign).joinToString(" │ ") { (cw, right) ->
                val (cell, w) = cw
                if (right) cell.padStart(w) else cell.padEnd(w)
            }
            return "│ $cells │"
        }

        val lines = mutableListOf<String>()
        
        // Build table
        lines.add(hRule('┌', '┬', '┐'))
        lines.add(tableRow(listOf("Company", "Title", "Fit", "Artifact")))
        lines.add(hRule('├', '┼', '┤'))
        
        for (job in sorted) {
            val company = buildString {
                if (job.isDigest) append("• ")
                append(job.company.ifBlank { "Unknown Company" })
            }
            val title = job.roleTitle.ifBlank { "Unknown Role" }
            val fitStr = when (val code = SkipReasonCode.fromState(job)) {
                null -> job.fitScore?.toInt()?.toString() ?: "N/A"
                else -> code.abbreviation
            }
            val artifact = truncateUrl(job.metadataUrl, artifactWidth)
            
            lines.add(tableRow(listOf(company, title, fitStr, artifact)))
        }
        
        lines.add(hRule('└', '┴', '┘'))
        
        return lines
    }

    /**
     * Format a list of jobs into a table of aligned lines.
     * 
     * @param jobs List of JDState objects representing scored jobs
     * @return List of formatted lines (one per job)
     */
    fun formatJobTable(jobs: List<JDState>): List<String> {
        if (jobs.isEmpty()) return emptyList()

        val widths = computeColumnWidths(jobs)
        return jobs.map { formatJobLine(it, widths) }
    }

    /**
     * Format a single job for non-batch (single job) output.
     * Uses default minimum widths.
     * 
     * @param state The JDState representing the job
     * @param color Optional ANSI color code
     * @return Formatted line
     */
    fun formatSingleJob(state: JDState, color: String = ""): String {
        return formatJobLine(state, ColumnWidths.default(), color)
    }

    /**
     * Truncate a URL to the specified maximum length.
     * If the URL is longer than maxLength, it truncates the middle part and inserts an ellipsis.
     * 
     * @param url The URL to truncate
     * @param maxLength Maximum length of the resulting string
     * @return Truncated URL or original if within limit
     */
    private fun truncateUrl(url: String, maxLength: Int): String {
        if (url.isEmpty() || url.length <= maxLength) return url

        val effectiveMax = maxLength.coerceAtLeast(DEFAULT_URL_MAX_LENGTH)
        if (effectiveMax < 10) return url.take(effectiveMax)

        // Need room for ellipsis
        val ellipsis = "…"
        val availableForUrl = effectiveMax - ellipsis.length

        if (availableForUrl < 5) return url.take(effectiveMax)

        // Split: keep 1/3 at start, 2/3 at end
        val startLen = availableForUrl / 3
        val endLen = availableForUrl - startLen

        val start = url.take(startLen)
        val end = url.takeLast(endLen)

        return "$start$ellipsis$end"
    }
}
