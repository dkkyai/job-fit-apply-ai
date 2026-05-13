package com.jd.pipeline.nodes

import com.jd.pipeline.models.*
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Tests for ScoreFitNode — profile rendering, evidence parsing, and hard-gate logic.
 * The LLM call itself is covered by integration tests; everything here is pure-function.
 */
@DisplayName("ScoreFitNodeTest")
class ScoreFitNodeTest {

    // ── Profile rendering ─────────────────────────────────────────────────────

    @Test
    @DisplayName("renderCandidateProfile includes identity, target title, career history, and preferences")
    fun rendersCoreFields() {
        val profile = sampleProfile()
        val out = ScoreFitNode.renderCandidateProfile(profile)

        assertTrue(out.contains("Jane Doe"), "expected name; got:\n$out")
        assertTrue(out.contains("Staff SDET"), "expected target title")
        assertTrue(out.contains("Seattle, WA"), "expected location")
        assertTrue(out.contains("12+ years"))
        assertTrue(out.contains("B.S. CS"))
        // Career history table
        assertTrue(out.contains("| Senior SDET | Acme | — | 2020-01 – 2024-01 |"), "expected career row with location column; got:\n$out")
        // Skills section
        assertTrue(out.contains("Mobile automation:") && out.contains("Espresso"))
        // Preferences block
        assertTrue(out.contains("Target total compensation"))
        assertTrue(out.contains("\$200,000"))
        assertTrue(out.contains("US Citizen"))
        assertTrue(out.contains("Open to contract roles:** yes"))
    }

    @Test
    @DisplayName("renderCandidateProfile leaves no unresolved placeholder braces")
    fun noUnresolvedBraces() {
        val out = ScoreFitNode.renderCandidateProfile(sampleProfile())
        assertFalse(out.contains("{{"), "rendered profile should not contain template markers")
        assertFalse(out.contains("}}"))
    }

    @Test
    @DisplayName("renderCandidateProfile gracefully handles empty optional collections")
    fun handlesEmptyCollections() {
        val profile = sampleProfile().copy(
            background = sampleProfile().background.copy(
                careerHistory = emptyList(),
                coreStrengths = emptyList(),
                languages = emptyList(),
                domainExpertise = emptyList()
            ),
            skills = CandidateSkills(
                primaryStack = emptyList(),
                mobileAutomation = emptyList(),
                ciCdPlatforms = emptyList(),
                webApiAutomation = emptyList(),
                infrastructureObservability = emptyList(),
                leadershipAbilities = emptyList()
            )
        )
        val out = ScoreFitNode.renderCandidateProfile(profile)
        assertTrue(out.contains("Jane Doe"))
        assertFalse(out.contains("Career History"), "should omit empty career table")
        assertFalse(out.contains("Languages:"), "should omit empty languages line")
    }

    @Test
    @DisplayName("DEFAULT_SKILL_PROMPT carries the placeholder so substitution works without the resource file")
    fun defaultPromptHasPlaceholder() {
        assertTrue(
            ScoreFitNode.DEFAULT_SKILL_PROMPT.contains(ScoreFitNode.CANDIDATE_PROFILE_PLACEHOLDER),
            "fallback prompt must include {{CANDIDATE_PROFILE}} for runtime substitution"
        )
    }

