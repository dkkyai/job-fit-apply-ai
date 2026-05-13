package com.jd.pipeline.utils

import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for OutputUtils - utility class for output directory operations.
 */
class OutputUtilsTest {

    @Nested
    @DisplayName("sanitizeFileName Tests")
    inner class SanitizeFileNameTests {

        @Test
        @DisplayName("Test sanitizeFileName removes filesystem unsafe characters")
        fun testSanitizeFileNameRemovesUnsafeChars() {
            val input = "Test / \\ : * ? \" < > | Corp"
            val result = OutputUtils.sanitizeFileName(input)
            assertEquals("test_corp", result)
        }

        @Test
        @DisplayName("Test sanitizeFileName removes control characters")
        fun testSanitizeFileNameRemovesControlChars() {
            val input = "Company\nName\tWith\rControl"
            val result = OutputUtils.sanitizeFileName(input)
            assertEquals("company_name_with_control", result)
        }

        @Test
        @DisplayName("Test sanitizeFileName collapses multiple underscores")
        fun testSanitizeFileNameCollapsesUnderscores() {
            val input = "Senior   Software   Engineer"
            val result = OutputUtils.sanitizeFileName(input)
            assertEquals("senior_software_engineer", result)
        }

        @Test
        @DisplayName("Test sanitizeFileName trims leading and trailing underscores")
        fun testSanitizeFileNameTrimsUnderscores() {
            val input = "_LeadingTrailing_"
            val result = OutputUtils.sanitizeFileName(input)
            assertEquals("leadingtrailing", result)
        }

        @Test
        @DisplayName("Test sanitizeFileName preserves alphanumeric and hyphens")
        fun testSanitizeFileNamePreservesSafeChars() {
            val input = "Company-123_ABC"
            val result = OutputUtils.sanitizeFileName(input)
            assertEquals("company-123_abc", result)
        }

        @Test
        @DisplayName("Test sanitizeFileName returns unknown for blank input")
        fun testSanitizeFileNameBlankInput() {
            assertEquals("unknown", OutputUtils.sanitizeFileName(""))
            assertEquals("unknown", OutputUtils.sanitizeFileName("   "))
            assertEquals("unknown", OutputUtils.sanitizeFileName("___"))
        }

        @Test
        @DisplayName("Test sanitizeFileName with lowercase=false")
        fun testSanitizeFileNameNoLowercase() {
            val input = "Acme Corp"
            val result = OutputUtils.sanitizeFileName(input, lowercase = false)
            assertEquals("Acme_Corp", result)
        }
    }

    @Nested
    @DisplayName("createOutputPath Tests")
    inner class CreateOutputPathTests {

        @Test
        @DisplayName("Test createOutputPath generates correct format")
        fun testCreateOutputPathFormat() {
            // This test will generate a timestamp-based path
            // We verify the structure but not exact values due to timestamp
            val path = OutputUtils.createOutputPath("Acme Corp", "Software Engineer")

            val pathStr = path.toString()
            // Path may be absolute, but should contain output directory and company/role
            assertTrue(pathStr.contains("output"), "Path should contain 'output' directory")
            assertTrue(pathStr.contains("acme_corp"), "Path should contain slugified company")
            assertTrue(pathStr.contains("software_engineer"), "Path should contain slugified role")
        }

        @Test
        @DisplayName("Test createOutputPath with special characters in company")
        fun testCompanySlugging() {
            val path = OutputUtils.createOutputPath("Test & Co.", "Engineer")

            val fileName = path.fileName.toString()
            // Verify the path is created (specific format may vary)
            assertNotNull(fileName, "Path should not be null")
            assertTrue(fileName.isNotEmpty(), "Path should not be empty")
            assertTrue(fileName.contains("test_co"), "Special chars should be sanitized")
        }

        @Test
        @DisplayName("Test createOutputPath sanitizes filesystem unsafe characters")
        fun testCreateOutputPathSanitizesUnsafeChars() {
            val path = OutputUtils.createOutputPath("Corp / \\ : * ? \" < > |", "Role / \\ : * ? \" < > |")

            val fileName = path.fileName.toString()
            assertTrue(fileName.contains("corp"), "Unsafe chars in company should be sanitized")
            assertTrue(fileName.contains("role"), "Unsafe chars in role should be sanitized")
            assertTrue(!fileName.contains("/"), "Filename should not contain raw slash")
            assertTrue(!fileName.contains("\\"), "Filename should not contain raw backslash")
            assertTrue(!fileName.contains(":"), "Filename should not contain raw colon")
            assertTrue(!fileName.contains("*"), "Filename should not contain raw asterisk")
            assertTrue(!fileName.contains("?"), "Filename should not contain raw question mark")
            assertTrue(!fileName.contains("\""), "Filename should not contain raw quote")
            assertTrue(!fileName.contains("<"), "Filename should not contain raw less-than")
            assertTrue(!fileName.contains(">"), "Filename should not contain raw greater-than")
            assertTrue(!fileName.contains("|"), "Filename should not contain raw pipe")
        }

        @Test
        @DisplayName("Test createOutputPath with multiple underscores in role")
        fun testRoleSlugging() {
            val path = OutputUtils.createOutputPath("Company", "Senior Software Engineer")

            val pathStr = path.toString()
            // Multiple underscores should be collapsed to single
            assertTrue(pathStr.contains("senior_software_engineer"), "Role should be slugified")
        }

        @Test
        @DisplayName("Test createOutputPath with numbers")
        fun testCreateOutputPathWithNumbers() {
            val path = OutputUtils.createOutputPath("Company123", "Engineer Level 2")

            val pathStr = path.toString()
            assertTrue(pathStr.contains("company123"), "Numbers should be preserved")
            assertTrue(pathStr.contains("engineer_level_2"), "Numbers in role should be preserved")
        }
    }

    @Nested
    @DisplayName("getOutputDirectory Tests")
    inner class GetOutputDirectoryTests {

        @Test
        @DisplayName("Test getOutputDirectory returns existing path from state")
        fun testGetOutputDirectoryFromState() {
            val state = JDState(
                outputPath = "/custom/output/path"
            )

            val path = OutputUtils.getOutputDirectory(state)

            assertEquals("/custom/output/path", path.toString())
        }

        @Test
        @DisplayName("Test getOutputDirectory creates new path when not in state")
        fun testGetOutputDirectoryCreatesNew() {
            val state = JDState(
                company = "New Company",
                roleTitle = "New Role"
            )

            val path = OutputUtils.getOutputDirectory(state)

            val pathStr = path.toString()
            assertTrue(pathStr.contains("new_company"), "Path should contain company")
            assertTrue(pathStr.contains("new_role"), "Path should contain role")
        }

        @Test
        @DisplayName("Test getOutputDirectory with empty output path")
        fun testGetOutputDirectoryEmptyPath() {
            val state = JDState(
                outputPath = "",
                company = "Company",
                roleTitle = "Role"
            )

            val path = OutputUtils.getOutputDirectory(state)

            // Should create new path when empty string
            val pathStr = path.toString()
            assertTrue(pathStr.contains("company"), "Path should contain company")
        }

        @Test
        @DisplayName("Test getOutputDirectory with null output path")
        fun testGetOutputDirectoryNullPath() {
            val state = JDState(
                outputPath = "",  // Empty string instead of null
                company = "TestCompany",
                roleTitle = "TestRole"
            )

            val path = OutputUtils.getOutputDirectory(state)

            val pathStr = path.toString()
            assertTrue(pathStr.contains("testcompany"), "Path should contain company")
        }
    }
}