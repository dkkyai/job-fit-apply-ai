package com.jd.pipeline.nodes

import com.jd.pipeline.models.*
import com.jd.pipeline.nodes.tailor.*
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("ResumeTailoringSubgraph — process()")
class ResumeTailoringSubgraphProcessTest {

    private fun minProfile() = CandidateProfile(
        identity = CandidateIdentity(
            name = "Jane Doe", firstName = "Jane", lastName = "Doe",
            email = "jane@doe.com", phone = "555-1234", location = "Seattle, WA"
        ),
        background = CandidateBackground(
            targetTitle = "Staff SDET", yearsExperience = 10,
            summary = "Experienced SDET.", education = emptyList(),
            careerHistory = listOf(
                CareerEntry(
                    role = "Senior SDET", company = "Acme",
                    startDate = "2020-01", endDate = "2024-01",
                    location = "",
                    bullets = listOf(Bullet("", "Led mobile test framework.")),
                )
            ),
            coreStrengths = listOf("Mobile automation"),
            languages = emptyList(),
            domainExpertise = listOf("SDET")
        ),
        skills = listOf(
            SkillGroup("Primary Stack", listOf("Kotlin")),
            SkillGroup("Mobile Automation", listOf("Appium")),
            SkillGroup("Leadership", listOf("Mentoring"))
        )
    )

    private fun jdText(wordCount: Int = 200): String =
        "We are hiring a Staff SDET with mobile automation experience. ".repeat(wordCount / 10)

    private fun tailorInput(jdText: String = jdText(), @TempDir tempDir: Path? = null): JDState {
        val base = JDState(
            isJobPosting     = true,
            company          = "Acme",
            roleTitle        = "Staff SDET",
            jdText           = jdText,
            pipelineAction   = PipelineAction.TAILOR,
            candidateProfile = minProfile(),
        )
        return if (tempDir != null) base.copy(outputPath = tempDir.toString()) else base
    }

    // ── Non-TAILOR guard ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("non-TAILOR input passes through unchanged")
    inner class NonTailorPassThrough {

        @Test
        @DisplayName("SKIP action → returns input unchanged")
        fun skipPassesThrough() {
            val subgraph = ResumeTailoringSubgraph()
            val input = JDState(pipelineAction = PipelineAction.SKIP, company = "Corp")
            val result = subgraph.process(input)
            assertEquals(input, result)
        }

        @Test
        @DisplayName("no tailor nodes are called when action is SKIP")
        fun noNodesCalledForSkip() {
            val mockJdExt = mock<JdExtractionNode>()
            val subgraph = ResumeTailoringSubgraph(jdExtraction = mockJdExt)
            subgraph.process(JDState(pipelineAction = PipelineAction.SKIP))
            verify(mockJdExt, never()).process(any())
        }
    }

    // ── Null candidateProfile guard ───────────────────────────────────────────

    @Nested
    @DisplayName("null candidateProfile guard")
    inner class NullProfileGuard {

        @Test
        @DisplayName("returns error state when candidateProfile is null")
        fun nullProfileReturnsError(@TempDir tempDir: Path) {
            val subgraph = ResumeTailoringSubgraph()
            val input = tailorInput(tempDir = tempDir).copy(candidateProfile = null)
            val result = subgraph.process(input)
            assertTrue(result.error.isNotBlank(), "Expected error for null profile")
            assertTrue(result.error.contains("candidateProfile is null"))
        }
    }

    // ── Sparse jdText guard ───────────────────────────────────────────────────

    @Nested
    @DisplayName("sparse jdText (< 50 non-URL words)")
    inner class SparseJdText {

        @Test
        @DisplayName("renders untailored profile and returns without calling tailor nodes")
        fun sparseJdSkipsTailorNodes(@TempDir tempDir: Path) {
            val mockJdExt = mock<JdExtractionNode>()
            val subgraph = ResumeTailoringSubgraph(jdExtraction = mockJdExt)
            val sparse = "short jd text"
            val input = tailorInput(jdText = sparse).copy(
                outputPath = tempDir.toString()
            )
            subgraph.process(input)
            verify(mockJdExt, never()).process(any())
            assertTrue(
                java.nio.file.Files.exists(tempDir.resolve("tailored_resume.html")),
                "untailored resume HTML should still be written for a sparse JD",
            )
        }

        @Test
        @DisplayName("sparse JD marks the resume as untailored (degraded)")
        fun sparseJdMarksUntailored(@TempDir tempDir: Path) {
            val subgraph = ResumeTailoringSubgraph()
            val input = tailorInput(jdText = "short jd text").copy(outputPath = tempDir.toString())

            val result = subgraph.process(input)

            assertEquals(1, result.tailoringDegradedNodes.size)
            assertTrue(result.tailoringDegradedNodes[0].contains("untailored"), "sparse JD should be flagged untailored")
        }
    }

