package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.config.Config
import java.nio.file.Files

/**
 * The shared resume rubric (ATS / recruiter-skim / hiring-manager-depth / integrity rules)
 * every tailoring node prepends to its skill prompt — one source of truth instead of
 * divergent per-prompt copies.
 *
 * Loaded once from [Config.RESUME_RUBRIC]; falls back to a compact embedded version if
 * the resource is missing (mirrors the per-node DEFAULT_PROMPT pattern).
 */
object TailorRubric {

    val text: String by lazy {
        runCatching {
            if (Files.exists(Config.RESUME_RUBRIC)) Files.readString(Config.RESUME_RUBRIC).trim()
            else FALLBACK
        }.getOrDefault(FALLBACK)
    }

    /** `rubric + skill prompt`, ready for a node to append its inputs to. */
    fun prepend(skillPrompt: String): String = "${text}\n\n---\n\n${skillPrompt.trim()}"

    private val FALLBACK = """
        |# RESUME RUBRIC (shared rules — these win over any conflicting node instruction)
        |Targets in order: (1) pass ATS; (2) win the recruiter's ~7-second skim; (3) show
        |Staff/Senior-level scope a hiring manager believes — and that survives the interview.
        |ATS: mirror the JD's exact phrasing only for skills the candidate genuinely has;
        |acronym + expansion on first use; cover each must-have term once (summary + skills +
        |a bullet) — never stuff; standard section headings only.
        |Skim: strongest bullet first per role; fragments, action verb first, one idea per
        |bullet (≤ ~30 words); quantify where a real number exists (digits, not words);
        |no filler adjectives.
        |Voice: plain engineer-to-engineer language; banned LLM tells: spearheaded, leveraged,
        |utilized, orchestrated, seamless, robust, cutting-edge, innovative; the same opening
        |verb may lead at most 2 bullets across the resume.
        |Depth: lead with scope signals (team size, teams/apps served, coverage %, time/cost
        |deltas, engineers enabled); rewrite or demote any bullet a mid-level engineer could
        |claim; prefer influence-without-authority evidence.
        |Integrity (non-negotiable): never fabricate metrics, tools, scope, titles, or dates;
        |keep the source's hedges; emphasise only terms from the gap analysis's supported /
        |missing_but_supported sets; unsupported terms must not appear anywhere.
    """.trimMargin()
}
