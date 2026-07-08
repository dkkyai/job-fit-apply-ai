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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            SkillGroup("CI/CD Platforms", listOf("GitHub Actions")),
            SkillGroup("Web & API Automation", listOf("Playwright")),
            SkillGroup("Infrastructure & Observability", listOf("K8s")),
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
    }

    // ── Full orchestration with mock nodes ────────────────────────────────────

    @Nested
    @DisplayName("full tailor orchestration with mock nodes")
    inner class FullOrchestration {

        private fun passThrough(state: TailorState) = state

        @Test
        @DisplayName("all six tailor nodes are called in sequence")
        fun allNodesCalledInSequence(@TempDir tempDir: Path) {
            val calls = mutableListOf<String>()

            val jdExt  = mock<JdExtractionNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "jdExtraction"
                    (inv.arguments[0] as TailorState).copy(
                        jdStructured = JdStructured("Staff SDET", "Staff", listOf("Kotlin"))
                    )
                }
            }
            val gapAn  = mock<GapAnalysisNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "gapAnalysis"
                    (inv.arguments[0] as TailorState).copy(
                        gapAnalysis = GapAnalysis(topGaps = listOf("iOS"), topStrengths = listOf("Kotlin"))
                    )
                }
            }
            val sumRew = mock<SummaryRewriteNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "summaryRewrite"
                    (inv.arguments[0] as TailorState).copy(tailoredSummary = "Tailored summary.")
                }
            }
            val bulRew = mock<BulletRewriteNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "bulletRewrite"
                    (inv.arguments[0] as TailorState).copy(
                        tailoredBullets = listOf(TailoredBullet("orig", "rewritten", 90)),
                        tailoredCareerHistory = minProfile().background.careerHistory,
                    )
                }
            }
            val sklRst = mock<SkillsRestructureNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "skillsRestructure"
                    (inv.arguments[0] as TailorState).copy(
                        restructuredSkills = RestructuredSkills(
                            restructuredText = "Kotlin | Playwright",
                            jdMatchedSkills = listOf("Kotlin"),
                            groupedByCategory = mapOf("Languages" to listOf("Kotlin"))
                        )
                    )
                }
            }
            val atsSc  = mock<AtsScoringNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    calls += "atsScoring"
                    (inv.arguments[0] as TailorState).copy(
                        atsScore = AtsScore(overallScore = 88)
                    )
                }
            }
            val subgraph = ResumeTailoringSubgraph(
                jdExtraction    = jdExt,
                gapAnalysis     = gapAn,
                summaryRewrite  = sumRew,
                bulletRewrite   = bulRew,
                skillsRestructure = sklRst,
                atsScoring      = atsSc,
            )
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertEquals(
                listOf("jdExtraction", "gapAnalysis", "summaryRewrite", "bulletRewrite", "skillsRestructure", "atsScoring"),
                calls,
                "Nodes must execute in pipeline order"
            )
            assertTrue(result.error.isBlank(), "Expected no error, got: ${result.error}")
        }

        @Test
        @DisplayName("jd_extraction error is non-fatal: later nodes still run and it renders")
        fun jdExtractionErrorIsNonFatal(@TempDir tempDir: Path) {
            val jdExt = mock<JdExtractionNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    (inv.arguments[0] as TailorState).copy(error = "jd_extraction: LLM failed")
                }
            }
            val gapAn  = mock<GapAnalysisNode>().apply { whenever(process(any())).doAnswer { it.arguments[0] as TailorState } }
            val sumRew = mock<SummaryRewriteNode>().apply { whenever(process(any())).doAnswer { it.arguments[0] as TailorState } }
            val bulRew = mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer { it.arguments[0] as TailorState } }
            val sklRst = mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer { it.arguments[0] as TailorState } }
            val atsSc  = mock<AtsScoringNode>()

            val subgraph = ResumeTailoringSubgraph(jdExt, gapAn, sumRew, bulRew, sklRst, atsSc)
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            verify(gapAn).process(any())              // later nodes still run despite the jd_extraction error
            assertTrue(java.nio.file.Files.exists(tempDir.resolve("tailored_resume.html")))   // and it still renders
            assertTrue(result.error.isBlank(), "jd_extraction failure must be non-fatal: ${result.error}")
        }

        @Test
        @DisplayName("skillsRestructure error is non-fatal: atsScoring is skipped, pipeline continues")
        fun skillsRestructureErrorIsNonFatal(@TempDir tempDir: Path) {
            // Wire all nodes to succeed except skillsRestructure
            val passthroughTailorState: (Any) -> TailorState = { args ->
                (args as TailorState).copy(
                    jdStructured = JdStructured("Staff SDET", "Staff", listOf("Kotlin")),
                    gapAnalysis = GapAnalysis(topStrengths = listOf("Kotlin")),
                    tailoredSummary = "Summary.",
                    tailoredBullets = listOf(TailoredBullet("orig", "rewritten", 90)),
                    tailoredCareerHistory = minProfile().background.careerHistory,
                )
            }
            val jdExt  = mock<JdExtractionNode>().apply { whenever(process(any())).doAnswer { passthroughTailorState(it.arguments[0]) } }
            val gapAn  = mock<GapAnalysisNode>().apply { whenever(process(any())).doAnswer { passthroughTailorState(it.arguments[0]) } }
            val sumRew = mock<SummaryRewriteNode>().apply { whenever(process(any())).doAnswer { passthroughTailorState(it.arguments[0]) } }
            val bulRew = mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer { passthroughTailorState(it.arguments[0]) } }
            val sklRst = mock<SkillsRestructureNode>().apply {
                whenever(process(any())).doAnswer { inv ->
                    (inv.arguments[0] as TailorState).copy(error = "skills_restructure: bad LLM output")
                }
            }
            val atsSc  = mock<AtsScoringNode>()

            val subgraph = ResumeTailoringSubgraph(jdExt, gapAn, sumRew, bulRew, sklRst, atsSc)
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            // atsScoring must NOT be called when restructuredSkills is null
            verify(atsSc, never()).process(any())
            // Pipeline continues and renders HTML
            assertTrue(java.nio.file.Files.exists(tempDir.resolve("tailored_resume.html")))
            assertTrue(result.error.isBlank(), "Non-fatal skills error should not propagate: ${result.error}")
        }
    }

    // ── Robustness: a content-node failure must NOT abandon the report ────────

    @Nested
    @DisplayName("content-node failures are non-fatal (report still produced)")
    inner class RobustnessNonFatal {

        private val pass: (org.mockito.invocation.InvocationOnMock) -> TailorState = { it.arguments[0] as TailorState }

        @Test
        @DisplayName("bullet_rewrite error → still renders HTML + returns outputPath, no error")
        fun bulletRewriteErrorIsNonFatal(@TempDir tempDir: Path) {
            val jdExt  = mock<JdExtractionNode>().apply { whenever(process(any())).doAnswer(pass) }
            val gapAn  = mock<GapAnalysisNode>().apply { whenever(process(any())).doAnswer(pass) }
            val sumRew = mock<SummaryRewriteNode>().apply { whenever(process(any())).doAnswer(pass) }
            val bulRew = mock<BulletRewriteNode>().apply {
                whenever(process(any())).doAnswer { inv -> (inv.arguments[0] as TailorState).copy(error = "bullet_rewrite: LLM 507") }
            }
            val sklRst = mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer(pass) }
            val atsSc  = mock<AtsScoringNode>()

            val subgraph = ResumeTailoringSubgraph(jdExt, gapAn, sumRew, bulRew, sklRst, atsSc)
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
            val jdExt  = mock<JdExtractionNode>().apply { whenever(process(any())).doAnswer(pass) }
            val gapAn  = mock<GapAnalysisNode>().apply { whenever(process(any())).doAnswer(pass) }
            val sumRew = mock<SummaryRewriteNode>().apply { whenever(process(any())).thenThrow(RuntimeException("boom")) }
            val bulRew = mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer(pass) }
            val sklRst = mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer(pass) }
            val atsSc  = mock<AtsScoringNode>()

            val subgraph = ResumeTailoringSubgraph(jdExt, gapAn, sumRew, bulRew, sklRst, atsSc)
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertTrue(result.error.isBlank(), "a thrown node error must not abort the subgraph: ${result.error}")
            assertTrue(java.nio.file.Files.exists(tempDir.resolve("tailored_resume.html")))
            assertEquals(listOf("summary_rewrite"), result.tailoringDegradedNodes)
        }

        @Test
        @DisplayName("a clean run records NO degraded nodes")
        fun cleanRunHasNoDegraded(@TempDir tempDir: Path) {
            val jdExt  = mock<JdExtractionNode>().apply { whenever(process(any())).doAnswer(pass) }
            val gapAn  = mock<GapAnalysisNode>().apply { whenever(process(any())).doAnswer(pass) }
            val sumRew = mock<SummaryRewriteNode>().apply { whenever(process(any())).doAnswer(pass) }
            val bulRew = mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer(pass) }
            val sklRst = mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer(pass) }
            val atsSc  = mock<AtsScoringNode>().apply { whenever(process(any())).doAnswer(pass) }

            val subgraph = ResumeTailoringSubgraph(jdExt, gapAn, sumRew, bulRew, sklRst, atsSc)
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertTrue(result.tailoringDegradedNodes.isEmpty(), "clean run should record no degraded nodes: ${result.tailoringDegradedNodes}")
        }

        @Test
        @DisplayName("multiple node failures are all recorded")
        fun multipleFailuresRecorded(@TempDir tempDir: Path) {
            val fail: (org.mockito.invocation.InvocationOnMock) -> TailorState = { (it.arguments[0] as TailorState).copy(error = "LLM failed") }
            val jdExt  = mock<JdExtractionNode>().apply { whenever(process(any())).doAnswer(pass) }
            val gapAn  = mock<GapAnalysisNode>().apply { whenever(process(any())).doAnswer(pass) }
            val sumRew = mock<SummaryRewriteNode>().apply { whenever(process(any())).doAnswer(fail) }
            val bulRew = mock<BulletRewriteNode>().apply { whenever(process(any())).doAnswer(fail) }
            val sklRst = mock<SkillsRestructureNode>().apply { whenever(process(any())).doAnswer(pass) }
            val atsSc  = mock<AtsScoringNode>()

            val subgraph = ResumeTailoringSubgraph(jdExt, gapAn, sumRew, bulRew, sklRst, atsSc)
            val result = subgraph.process(tailorInput(tempDir = tempDir))

            assertTrue(result.tailoringDegradedNodes.containsAll(listOf("summary_rewrite", "bullet_rewrite")),
                "both failed nodes should be recorded: ${result.tailoringDegradedNodes}")
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
}
