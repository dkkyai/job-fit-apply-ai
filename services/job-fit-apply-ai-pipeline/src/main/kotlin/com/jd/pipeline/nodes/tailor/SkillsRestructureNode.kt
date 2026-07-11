package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import java.nio.file.Files

/**
 * Tailor subgraph node F (6/7): skills restructure.
 *
 * The skills section is parsed early and weighs heavily in ATS keyword matching, so this
 * node: drops resume skills with no relevance to the JD, regroups/relabels categories to
 * the JD's own groupings, orders categories by JD relevance, and guarantees every
 * `supported` must-have skill appears verbatim (exact JD phrasing).
 */
class SkillsRestructureNode(
    private val llm: LlmCaller = LlmClient.skillsClient(nodeKey = "skills_restructure")
) {
    private val mapper = ObjectMapper().registerKotlinModule()

    fun process(state: TailorState): TailorState {
        val jd = state.jdRequirements ?: return state.copy(error = "skills_restructure: jdRequirements is null")
        val gap = state.gapAnalysis ?: return state.copy(error = "skills_restructure: gapAnalysis is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "skills_restructure: candidateProfile is null")

        println("[skills_restructure] Restructuring skills for: ${state.roleTitle}")

        return try {
            val prompt = buildPrompt(jd, gap, state, profile)
            val response = llm.call(prompt)
            val parsed = mapper.readValue(stripJsonFences(response), RestructuredSkills::class.java)
            validate(parsed)
            println("[skills_restructure] categories: ${parsed.groupedByCategory.size}, JD-matched: ${parsed.jdMatchedSkills.size}, removed: ${parsed.removedForThisRole.size}")
            state.copy(restructuredSkills = parsed)
        } catch (e: Exception) {
            state.copy(error = "skills_restructure: ${e.message}")
        }
    }

    private fun buildPrompt(
        jd: JdRequirements,
        gap: GapAnalysis,
        state: TailorState,
        profile: com.jd.pipeline.models.CandidateProfile
    ): String {
        val skillPrompt = try {
            if (Files.exists(Config.SKILLS_RESTRUCTURE_SKILL)) Files.readString(Config.SKILLS_RESTRUCTURE_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val candidateGroups = profile.skills
            .filter { it.items.isNotEmpty() }
            .joinToString("\n") { "- ${it.label}: ${it.items.joinToString(", ")}" }

        val jdGroupings = jd.skillGroupings
            .filter { it.items.isNotEmpty() || it.label.isNotBlank() }
            .joinToString("\n") { "- ${it.label}: ${it.items.joinToString(", ")}" }
            .ifBlank { "(the JD does not group its skills — use logical categories ordered by JD relevance)" }

        val supportedTerms = gap.supported.map { it.term }.distinct()

        return buildString {
            appendLine(TailorRubric.prepend(skillPrompt))
            appendLine()
            appendLine("TARGET TITLE: ${jd.targetTitle}")
            appendLine("MUST-HAVE TERMS: ${jd.mustHave.joinToString(", ")}")
            appendLine("NICE-TO-HAVE TERMS: ${jd.niceToHave.joinToString(", ")}")
            appendLine("EXACT-MATCH TERMS: ${jd.exactMatchTerms.joinToString(", ")}")
            appendLine()
            appendLine("THE JD'S OWN SKILL GROUPINGS (mirror these labels where possible):")
            appendLine(jdGroupings)
            appendLine()
            appendLine("SUPPORTED TERMS (every one of these that is a skill MUST appear verbatim in your output):")
            appendLine(supportedTerms.joinToString(", ").ifBlank { "(none)" })
            appendLine()
            appendLine("DO NOT ADD (no resume evidence): ${gap.unsupported.joinToString(", ").ifBlank { "(none)" }}")
            state.atsReport?.let { report ->
                appendLine()
                appendLine("PREVIOUS VALIDATION FEEDBACK (revision pass — fix these concretely):")
                appendLine("- Supported terms still MISSING from the output: ${report.missingTerms.joinToString(", ").ifBlank { "(none)" }}")
                appendLine("- Unsupported terms that LEAKED (remove): ${report.leakedUnsupportedTerms.joinToString(", ").ifBlank { "(none)" }}")
            }
            appendLine()
            appendLine("CANDIDATE SKILLS BY GROUP (the only source pool — never add a skill not listed here):")
            append(candidateGroups)
        }
    }

    /** Reject example/placeholder echoes and structurally empty output. */
    private fun validate(parsed: RestructuredSkills) {
        if (parsed.groupedByCategory.isEmpty() || parsed.groupedByCategory.values.all { it.isEmpty() }) {
            throw IllegalStateException("LLM returned no skill groups")
        }
        val flat = parsed.groupedByCategory.entries.joinToString(" ") { "${it.key} ${it.value.joinToString(" ")}" }
        EXAMPLE_TELLS.forEach { tell ->
            if (flat.contains(tell, ignoreCase = true)) {
                throw IllegalStateException("LLM returned example/placeholder text ('$tell') — skills not restructured")
            }
        }
    }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    companion object {
        private val EXAMPLE_TELLS = listOf(
            "skill1", "skill2", "skill3", "skill4",
            "<MostRelevantCategory>", "<jd_matched_skill>", "<placeholder>"
        )
        private val DEFAULT_PROMPT = """
            |Restructure the candidate's skills section for the target JD.
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "grouped_by_category": { "category label": ["skill", ...], ... },
            |  "jd_matched_skills": ["skills present in both the JD and the resume", ...],
            |  "removed_for_this_role": ["resume skills dropped as irrelevant for this JD", ...]
            |}
            |Rules:
            |- Only skills from the candidate's list — never add one.
            |- DROP skills with no relevance to this JD (list them in removed_for_this_role).
            |- Mirror the JD's own grouping labels where given; order categories by JD relevance.
            |- Every supported must-have skill appears verbatim (exact JD phrasing).
            |- Within each group, JD-matched skills lead.
        """.trimMargin()
    }
}