    // ── Full orchestration with mock nodes ────────────────────────────────────

    private fun mockJdExt() = mock<JdExtractionNode>().apply {
        whenever(process(any())).doAnswer { inv ->
            (inv.arguments[0] as TailorState).copy(
                jdRequirements = JdRequirements(targetTitle = "Staff SDET", mustHave = listOf("Kotlin"))
            )
        }
    }

    private fun mockGap() = mock<GapAnalysisNode>().apply {
        whenever(process(any())).doAnswer { inv ->
            (inv.arguments[0] as TailorState).copy(
                gapAnalysis = GapAnalysis(
                    supported = listOf(EvidencedTerm("Kotlin", "Led mobile test framework.")),
                    unsupported = listOf("Pact")
                )
            )
        }
    }

    private fun mockSummary() = mock<SummaryRewriteNode>().apply {
        whenever(process(any())).doAnswer { inv ->
            (inv.arguments[0] as TailorState).copy(tailoredSummary = "Tailored summary with Kotlin.")
        }
    }

    private fun mockBullets() = mock<BulletRewriteNode>().apply {
        whenever(process(any())).doAnswer { inv ->
            val profile = minProfile()
            (inv.arguments[0] as TailorState).copy(
                tailoredCareerHistory = profile.background.careerHistory,
                tailoredProjects = emptyList(),
                bulletMeta = mapOf(roleKey("Senior SDET", "Acme", "2020-01") to listOf(BulletMeta(1, true, true))),
                tailoredBullets = listOf(TailoredBullet("orig", "rewritten", listOf("Kotlin"), true, true))
            )
        }
    }

    private fun mockReorder() = mock<BulletReorderNode>().apply {
        whenever(process(any())).doAnswer { it.arguments[0] as TailorState }
    }

    private fun mockSkills() = mock<SkillsRestructureNode>().apply {
        whenever(process(any())).doAnswer { inv ->
            (inv.arguments[0] as TailorState).copy(
                restructuredSkills = RestructuredSkills(
                    groupedByCategory = mapOf("Languages" to listOf("Kotlin")),
                    jdMatchedSkills = listOf("Kotlin")
                )
            )
        }
    }

    private fun mockAts(report: AtsReport = cleanReport()) = mock<AtsValidationNode>().apply {
        whenever(process(any())).doAnswer { inv ->
            (inv.arguments[0] as TailorState).copy(atsReport = report)
        }
    }

    private fun cleanReport() = AtsReport(mustHaveCoveragePct = 100, overallScore = 90)

