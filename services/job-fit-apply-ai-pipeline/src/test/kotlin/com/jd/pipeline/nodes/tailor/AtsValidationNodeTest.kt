package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CandidateBackground
import com.jd.pipeline.models.CandidateIdentity
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CareerEntry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AtsValidationNode]. The deterministic core (word-boundary term matching,
 * coverage %, leak detection, doubled words) is exercised directly; the LLM is a stub
 * returning fixed qualitative sub-scores.
 */
@DisplayName("AtsValidationNodeTest")
class AtsValidationNodeTest {

    private val llmStub = LlmCaller {
        """{"seniority_alignment": 80, "quantification": 70, "format_safety": 90, "top_improvements": ["work Espresso into a bullet"]}"""
    }
    private val node = AtsValidationNode(llmStub)

    // ── word-boundary matching ────────────────────────────────────────────────

    @Test
    @DisplayName("termPresent uses word boundaries — Java does not match JavaScript")
    fun termPresentWordBoundaries() {
        assertFalse(node.termPresent("Java", "wrote JavaScript automation"), "substring must not match")
        assertTrue(node.termPresent("Java", "wrote Java and JavaScript automation"))
        assertTrue(node.termPresent("java", "Enterprise Java shop"), "case-insensitive")
    }

    @Test
    @DisplayName("termPresent handles non-word characters (C++, CI/CD) and multi-word phrases")
    fun termPresentSpecialChars() {
        assertTrue(node.termPresent("C++", "media stack in C++ and C#"))
        assertTrue(node.termPresent("CI/CD", "owned CI/CD pipelines"))
        assertTrue(node.termPresent("cross-functional collaboration", "drove cross-functional collaboration daily"))
        assertTrue(node.termPresent("Kotlin Multiplatform (KMP)", "adopted Kotlin Multiplatform (KMP) modules"))
        assertFalse(node.termPresent("Espresso", "used EspressoX wrapper"), "trailing alnum blocks the match")
    }

    // ── doubled words ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("findDoubledWords catches the 'reducing reducing' class, case-insensitively")
    fun doubledWords() {
        assertEquals(listOf("reducing"), node.findDoubledWords("track record of reducing reducing friction"))
        assertEquals(listOf("the"), node.findDoubledWords("The the framework shipped."))
        assertTrue(node.findDoubledWords("clean text with no duplicates").isEmpty())
    }

    // ── deterministic coverage / leak partition ───────────────────────────────

    @Test
    @DisplayName("validateDeterministically computes coverage over reachable keywords, plus missing/unreachable/leaks")
    fun deterministicChecks() {
        // No exact_match_terms → the universe falls back to the atomic must-haves.
        val jd = JdRequirements(
            targetTitle = "Staff SDET",
            // 4 keywords: 2 reachable+present, 1 reachable+absent, 1 unreachable
            mustHave = listOf("Kotlin", "Espresso", "GitHub Actions", "Pact")
        )
        val gap = GapAnalysis(unsupported = listOf("Pact", "gRPC"))
        val output = "Architected Kotlin test frameworks with Espresso coverage gates."

        val checks = node.validateDeterministically(jd, gap, output)

        // reachable = {Kotlin, Espresso, GitHub Actions}; present = {Kotlin, Espresso} → 2/3
        assertEquals(66, checks.coveragePct, "2 of 3 reachable keywords present")
        assertEquals(listOf("GitHub Actions"), checks.missingTerms, "reachable but absent → actionable")
        assertEquals(listOf("Pact"), checks.unreachableTerms, "unsupported keyword can never be claimed")
        assertTrue(checks.leakedTerms.isEmpty())
    }

    @Test
    @DisplayName("coverage is measured over exact_match_terms when present, not long must-have sentences")
    fun coverageUsesAtomicExactTerms() {
        val jd = JdRequirements(
            targetTitle = "Staff SDET",
            mustHave = listOf("7+ years of software engineering experience with a focus on test automation"),
            exactMatchTerms = listOf("Kotlin", "Espresso", "GitHub Actions", "Pact")
        )
        val gap = GapAnalysis(unsupported = listOf("Pact"))
        val output = "Kotlin frameworks, Espresso suites, GitHub Actions pipelines."

        val checks = node.validateDeterministically(jd, gap, output)

        // The long must-have is ignored; universe = the 4 exact terms; reachable = 3; all present.
        assertEquals(100, checks.coveragePct)
        assertTrue(checks.missingTerms.isEmpty())
        assertEquals(listOf("Pact"), checks.unreachableTerms)
    }

    @Test
    @DisplayName("coverage universe is the UNION of exact-match terms and short must-have phrases")
    fun coverageUniverseUnion() {
        val jd = JdRequirements(
            targetTitle = "Staff SDET",
            mustHave = listOf("test automation", "7+ years of software engineering experience with a focus on quality"),
            exactMatchTerms = listOf("Kotlin")
        )
        val gap = GapAnalysis()

        val full = node.validateDeterministically(jd, gap, "Kotlin frameworks for test automation.")
        assertEquals(100, full.coveragePct, "Kotlin AND the short must-have both count; the long sentence is excluded")

        val half = node.validateDeterministically(jd, gap, "Kotlin frameworks.")
        assertEquals(50, half.coveragePct)
        assertEquals(listOf("test automation"), half.missingTerms)
    }

