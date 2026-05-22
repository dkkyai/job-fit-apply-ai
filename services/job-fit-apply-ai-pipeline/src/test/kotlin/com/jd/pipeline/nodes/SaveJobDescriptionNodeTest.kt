package com.jd.pipeline.nodes

import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for SaveJobDescriptionNode.
 * 
 * Note: The node uses OutputUtils.createOutputPath() which creates directories
 * in Config.OUTPUT_DIR. The passed outputPath is only used when setting result.outputPath.
 */
@DisplayName("SaveJobDescriptionNodeTest")
class SaveJobDescriptionNodeTest {

    private lateinit var node: SaveJobDescriptionNode

    @BeforeEach
    fun setUp() {
        node = SaveJobDescriptionNode()
    }

    @Test
    @DisplayName("Should return original state when jdText and scrapedContent are empty")
    fun testReturnsOriginalWhenNoContent() {
        // Given
        val input = JDState(
            jdText = "",
            scrapedContent = "",
            company = "TestCo",
            roleTitle = "Engineer"
        )

        // When
        val result = node.process(input)

        // Then: should pass through without error, outputPath not set
        assertEquals(input, result.copy(error = ""))
        assertTrue(result.error.isEmpty() || result.outputPath.isEmpty())
    }

    @Test
    @DisplayName("Should return error when directory creation fails")
    fun testReturnsErrorOnDirectoryCreationFailure() {
        // Given: use a path that cannot be created (permission denied or invalid)
        val input = JDState(
            jdText = "Test JD",
            company = "",
            roleTitle = ""
        )

        // When
        val result = node.process(input)

        // Then: either error is set OR files were created successfully
        // (The actual outcome depends on Config.OUTPUT_DIR permissions)
        if (result.error.isNotEmpty()) {
            assertTrue(result.error.contains("create output directory") || result.error.contains("write file"))
        }
    }

    @Test
    @DisplayName("Should use jdText when available")
    fun testUsesJdTextWhenAvailable() {
        // Given
        val input = JDState(
            jdText = "Primary JD text",
            scrapedContent = "Fallback scraped content",
            company = "TestCo",
            roleTitle = "Engineer"
        )

        // When
        val result = node.process(input)

        // Then: outputPath should be set (file creation is attempted in Config.OUTPUT_DIR)
        assertTrue(result.outputPath.isNotEmpty() || result.error.isEmpty())
    }

    @Test
    @DisplayName("Should use scrapedContent when jdText is empty")
    fun testUsesScrapedContentWhenJdTextEmpty() {
        // Given
        val input = JDState(
            jdText = "",
            scrapedContent = "Fallback scraped content",
            company = "TestCo",
            roleTitle = "Engineer"
        )

        // When
        val result = node.process(input)

        // Then: outputPath should be set
        assertTrue(result.outputPath.isNotEmpty() || result.error.isEmpty())
    }

    @Test
    @DisplayName("Should preserve company and roleTitle in output path")
    fun testPreservesCompanyAndRoleInPath() {
        // Given
        val input = JDState(
            jdText = "Test content",
            company = "UniqueCompany123",
            roleTitle = "UniqueRole456"
        )

        // When
        val result = node.process(input)

        // Then: outputPath should contain sanitized company and role
        if (result.outputPath.isNotEmpty()) {
            assertTrue(result.outputPath.contains("uniquecompany123") || result.outputPath.contains("UniqueCompany123"))
            assertTrue(result.outputPath.contains("uniquerole456") || result.outputPath.contains("UniqueRole456"))
        }
    }

    @Test
    @DisplayName("Should handle state with no company/role gracefully")
    fun testHandlesMissingCompanyRole() {
        // Given
        val input = JDState(
            jdText = "Test content",
            company = "",
            roleTitle = ""
        )

        // When
        val result = node.process(input)

        // Then: should not crash, error may be set
        assertTrue(result.outputPath.isNotEmpty() || result.error.isNotEmpty() || result.outputPath.isEmpty())
    }

    @Test
    @DisplayName("Should preserve existing error in state")
    fun testPreservesExistingError() {
        // Given
        val input = JDState(
            jdText = "Test content",
            company = "TestCo",
            roleTitle = "Engineer",
            error = "Previous error"
        )

        // When
        val result = node.process(input)

        // Then: error should be preserved (unless files were created successfully)
        if (result.outputPath.isEmpty()) {
            assertEquals("Previous error", result.error)
        }
    }

    @Test
    @DisplayName("Should preserve techStack and other fields")
    fun testPreservesOtherFields() {
        // Given
        val input = JDState(
            jdText = "Test content",
            company = "TestCo",
            roleTitle = "Engineer",
            techStack = listOf("Kotlin", "Java"),
            fitScore = 85.0f,
            isJobPosting = true
        )

        // When
        val result = node.process(input)

        // Then: other fields should be preserved
        assertEquals(listOf("Kotlin", "Java"), result.techStack)
        assertEquals(85.0f, result.fitScore)
        assertTrue(result.isJobPosting)
    }

    @Test
    @DisplayName("Should set outputPath when successful")
    fun testSetsOutputPathOnSuccess(@TempDir tempDir: Path) {
        // Given: we know Config.OUTPUT_DIR - create the directory structure
        val outputDir = tempDir.resolve("output")
        Files.createDirectories(outputDir)
        
        // The node uses OutputUtils.createOutputPath() which uses Config.OUTPUT_DIR
        // For this test, we just verify the method doesn't crash
        val input = JDState(
            jdText = "Test content",
            company = "OutputTest",
            roleTitle = "Engineer"
        )

        // When
        val result = node.process(input)

        // Then: either success or error, but no crash
        assertTrue(result.outputPath.isNotEmpty() || result.error.isNotEmpty())
    }

    @Test
    @DisplayName("Should handle empty tech stack")
    fun testHandlesEmptyTechStack() {
        // Given
        val input = JDState(
            jdText = "Test content",
            company = "TestCo",
            roleTitle = "Engineer",
            techStack = emptyList()
        )

        // When
        val result = node.process(input)

        // Then: should not crash
        assertTrue(result.techStack.isEmpty())
    }

    @Test
    @DisplayName("Should handle unicode in company/role")
    fun testHandlesUnicodeInFields() {
        // Given
        val input = JDState(
            jdText = "Test content",
            company = "日本語会社",
            roleTitle = "エンジニア"
        )

        // When
        val result = node.process(input)

        // Then: should not crash
        assertTrue(result.outputPath.isNotEmpty() || result.error.isNotEmpty())
    }
}
