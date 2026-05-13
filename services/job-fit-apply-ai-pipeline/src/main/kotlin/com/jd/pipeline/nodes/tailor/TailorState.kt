package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CareerEntry

/**
 * Internal state for the ResumeTailoringSubgraph.
 *
 * Carries read-only copies of the fields it needs from JDState plus
 * each node's progressive outputs.  JDState is never mutated from within
 * the subgraph — the subgraph entry point (ResumeTailoringSubgraph) reads
 * from JDState and writes back a single copy at the end.
 *
 * Each tailored field is null until its producing node runs.
 */
data class TailorState(
    // ── From JDState (read-only copies at subgraph entry) ─────────────────────
    val jdText: String,
    val candidateProfile: CandidateProfile? = null,
    val fitScore: Float,
    val strengths: List<String>,
    val gaps: List<String>,
    val company: String,
    val roleTitle: String,
    val trackId: Int,

    // ── Progressive outputs (null until their producing node runs) ─────────────
    val jdStructured: JdStructured? = null,
    val gapAnalysis: GapAnalysis? = null,
    val tailoredSummary: String? = null,
    /** Career history with bullets rewritten per role. Non-bullet fields copy through unchanged. */
    val tailoredCareerHistory: List<CareerEntry>? = null,
    /** Projects with bullets rewritten per role. Empty list when the profile has no projects. */
    val tailoredProjects: List<CareerEntry>? = null,
    /** Flat list of original→rewritten bullet pairs — used only for the `tailored_bullets.txt` diagnostic file. */
    val tailoredBullets: List<TailoredBullet>? = null,
    val restructuredSkills: RestructuredSkills? = null,
    val atsScore: AtsScore? = null,

    // ── Pipeline metadata ─────────────────────────────────────────────────────
    val error: String = ""
)