    // ── Hard-gate logic ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Hard Gate: Compensation")
    inner class CompHardGateTests {

        @Test
        @DisplayName("flags gate when posted max is below target TC")
        fun flagsWhenBelowTarget() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(minimumTotalCompensation = "\$200,000")
            )
            val gates = invokeComputeHardGates(state, compMin = 120_000, compMax = 150_000)
            assertTrue(gates.any { it.contains("below target") }, "expected gate; got: $gates")
        }

        @Test
        @DisplayName("no gate when posted max meets or exceeds target TC")
        fun noGateWhenMeetsTarget() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(minimumTotalCompensation = "\$200,000")
            )
            val gates = invokeComputeHardGates(state, compMin = 180_000, compMax = 250_000)
            assertTrue(gates.isEmpty(), "unexpected gates: $gates")
        }

        @Test
        @DisplayName("no gate when posted comp is null (not disclosed)")
        fun noGateWhenCompUnknown() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(minimumTotalCompensation = "\$200,000")
            )
            val gates = invokeComputeHardGates(state, compMin = null, compMax = null)
            assertTrue(gates.isEmpty(), "should not flag when comp is undisclosed")
        }

        @Test
        @DisplayName("no gate when profile has no target TC set")
        fun noGateWhenNoTargetTc() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(minimumTotalCompensation = null)
            )
            val gates = invokeComputeHardGates(state, compMin = 100_000, compMax = 120_000)
            assertTrue(gates.isEmpty(), "should not flag when no target TC configured")
        }
    }

    @Nested
    @DisplayName("Hard Gate: Location")
    inner class LocationHardGateTests {

        @Test
        @DisplayName("flags gate when onsite outside home city and no relocation")
        fun flagsOnsiteOutsideHomeCity() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(willingToRelocate = false)
            )
            val gates = invokeComputeHardGates(state, workArrangement = "onsite", officeLocation = "New York, NY")
            assertTrue(gates.any { it.contains("Onsite-only") }, "expected gate; got: $gates")
        }

        @Test
        @DisplayName("no gate when onsite in home city")
        fun noGateOnsiteInHomeCity() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(willingToRelocate = false)
            )
            val gates = invokeComputeHardGates(state, workArrangement = "onsite", officeLocation = "Seattle, WA")
            assertTrue(gates.isEmpty(), "should not flag onsite in home city; got: $gates")
        }

        @Test
        @DisplayName("no gate when arrangement is remote regardless of location")
        fun noGateForRemote() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(willingToRelocate = false)
            )
            val gates = invokeComputeHardGates(state, workArrangement = "remote", officeLocation = "New York, NY")
            assertTrue(gates.isEmpty(), "remote should never trigger location gate")
        }

        @Test
        @DisplayName("no gate when candidate is willing to relocate")
        fun noGateWhenWillingToRelocate() {
            val state = stateWithProfile(
                preferences = sampleProfile().preferences.copy(willingToRelocate = true)
            )
            val gates = invokeComputeHardGates(state, workArrangement = "onsite", officeLocation = "Austin, TX")
            assertTrue(gates.isEmpty(), "should not flag when willing to relocate")
        }
    }

    @Nested
    @DisplayName("Hard Gate: No profile")
    inner class NoProfileTests {

        @Test
        @DisplayName("returns empty gates when candidateProfile is null")
        fun noGatesWithoutProfile() {
            val state = JDState(jdText = "some JD", candidateProfile = null)
            val gates = invokeComputeHardGates(state, compMax = 100_000, workArrangement = "onsite", officeLocation = "Chicago, IL")
            assertTrue(gates.isEmpty(), "should return no gates when profile is absent")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun invokeComputeHardGates(
        state: JDState,
        compMin: Int? = null,
        compMax: Int? = null,
        workArrangement: String = "unknown",
        officeLocation: String = ""
    ): List<String> {
        // computeHardGates is private; test via reflection for isolation
        val method = ScoreFitNode::class.java.getDeclaredMethod(
            "computeHardGates",
            JDState::class.java,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(ScoreFitNode(), state, compMin, compMax, workArrangement, officeLocation) as List<String>
    }

    private fun stateWithProfile(preferences: CandidatePreferences = sampleProfile().preferences): JDState =
        JDState(
            jdText = "some JD",
            candidateProfile = sampleProfile().copy(preferences = preferences)
        )

    private fun sampleProfile(): CandidateProfile = CandidateProfile(
        identity = CandidateIdentity(
            name = "Jane Doe",
            firstName = "Jane",
            lastName = "Doe",
            email = "jane@example.com",
            phone = "555-1234",
            location = "Seattle, WA"
        ),
        background = CandidateBackground(
            targetTitle = "Staff SDET",
            yearsExperience = 12,
            education = listOf(
                EducationEntry(degree = "B.S. CS", school = "Test U", location = "Test City, TC", startDate = "2008-09", endDate = "2012-05")
            ),
            careerHistory = listOf(
                CareerEntry(
                    role = "Senior SDET",
                    company = "Acme",
                    startDate = "2020-01",
                    endDate = "2024-01",
                    bullets = listOf("KMP framework")
                )
            ),
            coreStrengths = listOf("Mobile Test Automation"),
            languages = listOf("Kotlin", "Swift"),
            domainExpertise = listOf("retail/commerce")
        ),
        skills = CandidateSkills(
            primaryStack = listOf("Kotlin", "Swift"),
            mobileAutomation = listOf("Espresso", "XCUITest"),
            ciCdPlatforms = listOf("GitHub Actions"),
            webApiAutomation = listOf("Playwright"),
            infrastructureObservability = listOf("Docker"),
            leadershipAbilities = listOf("Mentoring")
        ),
        preferences = CandidatePreferences(
            willingToRelocate = false,
            relocationNotes = "Prefer Seattle",
            visaStatus = "US Citizen",
            visaSponsorshipRequired = false,
            preferredWorkArrangement = "Remote-first; hybrid Seattle",
            minimumTotalCompensation = "$200,000",
            openToContractRoles = true,
            minimumContractRateHourly = 85
        )
    )
}