    @Nested
    @DisplayName("full tailor orchestration with mock nodes")
    inner class FullOrchestration {

        @Test
        @DisplayName("all seven stages are called in pipeline order")
        fun allNodesCalledInSequence(@TempDir tempDir: Path) {
            val calls = mutableListOf<String>()

            val jdExt = mockJdExt().also { whenever(it.process(any())).doAnswer { inv -> calls += "jdExtraction"
                (inv.arguments[0] as TailorState).copy(jdRequirements = JdRequirements(targetTitle = "Staff SDET", mustHave = listOf("Kotlin"))) } }
            val gapAn = mockGap().also { whenever(it.process(any())).doAnswer { inv -> calls += "gapAnalysis"
                (inv.arguments[0] as TailorState).copy(gapAnalysis = GapAnalysis(supported = listOf(EvidencedTerm("Kotlin", "ev")))) } }
            val sumRew = mockSummary().also { whenever(it.process(any())).doAnswer { inv -> calls += "summaryRewrite"
                (inv.arguments[0] as TailorState).copy(tailoredSummary = "Summary.") } }
            val bulRew = mockBullets().also { whenever(it.process(any())).doAnswer { inv -> calls += "bulletRewrite"
                (inv.arguments[0] as TailorState).copy(
                    tailoredCareerHistory = minProfile().background.careerHistory,
                    tailoredProjects = emptyList(),
                    bulletMeta = emptyMap(),
                    tailoredBullets = emptyList()) } }
            val reorder = mockReorder().also { whenever(it.process(any())).doAnswer { inv -> calls += "bulletReorder"
                inv.arguments[0] as TailorState } }
            val sklRst = mockSkills().also { whenever(it.process(any())).doAnswer { inv -> calls += "skillsRestructure"
                (inv.arguments[0] as TailorState).copy(restructuredSkills = RestructuredSkills(groupedByCategory = mapOf("L" to listOf("Kotlin")))) } }
            val atsVal = mockAts().also { whenever(it.process(any())).doAnswer { inv -> calls += "atsValidation"
                (inv.arguments[0] as TailorState).copy(atsReport = cleanReport()) } }

            val subgraph = ResumeTailoringSubgraph(jdExt, gapAn, sumRew, bulRew, reorder, sklRst, atsVal)
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertEquals(
                listOf("jdExtraction", "gapAnalysis", "summaryRewrite", "bulletRewrite",
                    "bulletReorder", "skillsRestructure", "atsValidation"),
                calls,
                "Stages must execute in pipeline order"
            )
            assertTrue(result.error.isBlank(), "Expected no error, got: ${result.error}")
        }

        @Test
        @DisplayName("an actionable validation report triggers exactly one refinement pass")
        fun actionableReportTriggersRefine(@TempDir tempDir: Path) {
            val actionable = AtsReport(mustHaveCoveragePct = 50, missingTerms = listOf("Kotlin"), overallScore = 60)
            val improved = AtsReport(mustHaveCoveragePct = 100, overallScore = 92)

            val atsVal = mock<AtsValidationNode>().apply {
                var call = 0
                whenever(process(any())).doAnswer { inv ->
                    call++
                    (inv.arguments[0] as TailorState).copy(atsReport = if (call == 1) actionable else improved)
                }
            }
            val sumRew = mockSummary()
            val subgraph = ResumeTailoringSubgraph(
                mockJdExt(), mockGap(), sumRew, mockBullets(), mockReorder(), mockSkills(), atsVal
            )

            val result = subgraph.process(tailorInput(tempDir = tempDir))

            verify(sumRew, times(2)).process(any())   // initial pass + one refinement pass
            verify(atsVal, times(2)).process(any())
            assertTrue(result.error.isBlank())
        }

        @Test
        @DisplayName("refinement is non-fatal: a failing summary does not stop skills from re-running")
        fun refineSurvivesFailingSummary(@TempDir tempDir: Path) {
            // Summary always fails; the refine pass must still reach skills + validation.
            val sumRew = mock<SummaryRewriteNode>().apply {
                var call = 0
                whenever(process(any())).doAnswer { inv ->
                    call++
                    val s = inv.arguments[0] as TailorState
                    if (call == 1) s.copy(tailoredSummary = "First-pass summary.") else s.copy(error = "summary_rewrite: echo")
                }
            }
            val sklRst = mockSkills()
            val atsVal = mock<AtsValidationNode>().apply {
                var call = 0
                whenever(process(any())).doAnswer { inv ->
                    call++
                    (inv.arguments[0] as TailorState).copy(
                        atsReport = if (call == 1) AtsReport(leakedUnsupportedTerms = listOf("Pact"), overallScore = 60)
                        else AtsReport(overallScore = 90)
                    )
                }
            }
            val subgraph = ResumeTailoringSubgraph(
                mockJdExt(), mockGap(), sumRew, mockBullets(), mockReorder(), sklRst, atsVal
            )

            val result = subgraph.process(tailorInput(tempDir = tempDir))

            verify(sklRst, times(2)).process(any())   // main pass + refine pass, despite the summary failure
            verify(atsVal, times(2)).process(any())
            assertTrue(result.error.isBlank())
        }

        @Test
        @DisplayName("a clean report does NOT trigger refinement")
        fun cleanReportSkipsRefine(@TempDir tempDir: Path) {
            val sumRew = mockSummary()
            val subgraph = ResumeTailoringSubgraph(
                mockJdExt(), mockGap(), sumRew, mockBullets(), mockReorder(), mockSkills(), mockAts()
            )
            subgraph.process(tailorInput(tempDir = tempDir))
            verify(sumRew, times(1)).process(any())
        }

        @Test
        @DisplayName("skillsRestructure error is non-fatal: validation is skipped, pipeline continues")
        fun skillsRestructureErrorIsNonFatal(@TempDir tempDir: Path) {
            val sklRst = mock<SkillsRestructureNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    (inv.arguments[0] as TailorState).copy(error = "skills_restructure: bad LLM output")
                }
            }
            val atsVal = mock<AtsValidationNode>()
            val subgraph = ResumeTailoringSubgraph(
                mockJdExt(), mockGap(), mockSummary(), mockBullets(), mockReorder(), sklRst, atsVal
            )

            val result = subgraph.process(tailorInput(tempDir = tempDir))

