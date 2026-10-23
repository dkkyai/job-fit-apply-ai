package com.jd.jobbot.jobs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * The same contract file the notifier's ButtonEligibilityContractTest reads: the button a card
 * shows and the button JobBot accepts must be decided by identical rules.
 */
class EligibilityContractTest {
    private fun contract(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            dir.resolve("docker/jobbot/contract/button_eligibility.json").takeIf { Files.exists(it) }?.let { return it }
            dir = dir.parent
        }
        error("button_eligibility.json not found")
    }

    @TestFactory
    fun cases(): List<DynamicTest> {
        val root = Json.parseToJsonElement(Files.readString(contract())).jsonObject
        assertEquals(Eligibility.VERBS, root["verbs"]!!.jsonArray.map { it.jsonPrimitive.content })
        return root["cases"]!!.jsonArray.map { c ->
            val case = c.jsonObject
            DynamicTest.dynamicTest(case["name"]!!.jsonPrimitive.content) {
                val expected = case["eligible"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(expected, Eligibility.eligibleVerbs(case["event"] as JsonObject))
            }
        }
    }
}

/** Apply with a resume Richard sent has its own rule: no tailored resume needed, a posting URL is. */
class ResumeUploadEligibilityTest {
    @kotlin.test.Test
    fun `any completed job with a posting URL qualifies, whatever the pipeline did with it`() {
        for (action in listOf("TAILOR", "OFFER", "SKIP")) {
            kotlin.test.assertTrue(Eligibility.eligibleForResumeUpload(com.jd.jobbot.support.FakeBridge.event(5, action = action)), action)
        }
    }

    @kotlin.test.Test
    fun `a job without a posting URL does not`() {
        kotlin.test.assertFalse(Eligibility.eligibleForResumeUpload(com.jd.jobbot.support.FakeBridge.event(5, jobUrl = null)))
    }

    @kotlin.test.Test
    fun `an event that is not a completion does not`() {
        kotlin.test.assertFalse(Eligibility.eligibleForResumeUpload(kotlinx.serialization.json.buildJsonObject { }))
    }
}
