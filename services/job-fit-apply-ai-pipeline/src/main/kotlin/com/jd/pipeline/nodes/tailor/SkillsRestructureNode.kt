package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import java.nio.file.Files

/**
 * Tailor subgraph node 5/6: Skills Restructure
 */
class SkillsRestructureNode(
    private val llm: LlmCaller = LlmClient.skillsClient(nodeKey = "skills_restructure")
) {
    private val mapper = ObjectMapper()

    fun process(state: TailorState): TailorState {
        val jd = state.jdStructured ?: return state.copy(error = "skills_restructure: jdStructured is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "skills_restructure: candidateProfile is null")

        println("[skills_restructure] Restructuring skills for: ${state.roleTitle}")

        return try {
            val prompt = buildPrompt(jd, profile)
            val response = llm.call(prompt)
            val cleaned = stripJsonFences(response)
            val parsed = mapper.readValue(cleaned, RestructuredSkills::class.java)
            validateNotExampleText(parsed.restructuredText)
            println("[skills_restructure] JD-matched skills: ${parsed.jdMatchedSkills.size}, categories: ${parsed.groupedByCategory.size}")
            state.copy(restructuredSkills = parsed)
        } catch (e: Exception) {
            state.copy(error = "skills_restructure: ${e.message}")
        }
    }

    private fun buildPrompt(jd: JdStructured, profile: com.jd.pipeline.models.CandidateProfile): String {
        val skillPrompt = try {
            if (Files.exists(Config.SKILLS_RESTRUCTURE_SKILL)) Files.readString(Config.SKILLS_RESTRUCTURE_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val skillBuckets = profile.skills
            .filter { it.items.isNotEmpty() }
            .joinToString("\n") { "- ${it.label}: ${it.items.joinToString(", ")}" }

        val coreStrengths = profile.background.coreStrengths.joinToString("; ")
        val domain = profile.background.domainExpertise.joinToString(", ")

        return """
            $skillPrompt

            TARGET ROLE: ${jd.roleTitle} (${jd.seniority})
            JD REQUIRED SKILLS: ${jd.requiredSkills.joinToString(", ")}
            JD PREFERRED SKILLS: ${jd.preferredSkills.joinToString(", ")}
            JD DOMAIN KEYWORDS: ${jd.domainKeywords.joinToString(", ")}

            CANDIDATE SKILLS BY BUCKET (do not add skills not listed here):
            $skillBuckets

            CANDIDATE CORE STRENGTHS: $coreStrengths
            CANDIDATE DOMAIN EXPERTISE: $domain
        """.trimIndent()
    }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    private fun validateNotExampleText(restructuredText: String) {
        EXAMPLE_TELLS.forEach { tell ->
            if (restructuredText.contains(tell, ignoreCase = true)) {
                throw IllegalStateException("LLM returned example/placeholder text ('$tell') — skills not restructured")
            }
        }
    }

    companion object {
        private val EXAMPLE_TELLS = listOf(
            "skill1", "skill2", "skill3", "skill4",
            "<MostRelevantCategory>", "<jd_matched_skill>", "<placeholder>"
        )
        private val DEFAULT_PROMPT = """
            |You are a resume skills organiser. Restructure the skills section.
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "restructured_text": "string (plain-text skills section ready to paste into resume)",
            |  "removed_for_this_role": ["string", ...],
            |  "jd_matched_skills": ["string", ...],
            |  "grouped_by_category": {
            |    "category_name": ["skill", ...]
            |  }
            |}
            |
            |Rules:
            |- Only include skills that appear in the resume. Do not add skills not listed.
            |- Group by logical category: Languages, Frameworks, CI/CD, Cloud, Testing, Observability, etc.
            |- Within each group, lead with JD-matched skills.
            |- removed_for_this_role: skills the candidate has but that are irrelevant noise for this role.
            |- restructured_text: a plain-text, ATS-safe rendering ready to copy into the resume.
        """.trimMargin()
    }
}