            verify(atsVal, never()).process(any())
            assertTrue(java.nio.file.Files.exists(tempDir.resolve("tailored_resume.html")))
            assertTrue(result.error.isBlank(), "Non-fatal skills error should not propagate: ${result.error}")
            assertTrue(result.tailoringDegradedNodes.contains("skills_restructure"))
        }
    }

    // ── Robustness: a content-node failure must NOT abandon the report ────────

    @Nested
    @DisplayName("content-node failures are non-fatal (report still produced)")
    inner class RobustnessNonFatal {

        private val pass: (org.mockito.invocation.InvocationOnMock) -> TailorState = { it.arguments[0] as TailorState }

        @Test
        @DisplayName("jd_extraction error is non-fatal: later nodes still run and it renders")
        fun jdExtractionErrorIsNonFatal(@TempDir tempDir: Path) {
            val jdExt = mock<JdExtractionNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    (inv.arguments[0] as TailorState).copy(error = "jd_extraction: LLM failed")
                }
            }
            val gapAn = mock<GapAnalysisNode>().apply { whenever(process(any())).doAnswer(pass) }
            val subgraph = ResumeTailoringSubgraph(
                jdExt, gapAn,
                mock<SummaryRewriteNode>().apply { whenever(process(any())).doAnswer(pass) },
                mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer(pass) },
                mockReorder(),
                mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer(pass) },
                mock<AtsValidationNode>()
            )

            val result = subgraph.process(tailorInput(tempDir = tempDir))

            verify(gapAn).process(any())              // later nodes still run despite the jd_extraction error
            assertTrue(java.nio.file.Files.exists(tempDir.resolve("tailored_resume.html")))   // and it still renders
            assertTrue(result.error.isBlank(), "jd_extraction failure must be non-fatal: ${result.error}")
        }

        @Test
        @DisplayName("bullet_rewrite error → still renders HTML + returns outputPath, no error")
        fun bulletRewriteErrorIsNonFatal(@TempDir tempDir: Path) {
            val bulRew = mock<BulletRewriteNode>().apply {
                whenever(process(any())).doAnswer { inv -> (inv.arguments[0] as TailorState).copy(error = "bullet_rewrite: LLM 507") }
            }
            val subgraph = ResumeTailoringSubgraph(
                mockJdExt(), mockGap(), mockSummary(), bulRew, mockReorder(),
                mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer(pass) },
                mock<AtsValidationNode>()
            )
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertTrue(result.error.isBlank(), "bullet_rewrite failure must not abort the subgraph: ${result.error}")
            assertEquals(tempDir.toString(), result.outputPath)
            assertTrue(
                java.nio.file.Files.exists(tempDir.resolve("tailored_resume.html")),
                "resume HTML should still be written so artifact_url is populated",
            )
            assertEquals(listOf("bullet_rewrite"), result.tailoringDegradedNodes, "the degraded node must be recorded")
        }

        @Test
        @DisplayName("a node that THROWS is non-fatal — subgraph still renders + records it")
        fun throwingNodeIsNonFatal(@TempDir tempDir: Path) {
            val sumRew = mock<SummaryRewriteNode>().apply { whenever(process(any())).thenThrow(RuntimeException("boom")) }
            val subgraph = ResumeTailoringSubgraph(
                mockJdExt(), mockGap(), sumRew,
                mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer(pass) },
                mockReorder(),
                mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer(pass) },
                mock<AtsValidationNode>()
            )
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertTrue(result.error.isBlank(), "a thrown node error must not abort the subgraph: ${result.error}")
            assertTrue(java.nio.file.Files.exists(tempDir.resolve("tailored_resume.html")))
            assertEquals(listOf("summary_rewrite"), result.tailoringDegradedNodes)
        }

        @Test
        @DisplayName("a clean run records NO degraded nodes")
        fun cleanRunHasNoDegraded(@TempDir tempDir: Path) {
            val subgraph = ResumeTailoringSubgraph(
                mockJdExt(), mockGap(), mockSummary(), mockBullets(), mockReorder(), mockSkills(), mockAts()
            )
            val result = subgraph.process(tailorInput(tempDir = tempDir))
            assertTrue(result.tailoringDegradedNodes.isEmpty(), "clean run should record no degraded nodes: ${result.tailoringDegradedNodes}")
        }

        @Test
        @DisplayName("multiple node failures are all recorded")
        fun multipleFailuresRecorded(@TempDir tempDir: Path) {
            val fail: (org.mockito.invocation.InvocationOnMock) -> TailorState = { (it.arguments[0] as TailorState).copy(error = "LLM failed") }
            val subgraph = ResumeTailoringSubgraph(
                mockJdExt(), mockGap(),
                mock<SummaryRewriteNode>().apply { whenever(process(any())).doAnswer(fail) },
                mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer(fail) },
                mockReorder(),
                mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer(pass) },
                mock<AtsValidationNode>()
            )
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertTrue(result.tailoringDegradedNodes.containsAll(listOf("summary_rewrite", "bullet_rewrite")),
                "both failed nodes should be recorded: ${result.tailoringDegradedNodes}")
        }
    }
}
