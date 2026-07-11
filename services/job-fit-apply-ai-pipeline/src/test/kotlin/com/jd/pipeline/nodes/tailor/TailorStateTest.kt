package com.jd.pipeline.nodes.tailor

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [TailorState].
 */
@DisplayName("TailorStateTest")
class TailorStateTest {

    @Test
    @DisplayName("copy(error = ...) propagates error string correctly")
    fun errorPropagation() {
        val base = makeTailorState()
        val withError = base.copy(error = "Something went wrong")
        assertEquals("Something went wrong", withError.error)
    }

    @Test
    @DisplayName("progressive outputs default to null")
    fun progressiveDefaults() {
        val state = makeTailorState()
        assertNull(state.jdRequirements)
        assertNull(state.gapAnalysis)
        assertNull(state.tailoredSummary)
        assertNull(state.tailoredCareerHistory)
        assertNull(state.tailoredProjects)
        assertNull(state.bulletMeta)
        assertNull(state.tailoredBullets)
        assertNull(state.restructuredSkills)
        assertNull(state.atsReport)
    }

    private fun makeTailorState() = TailorState(
        jdText = "JD text",
        candidateProfile = null,
        fitScore = 0f,
        strengths = emptyList(),
        gaps = emptyList(),
        company = "Acme",
        roleTitle = "Engineer",
        trackId = 0
    )
}
