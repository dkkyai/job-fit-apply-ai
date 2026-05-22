package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import java.nio.file.Files
import java.nio.file.Path

/**
 * Node: generate_cover_letter
 * 
 * Generate a cover letter for jobs with score >= FIT_THRESHOLD.
 */
class GenerateCoverLetterNode(
    private val llm: LlmCaller = LlmClient.fromModelString(
        Config.COVER_LETTER_MODEL,
        jsonMode = false,
        temperature = 0.4,
        timeoutSeconds = 240,
        nodeKey = "cover_letter"
    )
) : Node<JDState> {

    override fun process(input: JDState): JDState {
        val score = input.fitScore
        val action = input.pipelineAction
        val outputPath = input.outputPath
        
        // Only generate cover letter if tailoring and score is high enough
        if (action != PipelineAction.TAILOR || score == null || score < Config.FIT_THRESHOLD) {
            return input
        }

        val company = input.company
        val roleTitle = input.roleTitle
        val jdText = input.jdText
        val strengths = input.strengths

        println("[cover_letter] Generating cover letter for: $roleTitle @ $company")

        return try {
            val authorName = input.candidateProfile?.identity?.fullName?.ifBlank { "Applicant" } ?: "Applicant"
            val prompt = buildCoverLetterPrompt(authorName, roleTitle, company, jdText, strengths)
            val coverLetter = llm.call(prompt).trim()

            // Save to output directory
            if (!outputPath.isNullOrEmpty()) {
                val coverLetterPath = Path.of(outputPath).resolve("cover_letter.txt")
                Files.writeString(coverLetterPath, coverLetter)
                println("[cover_letter] Saved to: $coverLetterPath")
            }

            input.copy(coverLetter = coverLetter)
        } catch (e: Exception) {
            input.copy(error = "cover_letter: ${e.message}")
        }
    }

    private fun buildCoverLetterPrompt(
        author: String,
        roleTitle: String, 
        company: String, 
        jdText: String?, 
        strengths: List<String>
    ): String = """
        Write a professional yet casual cover letter for a job application.
        
        JOB TITLE: $roleTitle
        COMPANY: $company
        
        JOB DESCRIPTION:
        $jdText
        
        CANDIDATE STRENGTHS TO HIGHLIGHT:
        $strengths
        
        CANDIDATE NAME (sign the letter with this exact name):
        - $author

        INSTRUCTIONS:
        1. Keep it concise (3-4 paragraphs).
        2. Professional but friendly tone.
        3. Highlight relevant experience drawn from the candidate strengths above.
        4. Show enthusiasm for the role and company.
        5. Do not fabricate experience, metrics, or skills not present in the strengths.
        6. No placeholder text (no "[Your Name]", "[Company]", etc.) — write a complete letter.
        7. Open with "Dear ${company} Hiring Team," and sign off with the candidate's name above.
        8. Output ONLY the letter text. No preamble, no explanation, no markdown fences.
    """.trimIndent()
}