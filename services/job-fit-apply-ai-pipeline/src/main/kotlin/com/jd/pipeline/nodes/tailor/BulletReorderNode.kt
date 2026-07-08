package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.config.Config
import com.jd.pipeline.models.CareerEntry

/**
 * Tailor subgraph node E (5/7): bullet reorder — deterministic Kotlin, no LLM call.
 *
 * Sorts each role's bullets by descending [BulletMeta.score] (must-have relevance, then
 * quantification, then seniority signal) so the strongest bullet leads every role — the
 * recruiter-skim rule. Role order itself is untouched (resumes stay reverse-chronological).
 *
 * Optionally caps bullet counts for skim readability (most recent role keeps more than
 * older ones); the lowest-scoring bullets are dropped whole — text is never truncated.
 * Sorting is stable, so equal-scoring bullets keep the rewrite's order.
 */
class BulletReorderNode(
    private val capEnabled: Boolean = Config.TAILOR_BULLET_CAP_ENABLED,
    private val capRecent: Int = Config.TAILOR_BULLET_CAP_RECENT,
    private val capOlder: Int = Config.TAILOR_BULLET_CAP_OLDER,
) {

    fun process(state: TailorState): TailorState {
        val career = state.tailoredCareerHistory
            ?: return state.copy(error = "bullet_reorder: tailoredCareerHistory is null")
        val meta = state.bulletMeta
            ?: return state.copy(error = "bullet_reorder: bulletMeta is null")
        val projects = state.tailoredProjects ?: emptyList()

        println("[bullet_reorder] Reordering bullets across ${career.size + projects.size} role(s)")

        val reorderedCareer = career.mapIndexed { index, entry ->
            reorderEntry(entry, meta, cap = capFor(isRecent = index == 0))
        }
        // Projects are side work — always the older-role cap.
        val reorderedProjects = projects.map { reorderEntry(it, meta, cap = capFor(isRecent = false)) }

        val dropped = (career.sumOf { it.bullets.size } + projects.sumOf { it.bullets.size }) -
            (reorderedCareer.sumOf { it.bullets.size } + reorderedProjects.sumOf { it.bullets.size })
        if (dropped > 0) println("[bullet_reorder] Capped: dropped $dropped lowest-scoring bullet(s)")

        return state.copy(
            tailoredCareerHistory = reorderedCareer,
            tailoredProjects = reorderedProjects
        )
    }

    private fun capFor(isRecent: Boolean): Int? = when {
        !capEnabled -> null
        isRecent -> capRecent
        else -> capOlder
    }

    /**
     * Sort one entry's bullets by descending meta score (stable) and apply the cap.
     * Entries with no meta (e.g. the LLM skipped the role) keep their original order.
     * `internal` for test visibility.
     */
    internal fun reorderEntry(
        entry: CareerEntry,
        metaByRole: Map<String, List<BulletMeta>>,
        cap: Int?
    ): CareerEntry {
        val metas = metaByRole[roleKey(entry.role, entry.company, entry.startDate)]
        val scored = if (metas != null && metas.size == entry.bullets.size) {
            entry.bullets.zip(metas).sortedByDescending { (_, m) -> m.score }.map { it.first }
        } else {
            entry.bullets
        }
        val capped = if (cap != null && cap > 0) scored.take(cap) else scored
        return entry.copy(bullets = capped)
    }
}
