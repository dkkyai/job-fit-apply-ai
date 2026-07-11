package com.jd.pipeline.utils

import com.jd.pipeline.config.Config

/**
 * Batch-scoped timing accumulator for LLM nodes.
 *
 * Call [reset] at the start of each batch run, [record] inside each timed node
 * invocation, and [summary] to retrieve the aggregated results for display.
 *
 * Not thread-safe — the pipeline processes emails sequentially on one thread.
 */
object NodeTimer {

    private val samples: MutableMap<String, MutableList<Long>> = mutableMapOf()

    /** Pipeline execution order for table display. */
    private val DISPLAY_ORDER = listOf(
        "scan_email", "scrape_jd", "score_fit",
        "jd_extraction", "gap_analysis", "summary_rewrite",
        "bullet_rewrite", "skills_restructure", "ats_scoring",
        "cover_letter", "draft_reply"
    )

    private val DISPLAY_NAMES = mapOf(
        "scan_email"         to "ScanEmail",
        "scrape_jd"          to "ScrapeJd",
        "score_fit"          to "ScoreFit",
        "jd_extraction"      to "JdExtraction",
        "gap_analysis"       to "GapAnalysis",
        "summary_rewrite"    to "SummaryRewrite",
        "bullet_rewrite"     to "BulletRewrite",
        "skills_restructure" to "SkillsRestructure",
        "ats_scoring"        to "AtsScoring",
        "cover_letter"       to "CoverLetter",
        "draft_reply"        to "DraftReply"
    )

    /** Record elapsed milliseconds for a node key. */
    fun record(node: String, elapsedMs: Long) {
        samples.getOrPut(node) { mutableListOf() } += elapsedMs
    }

    /** Clear all samples. Call once before each batch run. */
    fun reset() = samples.clear()

    data class Entry(
        val displayName: String,
        val model: String,
        val count: Int,
        val avgSec: Double,
        val minSec: Double,
        val maxSec: Double
    )

    /**
     * Return one [Entry] per recorded LLM node, in pipeline execution order.
     * Nodes with no samples are omitted.
     */
    fun summary(): List<Entry> {
        val modelFor: Map<String, String> by lazy {
            mapOf(
                "scan_email"         to Config.SCAN_MODEL,
                "scrape_jd"          to Config.SCRAPE_MODEL,
                "score_fit"          to Config.SCORE_MODEL,
                "jd_extraction"      to Config.SCORE_MODEL,
                "gap_analysis"       to Config.SCORE_MODEL,
                "summary_rewrite"    to Config.RESUME_REASONING_MODEL,
                "bullet_rewrite"     to Config.RESUME_REASONING_MODEL,
                "skills_restructure" to Config.SKILLS_MODEL,
                "ats_scoring"        to Config.SCORE_MODEL,
                "cover_letter"       to Config.COVER_LETTER_MODEL,
                "draft_reply"        to Config.DRAFT_REPLY_MODEL
            )
        }

        return DISPLAY_ORDER
            .filter { it in samples }
            .map { key ->
                val times = samples.getValue(key)
                Entry(
                    displayName = DISPLAY_NAMES[key] ?: key,
                    model       = modelFor[key] ?: "",
                    count       = times.size,
                    avgSec      = times.average() / 1000.0,
                    minSec      = times.min().toDouble() / 1000.0,
                    maxSec      = times.max().toDouble() / 1000.0
                )
            }
    }
}