    @Test
    @DisplayName("findStyleWarnings flags overlong bullets and opening verbs used more than twice")
    fun styleWarnings() {
        val state = readyState().copy(
            tailoredCareerHistory = listOf(
                CareerEntry(
                    role = "SDET", company = "Acme", startDate = "2020-01",
                    bullets = listOf(
                        Bullet("A", "Led " + "framework work ".repeat(20)), // > 250 chars
                        Bullet("B", "Led the flaky-test triage rotation"),
                        Bullet("C", "Led adoption across four teams"),
                        Bullet("D", "Built coverage dashboards")
                    )
                )
            )
        )
        val warnings = node.findStyleWarnings(state)
        assertTrue(warnings.any { it.contains("too long") }, "overlong bullet flagged: $warnings")
        assertTrue(warnings.any { it.contains("\"led\"") }, "3× 'Led' flagged: $warnings")
        assertEquals(2, warnings.size)
    }

    @Test
    @DisplayName("an unsupported term appearing in the output is reported as a leak")
    fun leakDetection() {
        val jd = JdRequirements(targetTitle = "SDET", mustHave = listOf("Kotlin"))
        val gap = GapAnalysis(
            supported = listOf(EvidencedTerm("Kotlin", "ev")),
            unsupported = listOf("Pact")
        )
        val checks = node.validateDeterministically(jd, gap, "Kotlin contract testing with Pact suites")
        assertEquals(listOf("Pact"), checks.leakedTerms)
        assertEquals(100, checks.coveragePct)
    }

    // ── end-to-end process() ──────────────────────────────────────────────────

    private fun readyState(summary: String = "Staff SDET with Kotlin and Espresso depth.") = TailorState(
        jdText = "jd", company = "Acme", roleTitle = "Staff SDET",
        jdRequirements = JdRequirements(targetTitle = "Staff SDET", mustHave = listOf("Kotlin", "Espresso")),
        gapAnalysis = GapAnalysis(
            supported = listOf(EvidencedTerm("Kotlin", "ev"), EvidencedTerm("Espresso", "ev")),
            unsupported = listOf("Pact")
        ),
        tailoredSummary = summary,
        tailoredCareerHistory = listOf(
            CareerEntry(
                role = "SDET", company = "Acme", startDate = "2020-01",
                bullets = listOf(Bullet("Frameworks", "Built Espresso suites covering 90% of flows."))
            )
        ),
        restructuredSkills = RestructuredSkills(groupedByCategory = mapOf("Languages" to listOf("Kotlin")))
    )

    @Test
    @DisplayName("process() produces a full AtsReport with LLM sub-scores and weighted overall")
    fun processProducesReport() {
        val result = node.process(readyState())
        val report = result.atsReport

        assertTrue(result.error.isEmpty(), "no error expected: ${result.error}")
        checkNotNull(report)
        assertEquals(100, report.mustHaveCoveragePct)
        assertTrue(report.missingTerms.isEmpty())
        assertTrue(report.leakedUnsupportedTerms.isEmpty())
        assertEquals(80, report.seniorityAlignment)
        assertEquals(listOf("work Espresso into a bullet"), report.topImprovements)
        // 100*0.40 + 80*0.25 + 70*0.20 + 90*0.15 = 40+20+14+13.5 = 87 (no penalties)
        assertEquals(87, report.overallScore)
        assertFalse(report.needsRefinement)
    }

    @Test
    @DisplayName("integrity findings subtract 5 points each from the overall score")
    fun integrityPenalty() {
        // Summary leaks the unsupported term AND doubles a word → two findings.
        val result = node.process(readyState(summary = "Kotlin Espresso and and Pact expertise."))
        val report = checkNotNull(result.atsReport)

        assertEquals(listOf("Pact"), report.leakedUnsupportedTerms)
        assertEquals(listOf("and"), report.doubledWords)
        assertEquals(87 - 10, report.overallScore)
        assertTrue(report.needsRefinement)
    }

    @Test
    @DisplayName("guards: missing prerequisites produce named errors")
    fun guards() {
        val base = readyState()
        assertTrue(node.process(base.copy(jdRequirements = null)).error.contains("jdRequirements"))
        assertTrue(node.process(base.copy(gapAnalysis = null)).error.contains("gapAnalysis"))
        assertTrue(node.process(base.copy(tailoredCareerHistory = null)).error.contains("tailoredCareerHistory"))
        assertTrue(node.process(base.copy(restructuredSkills = null)).error.contains("restructuredSkills"))
    }

    @Test
    @DisplayName("a null (degraded) tailored summary falls back to the base profile summary for scoring")
    fun degradedSummaryFallsBackToBase() {
        val profile = CandidateProfile(
            identity = CandidateIdentity(name = "R", firstName = "R", lastName = "H", email = "e", phone = "p", location = "Seattle"),
            background = CandidateBackground(
                targetTitle = "Staff SDET", yearsExperience = 15,
                summary = "Staff SDET with deep Kotlin and Espresso automation.",
                education = emptyList(), careerHistory = emptyList(),
                coreStrengths = emptyList(), languages = emptyList(), domainExpertise = emptyList()
            ),
            skills = emptyList()
        )
        // No tailored summary, but the base profile carries Kotlin + Espresso.
        val result = node.process(readyState(summary = "ignored").copy(tailoredSummary = null, candidateProfile = profile))
        val report = checkNotNull(result.atsReport)
        assertEquals(100, report.mustHaveCoveragePct, "coverage counts the base summary's Kotlin + Espresso")
        assertTrue(result.error.isEmpty())
    }
}
