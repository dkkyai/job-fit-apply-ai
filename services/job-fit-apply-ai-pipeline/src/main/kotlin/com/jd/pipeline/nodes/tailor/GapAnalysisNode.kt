package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.CandidateProfileRenderer
import java.nio.file.Files

/**
 * Tailor subgraph node B (2/7): gap analysis — produces the "safe to claim" partition.
 *
 * Cross-references every JD must-have/nice-to-have term against the candidate profile and
 * emits [GapAnalysis]: `supported` (real evidence, safe to mirror verbatim), `unsupported`
 * (must never be claimed), and `missing_but_supported` (the candidate has it but the
 * resume doesn't surface it — the highest-value additions). All downstream emphasis draws
 * ONLY from the supported sets; this is the integrity guardrail made structural.
 */
class GapAnalysisNode(
    private val llm: LlmCaller = LlmClient.orchestrationClient(nodeKey = "gap_analysis")
) {
    private val mapper = ObjectMapper()

    fun process(state: TailorState): TailorState {
        val jd = state.jdRequirements
            ?: return state.copy(error = "gap_analysis: jdRequirements is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "gap_analysis: candidateProfile is null")

        println("[gap_analysis] Partitioning ${jd.mustHave.size} must-have / ${jd.niceToHave.size} nice-to-have terms for: ${state.roleTitle}")

        return try {
            val neverClaim = profile.tailoring.neverClaim
            val prompt = buildPrompt(jd, CandidateProfileRenderer.renderForTailoring(profile), neverClaim)
            val response = llm.call(prompt)
            val parsed = enforceNeverClaim(
                mapper.readValue(stripJsonFences(response), GapAnalysis::class.java),
                neverClaim
            )
            if (parsed.supported.isEmpty() && parsed.unsupported.isEmpty()) {
                return state.copy(error = "gap_analysis: LLM returned an empty partition")
            }
            println("[gap_analysis] supported: ${parsed.supported.size}, unsupported: ${parsed.unsupported.size}, missing-but-supported: ${parsed.missingButSupported.size}")
            state.copy(gapAnalysis = parsed)
        } catch (e: Exception) {
            state.copy(error = "gap_analysis: ${e.message}")
        }
    }

    /**
     * Deterministic backstop for the candidate's never-claim list: any supported /
     * missing_but_supported term that contains a never-claim term moves to unsupported,
     * and the never-claim terms themselves join unsupported — so downstream DO-NOT-CLAIM
     * prompts and the ATS leak scan cover them even when the LLM mis-partitioned.
     * `internal` for test visibility.
     */
    internal fun enforceNeverClaim(parsed: GapAnalysis, neverClaim: List<String>): GapAnalysis {
        if (neverClaim.isEmpty()) return parsed
        fun banned(term: String) = neverClaim.any { nc -> nc.equals(term.trim(), ignoreCase = true) || TermMatch.present(nc, term) }
        val (bannedSupported, keptSupported) = parsed.supported.partition { banned(it.term) }
        if (bannedSupported.isNotEmpty()) {
            println("[gap_analysis] never_claim override: moved ${bannedSupported.size} term(s) to unsupported: " +
                bannedSupported.joinToString(", ") { it.term })
        }
        return parsed.copy(
            supported = keptSupported,
            missingButSupported = parsed.missingButSupported.filterNot { banned(it.term) },
            unsupported = (parsed.unsupported + bannedSupported.map { it.term } + neverClaim)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        )
    }

    private fun buildPrompt(jd: JdRequirements, profileMarkdown: String, neverClaim: List<String>): String {
        val skillPrompt = try {
            if (Files.exists(Config.GAP_ANALYSIS_SKILL)) Files.readString(Config.GAP_ANALYSIS_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        return buildString {
            appendLine(TailorRubric.prepend(skillPrompt))
            appendLine()
            appendLine("TARGET TITLE: ${jd.targetTitle}")
            appendLine("MUST-HAVE TERMS (JD exact phrasing): ${jd.mustHave.joinToString(", ")}")
            appendLine("NICE-TO-HAVE TERMS: ${jd.niceToHave.joinToString(", ")}")
            appendLine("EXACT-MATCH TERMS (tools/certs/protocols): ${jd.exactMatchTerms.joinToString(", ")}")
            appendLine("SENIORITY SIGNALS: ${jd.senioritySignals.joinToString(", ")}")
            if (neverClaim.isNotEmpty()) {
                appendLine()
                appendLine("CANDIDATE-DECLARED NEVER-CLAIM (classify as unsupported even if evidence exists —")
                appendLine("the candidate refuses to interview on these): ${neverClaim.joinToString(", ")}")
            }
            appendLine()
            appendLine("CANDIDATE PROFILE (authoritative evidence source — quote it, never extend it):")
            append(profileMarkdown)
        }
    }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    companion object {
        private val DEFAULT_PROMPT = """
            |Cross-reference every JD term against the candidate profile and partition them.
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "supported": [ {"term": "JD term", "evidence": "quoted resume text that proves it"} ],
            |  "unsupported": ["JD term with NO resume evidence", ...],
            |  "missing_but_supported": [ {"term": "must-have the candidate has but the resume under-surfaces", "evidence": "quoted resume text"} ]
            |}
            |Rules:
            |- evidence must be text quoted from the profile, not inferred capability.
            |- unsupported terms must NOT be claimed anywhere downstream — be strict.
            |- missing_but_supported: must-haves with clear evidence that current summary/bullets/skills
            |  do not surface prominently — these are the highest-value additions.
        """.trimMargin()
    }
}
