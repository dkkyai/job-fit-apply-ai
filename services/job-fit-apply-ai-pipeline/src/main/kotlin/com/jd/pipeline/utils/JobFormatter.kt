package com.jd.pipeline.utils

import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest

/**
 * Data class representing column widths for job output formatting.
 */
data class ColumnWidths(
    val company: Int,
    val title: Int,
    val artifact: Int
)

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

    /**
     * Compute column widths based on the maximum length of each field across all jobs.
     */
    fun computeColumnWidths(jobs: List<JDState>): ColumnWidths {
        var maxCompany = 10
        var maxTitle = 20
        var maxArtifact = 30

        for (job in jobs) {
            val company = job.company.ifBlank { "Unknown Company" }
            val title = job.roleTitle.ifBlank { "Unknown Role" }
            val artifact = job.metadataUrl

            if (company.length > maxCompany) maxCompany = company.length
            if (title.length > maxTitle) maxTitle = title.length
            if (artifact.length > maxArtifact) maxArtifact = artifact.length
        }

        return ColumnWidths(maxCompany, maxTitle, maxArtifact)
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
            // The artifact column is sized to the longest URL, so URLs are shown in full.
            val artifact = job.metadataUrl
            
            lines.add(tableRow(listOf(company, title, fitStr, artifact)))
        }
        
        lines.add(hRule('└', '┴', '┘'))
        
        return lines
    }
}
