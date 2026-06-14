package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end scan-stage check against the real captured Built In digest email.
 * Routing (builtin.com → BoardRegistry → BuiltInDigestStrategy) must classify it as a
 * digest and never invoke the recruiter LLM — the injected caller throws if touched.
 */
@DisplayName("ScanEmailNode — Built In digest (real email)")
class ScanEmailNodeBuiltInTest {

    private val throwingLlm = LlmCaller { error("LLM must not be called for a job-board digest") }

    private fun loadFixture(): String =
        javaClass.getResourceAsStream("/emails/builtin_digest_sample.html")!!.readBytes().decodeToString()

    @Test
    @DisplayName("classifies the email as a digest and extracts both job URLs")
    fun scansBuiltInDigest() {
        val html = loadFixture()
        val email = IntakeContext.Email(
            emailId = "bi-real", from = "Built In <support@builtin.com>",
            subject = "New Test Engineer Job Matches",
            rawBody = "", htmlBody = html,
            isRecruiter = false, isDigest = false, isInlineDigest = false,
        )

        val result = ScanEmailNode(llm = throwingLlm).process(JDState(intake = email))

        assertTrue(result.isDigest, "email should be flagged as a digest")
        assertTrue(result.isJobPosting, "digest parent should be a job posting")
        assertEquals(2, result.digestJobs.size, "two job cards expected")

        val urls = result.digestJobs.map { it.jobUrl }.toSet()
        assertEquals(
            setOf(
                "https://builtin.com/job/software-developer-engineer-test/9694258",
                "https://builtin.com/job/software-test-engineer/9666813",
            ),
            urls,
        )

        val stoke = result.digestJobs.first { it.company == "Stoke Space" }
        assertEquals("Software Developer Engineer in Test", stoke.roleTitle)
        assertEquals("Kent, WA", stoke.location)
        assertEquals("onsite", stoke.remotePolicy)
    }
}
