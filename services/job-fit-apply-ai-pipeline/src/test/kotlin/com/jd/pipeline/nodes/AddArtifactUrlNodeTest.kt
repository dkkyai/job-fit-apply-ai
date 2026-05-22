package com.jd.pipeline.nodes

import com.jd.pipeline.config.Config
import com.jd.pipeline.fixtures.TestJDStateFactory
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for AddArtifactUrlNode.
 */
@ExtendWith(MockitoExtension::class)
class AddArtifactUrlNodeTest {

    private lateinit var node: AddArtifactUrlNode

    @BeforeEach
    fun setUp() {
        node = AddArtifactUrlNode()
    }

    @Test
    fun shouldReturnInputWhenOutputPathIsNull() {
        // Given: state with null output path
        val input = TestJDStateFactory.createTailoringState().copy(
            outputPath = ""  // Empty string simulates null/unset
        )

        // When
        val result = node.process(input)

        // Then: should not set artifact URL when output path is null/empty
        // (Empty string is returned when copying null in JDState)
        assertTrue(result.artifactUrl == null || result.artifactUrl.isEmpty())
    }

    @Test
    fun shouldReturnInputWhenOutputPathIsEmpty() {
        // Given: state with empty output path
        val input = TestJDStateFactory.createTailoringState().copy(
            outputPath = ""
        )

        // When
        val result = node.process(input)

        // Then: should not set artifact URL when output path is empty
        assertTrue(result.artifactUrl == null || result.artifactUrl.isEmpty())
    }

    @Test
    fun shouldSetArtifactUrlFromOutputPath() {
        // This test requires ARTIFACT_BASE_URL to be configured in the environment.
        // Skip gracefully when it isn't (e.g. in CI without .env).
        Assumptions.assumeTrue(
            Config.ARTIFACT_BASE_URL.isNotEmpty(),
            "Skipping: ARTIFACT_BASE_URL not configured"
        )

        // Given: state with output path
        val input = TestJDStateFactory.createTailoringState().copy(
            outputPath = "output/test_company_software_engineer"
        )

        // When
        val result = node.process(input)

        // Then: artifact URL should be set based on folder name
        assertNotNull(result.artifactUrl)
        assertTrue(result.artifactUrl.contains("test_company_software_engineer"))
    }

    @Test
    fun shouldPreserveExistingFields() {
        // Given: state with all fields populated
        val input = TestJDStateFactory.createHighScoredState().copy(
            outputPath = "output/acme_corp_engineer"
        )

        // When
        val result = node.process(input)

        // Then: existing fields should be preserved
        assertEquals(input.company, result.company)
        assertEquals(input.roleTitle, result.roleTitle)
        assertEquals(input.emailIntake?.emailId, result.emailIntake?.emailId)
    }

    @Test
    fun shouldHandlePathWithSpaces() {
        // This test requires ARTIFACT_BASE_URL to be configured in the environment.
        Assumptions.assumeTrue(
            Config.ARTIFACT_BASE_URL.isNotEmpty(),
            "Skipping: ARTIFACT_BASE_URL not configured"
        )

        // Given: output path with spaces in folder name
        val input = JDState(
            outputPath = "output/Acme Corp_Software Engineer",
            intake = IntakeContext.Email(
                emailId = "",
                from = "",
                subject = "",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            )
        )

        // When
        val result = node.process(input)

        // Then: URL should encode spaces
        assertNotNull(result.artifactUrl)
        assertTrue(result.artifactUrl.contains("%20"))
    }

    @Test
    fun shouldStripBasePathWhenConfigured() {
        // This test requires both ARTIFACT_BASE_URL and ARTIFACT_BASE_PATH to be configured.
        Assumptions.assumeTrue(
            Config.ARTIFACT_BASE_URL.isNotEmpty() && Config.ARTIFACT_BASE_PATH.isNotEmpty(),
            "Skipping: ARTIFACT_BASE_URL or ARTIFACT_BASE_PATH not configured"
        )

        // Given: output path that includes the base path prefix
        val input = JDState(
            outputPath = Config.ARTIFACT_BASE_PATH + "/20260416_085307_hyland_test_engineer",
            intake = IntakeContext.Email(
                emailId = "",
                from = "",
                subject = "",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            )
        )

        // When
        val result = node.process(input)

        // Then: artifact URL should contain only the folder name, not the base path
        assertNotNull(result.artifactUrl)
        assertTrue(result.artifactUrl.contains("20260416_085307_hyland_test_engineer"))
        // URL should NOT contain the base path prefix
        val basePathSegments = Config.ARTIFACT_BASE_PATH.split("/").filter { it.isNotEmpty() }
        for (segment in basePathSegments) {
            assertTrue(
                !result.artifactUrl.contains("/$segment/"),
                "Artifact URL should not contain base path segment: $segment"
            )
        }
    }

    @Test
    fun shouldHandleOutputPathWithoutBasePathPrefix() {
        // This test requires ARTIFACT_BASE_URL to be configured in the environment.
        Assumptions.assumeTrue(
            Config.ARTIFACT_BASE_URL.isNotEmpty(),
            "Skipping: ARTIFACT_BASE_URL not configured"
        )

        // Given: output path that doesn't start with base path (or base path is not set)
        val input = JDState(
            outputPath = "output/test_company_software_engineer",
            intake = IntakeContext.Email(
                emailId = "",
                from = "",
                subject = "",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            )
        )

        // When
        val result = node.process(input)

        // Then: artifact URL should be built from folder name only
        assertNotNull(result.artifactUrl)
        assertTrue(result.artifactUrl.contains("test_company_software_engineer"))
        // Should NOT contain "output/output" duplicate path
        assertTrue(!result.artifactUrl.contains("output/output"))
    }
}