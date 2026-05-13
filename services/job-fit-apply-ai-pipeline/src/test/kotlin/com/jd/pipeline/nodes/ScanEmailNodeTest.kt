package com.jd.pipeline.nodes

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Non-LLM tests for [ScanEmailNode] — pure logic methods that don't need a live backend.
 */
@DisplayName("ScanEmailNodeTest")
class ScanEmailNodeTest {

    private val node = ScanEmailNode()

    @Test
    @DisplayName("extractEmailAddress extracts address from angle-bracket form")
    fun extractEmailAddressAngle() {
        val result = invoke(node, "extractEmailAddress", "User Name <user@example.com>")
        assertEquals("user@example.com", result)
    }

    @Test
    @DisplayName("extractEmailAddress falls back to raw string when no angle brackets")
    fun extractEmailAddressFallback() {
        val result = invoke(node, "extractEmailAddress", "user@example.com")
        assertEquals("user@example.com", result)
    }

    @Test
    @DisplayName("extractDomain extracts domain from email address")
    fun extractDomain() {
        assertEquals("example.com", invoke(node, "extractDomain", "user@example.com"))
    }

    @Test
    @DisplayName("extractDomain returns empty for invalid input")
    fun extractDomainInvalid() {
        assertEquals("", invoke(node, "extractDomain", "not-an-email"))
        assertEquals("", invoke(node, "extractDomain", ""))
    }

    @Test
    @DisplayName("isJobBoardDomain returns true for known board domains")
    fun isJobBoardDomainKnown() {
        assertTrue(invokeB(node, "isJobBoardDomain", "linkedin.com"))
        assertTrue(invokeB(node, "isJobBoardDomain", "glassdoor.com"))
    }

    @Test
    @DisplayName("isJobBoardDomain returns false for unknown or empty domains")
    fun isJobBoardDomainUnknown() {
        assertFalse(invokeB(node, "isJobBoardDomain", "example.com"))
        assertFalse(invokeB(node, "isJobBoardDomain", ""))
        assertFalse(invokeB(node, "isJobBoardDomain", null))
    }

    @Test
    @DisplayName("preprocessEmailBody strips HTML tags and collapses whitespace")
    fun preprocessStripsHtml() {
        val body = "<html><body>  Hello   World  \n\n\n\nNext  \t  line</body></html>"
        val result = invoke(node, "preprocessEmailBody", body)
        assertFalse(result.contains("<"))
        assertFalse(result.contains(">"))
        assertTrue(result.contains("Hello World"))
        assertTrue(result.contains("\n\nNext line"))
    }

    @Test
    @DisplayName("preprocessEmailBody truncates to EMAIL_BODY_MAX_CHARS")
    fun preprocessTruncates() {
        val longBody = "word ".repeat(10_000)
        val result = invoke(node, "preprocessEmailBody", longBody)
        assertTrue(result.length <= ScanEmailNode.EMAIL_BODY_MAX_CHARS)
    }

    @Test
    @DisplayName("preprocessEmailBody handles null input")
    fun preprocessNull() {
        assertEquals("", invokeNull(node, "preprocessEmailBody"))
    }

    @Test
    @DisplayName("JOB_SIGNAL_PATTERN matches job-signal keywords")
    fun jobSignalMatches() {
        val signals = listOf(
            "engineer", "developer", "dev", "position", "role", "apply",
            "compensation", "salary", "remote", "hybrid",
            "qualifications", "responsibilities", "requirements",
            "qa", "qe", "sdet", "swe", "test", "manager", "lead", "senior", "staff", "principal"
        )
        signals.forEach { keyword ->
            assertTrue(
                ScanEmailNode.JOB_SIGNAL_PATTERN.matcher(keyword).find(),
                "'$keyword' should match JOB_SIGNAL_PATTERN"
            )
        }
    }

    @Test
    @DisplayName("JOB_SIGNAL_PATTERN does not match non-job content")
    fun jobSignalNoMatch() {
        val nonSignals = listOf(
            "hello world", "lunch invitation", "meeting minutes", "weather report"
        )
        nonSignals.forEach { text ->
            assertFalse(
                ScanEmailNode.JOB_SIGNAL_PATTERN.matcher(text).find(),
                "'$text' should NOT match JOB_SIGNAL_PATTERN"
            )
        }
    }

    @Test
    @DisplayName("process returns input unchanged when emailIntake is null")
    fun processReturnsInputWhenNoEmail() {
        val input = JDState(intake = null)
        val result = node.process(input)
        assertEquals(input, result)
    }

    @Test
    @DisplayName("process returns input unchanged when already marked as job posting")
    fun processReturnsInputWhenAlreadyJobPosting() {
        val input = JDState(
            intake = IntakeContext.Email(
                emailId = "1", subject = "", from = "a@b.com", rawBody = "", htmlBody = "",
                isRecruiter = false, isDigest = false, isInlineDigest = false
            ),
            isJobPosting = true
        )
        val result = node.process(input)
        assertEquals(input, result)
    }

    // ── reflection helpers ───────────────────────────────────────────────────

    private fun invoke(node: ScanEmailNode, methodName: String, arg: String): String {
        val m = ScanEmailNode::class.java.getDeclaredMethod(methodName, String::class.java)
        m.isAccessible = true
        return m.invoke(node, arg) as String
    }

    private fun invokeNull(node: ScanEmailNode, methodName: String): String {
        val m = ScanEmailNode::class.java.getDeclaredMethod(methodName, String::class.java)
        m.isAccessible = true
        return m.invoke(node, null) as String
    }

    private fun invokeB(node: ScanEmailNode, methodName: String, arg: String?): Boolean {
        val paramType = if (arg == null) String::class.java else String::class.java
        val m = ScanEmailNode::class.java.getDeclaredMethod(methodName, paramType)
        m.isAccessible = true
        return m.invoke(node, arg) as Boolean
    }
}
