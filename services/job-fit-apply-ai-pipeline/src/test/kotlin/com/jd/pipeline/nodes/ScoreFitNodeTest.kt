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
        assertTrue(out.contains("Mobile Automation:") && out.contains("Espresso"))
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
            skills = emptyList()
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
            // Worded for the Telegram card ("Skipped: …"), money in K.
            assertEquals(listOf("Posted pay (max \$150K) is below the \$200K target"), gates)
        }

        @Test
        @DisplayName("money reads the way a card says it")
        fun moneyFormatting() {
            assertEquals("\$85K", ScoreFitNode.money(85_000))
            assertEquals("\$137.5K", ScoreFitNode.money(137_500))
            assertEquals("\$1.2M", ScoreFitNode.money(1_200_000))
            assertEquals("\$950", ScoreFitNode.money(950))
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
    @DisplayName("Hard Gate: Location is Washington state, not Seattle")
    inner class StateLocationGateTests {

        private fun gates(arrangement: String, office: String, officeState: String = "", state: JDState = stateWithProfile()) =
            invokeComputeHardGates(state, workArrangement = arrangement, officeLocation = office, officeState = officeState)

        @Test
        @DisplayName("onsite in a Seattle suburb is home state, not a skip")
        fun onsiteSuburbAllowed() {
            assertTrue(gates("onsite", "Bothell, WA").isEmpty())
            assertTrue(gates("onsite", "Spokane, Washington").isEmpty())
        }

        @Test
        @DisplayName("hybrid outside Washington is skipped")
        fun hybridOutOfStateGated() {
            val g = gates("hybrid", "Merrifield, VA")
            assertTrue(g.single().startsWith("Hybrid in Merrifield, VA — outside WA"), "got: $g")
        }

        @Test
        @DisplayName("a neighborhood name with no state never causes a skip")
        fun neighborhoodFailsOpen() {
            assertTrue(gates("onsite", "South Lake Union").isEmpty())
            assertTrue(gates("hybrid", "United States").isEmpty())
        }

        @Test
        @DisplayName("the model's office_state resolves a neighborhood either way")
        fun officeStateUsed() {
            assertTrue(gates("hybrid", "South Lake Union", officeState = "WA").isEmpty())
            assertTrue(gates("hybrid", "The Loop", officeState = "IL").isNotEmpty())
            assertTrue(gates("onsite", "Shoreditch, London", officeState = "non-US").isNotEmpty())
        }

        @Test
        @DisplayName("multi-location posting that includes Washington is allowed")
        fun multiLocationWithHomeStateAllowed() {
            assertTrue(gates("hybrid", "San Francisco, CA; Seattle, WA").isEmpty())
            assertTrue(gates("hybrid", "San Francisco, CA; Chicago, IL; Salt Lake City, UT").isNotEmpty())
        }

        @Test
        @DisplayName("Washington, DC is not Washington state")
        fun washingtonDcIsNotWa() {
            assertTrue(gates("onsite", "Washington, DC").isNotEmpty())
        }

        @Test
        @DisplayName("falls back to the board's work model and location when the model says unknown")
        fun boardFieldsFallback() {
            val s = stateWithProfile().copy(remotePolicy = "Hybrid", location = "Austin, TX")
            assertTrue(invokeComputeHardGates(s).single().startsWith("Hybrid in Austin, TX"))
        }

        @Test
        @DisplayName("remote from either the board or the model means no location skip")
        fun remoteNeverGated() {
            assertTrue(invokeComputeHardGates(stateWithProfile().copy(remotePolicy = "Remote"),
                workArrangement = "hybrid", officeLocation = "Austin, TX").isEmpty())
            assertTrue(invokeComputeHardGates(stateWithProfile().copy(remotePolicy = "Hybrid", location = "Austin, TX"),
                workArrangement = "remote").isEmpty())
        }
    }

    @Nested
    @DisplayName("Rubric arithmetic: base dimensions + mobile bonus")
    inner class RubricArithmeticTests {
        private val fullBase = mapOf("framework" to 15, "cicd" to 20, "web_api" to 20, "seniority" to 20,
            "stack_overlap" to 10, "location" to 10, "domain" to 5)

        @Test
        @DisplayName("a role with no mobile work can still reach 100")
        fun noMobileNoPenalty() {
            assertEquals(100f, ScoreFitNode().scoreFromDimensions(fullBase + ("mobile" to 0)))
        }

        @Test
        @DisplayName("mobile adds up to 15 on top of the base, and the total caps at 100")
        fun mobileBonusCapped() {
            val partial = fullBase + ("cicd" to 10) + ("seniority" to 10)   // base 80
            assertEquals(95f, ScoreFitNode().scoreFromDimensions(partial + ("mobile" to 15)))
            assertEquals(95f, ScoreFitNode().scoreFromDimensions(partial + ("mobile" to 40)), "bonus clamps to 15")
            assertEquals(100f, ScoreFitNode().scoreFromDimensions(fullBase + ("mobile" to 15)), "total caps at 100")
        }

        @Test
        @DisplayName("out-of-range dimensions clamp to their rubric maximum")
        fun clampsDimensions() {
            val inflated = fullBase + ("framework" to 25) + ("location" to -3)   // → 15 and 0
            assertEquals(90f, ScoreFitNode().scoreFromDimensions(inflated))
        }

        @Test
        @DisplayName("falls back (null) when a base dimension is missing, e.g. the old rubric's keys")
        fun nullWithoutRubricKeys() {
            val oldRubric = mapOf("mobile" to 25, "cicd" to 20, "web_api" to 15, "seniority" to 15,
                "stack_overlap" to 10, "location" to 10, "domain" to 5)
            assertEquals(null, ScoreFitNode().scoreFromDimensions(oldRubric))
        }
    }

    @Test
    @DisplayName("postingDetails passes the board's location, work model, salary and type to the scorer")
    fun postingDetailsBlock() {
        val s = JDState(location = "United States", remotePolicy = "Remote", salaryRange = "\$101K/yr - \$127K/yr", employmentType = "Full-time")
        val block = ScoreFitNode().postingDetails(s)
        assertTrue(block.contains("Location: United States") && block.contains("Work model: Remote") &&
            block.contains("Salary: \$101K/yr") && block.contains("Employment type: Full-time"), block)
        assertEquals("", ScoreFitNode().postingDetails(JDState()))
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
        officeLocation: String = "",
        officeState: String = "",
    ): List<String> = ScoreFitNode().computeHardGates(state, compMin, compMax, workArrangement, officeLocation, officeState)

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
                EducationEntry(degree = "B.S. CS", school = "Test U", location = "Test City, TC", year = "2012")
            ),
            careerHistory = listOf(
                CareerEntry(
                    role = "Senior SDET",
                    company = "Acme",
                    startDate = "2020-01",
                    endDate = "2024-01",
                    bullets = listOf(Bullet("", "KMP framework"))
                )
            ),
            coreStrengths = listOf("Mobile Test Automation"),
            languages = listOf("Kotlin", "Swift"),
            domainExpertise = listOf("retail/commerce")
        ),
        skills = listOf(
            SkillGroup("Primary Stack", listOf("Kotlin", "Swift")),
            SkillGroup("Mobile Automation", listOf("Espresso", "XCUITest")),
            SkillGroup("CI/CD Platforms", listOf("GitHub Actions")),
            SkillGroup("Web & API Automation", listOf("Playwright")),
            SkillGroup("Infrastructure & Observability", listOf("Docker")),
            SkillGroup("Leadership", listOf("Mentoring"))
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
