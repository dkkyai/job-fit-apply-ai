package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CareerEntry

/**
 * Internal state for the ResumeTailoringSubgraph.
 *
 * Carries read-only copies of the fields it needs from JDState plus each node's
 * progressive outputs. JDState is never mutated from within the subgraph — the entry
 * point (ResumeTailoringSubgraph) reads from JDState and writes back a single copy
 * at the end.
 *
 * Each tailored field is null until its producing node runs. The pipeline:
 *   jd_extraction → gap_analysis → summary_rewrite → bullet_rewrite
 *   → bullet_reorder (deterministic) → skills_restructure → ats_validation
 */
data class TailorState(
    // ── From JDState (read-only copies at subgraph entry) ─────────────────────
    val jdText: String,
    val candidateProfile: CandidateProfile? = null,
    val fitScore: Float = 0f,
    /** score_fit's strengths/gaps — context hints only; gap_analysis produces the authoritative sets. */
    val strengths: List<String> = emptyList(),
    val gaps: List<String> = emptyList(),
    val company: String,
    val roleTitle: String,
    val trackId: Int = 0,

    // ── Progressive outputs (null until their producing node runs) ─────────────
    /** Node A: the JD's structured requirement set — single source for all later nodes. */
    val jdRequirements: JdRequirements? = null,
    /** Node B: supported / unsupported / missing_but_supported partition with evidence. */
    val gapAnalysis: GapAnalysis? = null,
    /** Node C: rewritten summary (plain text). */
    val tailoredSummary: String? = null,
    /** Node D: career history with rewritten bullets. Non-bullet fields copy through unchanged. */
    val tailoredCareerHistory: List<CareerEntry>? = null,
    /** Node D: projects with rewritten bullets. Empty when the profile has no projects. */
    val tailoredProjects: List<CareerEntry>? = null,
    /**
     * Node D: per-role reorder inputs, keyed by [roleKey] and index-aligned with that
     * entry's bullets. Consumed by BulletReorderNode; refreshed by it after sorting.
     */
    val bulletMeta: Map<String, List<BulletMeta>>? = null,
    /** Node D: flat original→rewritten pairs — only for the `tailored_bullets.txt` diagnostic. */
    val tailoredBullets: List<TailoredBullet>? = null,
    /** Node F: JD-relevance-ordered skill groups. */
    val restructuredSkills: RestructuredSkills? = null,
    /** Node G: actionable coverage/integrity report; non-null also marks a refine pass in prompts. */
    val atsReport: AtsReport? = null,

    // ── Pipeline metadata ─────────────────────────────────────────────────────
    val error: String = ""
)
