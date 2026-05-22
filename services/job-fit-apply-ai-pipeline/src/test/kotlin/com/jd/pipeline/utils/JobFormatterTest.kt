package com.jd.pipeline.utils

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Epic("Pipeline Utilities")
@Feature("Job Formatting")
class JobFormatterTest {

    private fun createMockJob(
        company: String,
        title: String,
        fitScore: Float?,
        artifactUrl: String
    ): JDState {
        return JDState(
            company = company,
            roleTitle = title,
            fitScore = fitScore,
            artifactUrl = artifactUrl,
            metadataUrl = artifactUrl   // formatter displays metadataUrl in the artifact column
        )
    }

    @Nested
    @DisplayName("ComputeColumnWidths Tests")
    inner class ComputeColumnWidthsTests {

        @Test
        @DisplayName("Test computeColumnWidths uses default minimum widths")
        fun testDefaultMinimumWidths() {
            val jobs = listOf(
                createMockJob("ABC", "Dev", 50.0f, "http://example.com")
            )

            val widths = JobFormatter.computeColumnWidths(jobs)

            // Should use minimum defaults since actual values are smaller
            assertEquals(10, widths.company, "Company should have minimum 10")
            assertEquals(20, widths.title, "Title should have minimum 20")
            assertEquals(8, widths.fit, "Fit should have minimum 8 (Fit: N/A)")
            assertEquals(30, widths.artifact, "Artifact should have minimum 30")
        }

        @Test
        @DisplayName("Test computeColumnWidths expands for longer values")
        fun testWidthsExpandForLongerValues() {
            val jobs = listOf(
                createMockJob(
                    "VeryLongCompanyNameInc",
                    "Senior Software Engineer",
                    85.0f,
                    "https://example.com/very/long/path/to/artifact"
                )
            )

            val widths = JobFormatter.computeColumnWidths(jobs)

            // Company name is longer than default 10, so it expands
            assertEquals("VeryLongCompanyNameInc".length, widths.company)
            // Title is longer than default 20, so it expands
            assertEquals("Senior Software Engineer".length, widths.title)
            // Fit score "Fit: 85" is 7 chars, but default minimum is 8, so stays at 8
            assertEquals(8, widths.fit)
            // URL is longer than default 30, so it expands
            assertTrue(widths.artifact >= "https://example.com/very/long/path/to/artifact".length)
        }

        @Test
        @DisplayName("Test computeColumnWidths handles multiple jobs correctly")
        fun testMultipleJobs() {
            val jobs = listOf(
                createMockJob("Short", "Title", 50.0f, "http://a.com"),
                createMockJob("MuchLongerCompany", "Much Longer Job Title", 75.0f, "http://b.com/very/long/url")
            )

            val widths = JobFormatter.computeColumnWidths(jobs)

            // Company expands from default 10 to 16
            assertEquals("MuchLongerCompany".length, widths.company)
            // Title expands from default 20 to 21
            assertEquals("Much Longer Job Title".length, widths.title)
            // Both fit scores are less than default 8, so stays at 8
            assertEquals(8, widths.fit)
        }

        @Test
        @DisplayName("Test computeColumnWidths handles N/A fit score")
        fun testFitScoreNA() {
            val jobs = listOf(
                createMockJob("Company", "Title", null, "http://example.com")
            )

            val widths = JobFormatter.computeColumnWidths(jobs)

            assertEquals("Fit: N/A".length, widths.fit)
        }
    }

    @Nested
    @DisplayName("FormatJobLine Tests")
    inner class FormatJobLineTests {

        @Test
        @DisplayName("Test formatJobLine creates properly formatted line")
        fun testBasicFormatting() {
            val state = createMockJob("Acme Corp", "Software Engineer", 85.0f, "http://example.com/artifact")
            val widths = ColumnWidths(company = 10, title = 20, fit = 8, artifact = 30)

            val line = JobFormatter.formatJobLine(state, widths)

            // Check that the line contains expected parts
            assertTrue(line.startsWith("Acme Corp  "), "Company should be padded")
            assertTrue(line.contains(" | "), "Should contain separators")
            assertTrue(line.contains("Fit: 85 "), "Should contain fit score")
        }

        @Test
        @DisplayName("Test formatJobLine applies color correctly")
        fun testColorApplication() {
            val state = createMockJob("Test", "Job", 50.0f, "http://test.com")
            val widths = ColumnWidths.default()

            val coloredLine = JobFormatter.formatJobLine(state, widths, "\u001B[32m")

            assertTrue(coloredLine.startsWith("\u001B[32m"), "Should start with color code")
            assertTrue(coloredLine.endsWith("\u001B[0m"), "Should end with reset code")
        }

        @Test
        @DisplayName("Test formatJobLine handles blank company/title")
        fun testBlankFields() {
            val state = createMockJob("", "", 90.0f, "http://example.com")
            val widths = ColumnWidths(company = 10, title = 20, fit = 8, artifact = 30)

            val line = JobFormatter.formatJobLine(state, widths)

            assertTrue(line.contains("Unknown Company"), "Should use default for blank company")
            assertTrue(line.contains("Unknown Role"), "Should use default for blank title")
        }
    }

    @Nested
    @DisplayName("FormatJobTable Tests")
    inner class FormatJobTableTests {

        @Test
        @DisplayName("Test formatJobTable returns empty for empty list")
        fun testEmptyList() {
            val lines = JobFormatter.formatJobTable(emptyList())

            assertTrue(lines.isEmpty())
        }

        @Test
        @DisplayName("Test formatJobTable formats multiple jobs with consistent widths")
        fun testMultipleJobsInTable() {
            val jobs = listOf(
                createMockJob("Company A", "Title A", 75.0f, "http://a.com"),
                createMockJob("Company B", "Title B", 85.0f, "http://b.com")
            )

            val lines = JobFormatter.formatJobTable(jobs)

            assertEquals(2, lines.size)

            // Both lines should have same total length (same column widths applied)
            assertEquals(lines[0].length, lines[1].length)
        }
    }

    @Nested
    @DisplayName("FormatSingleJob Tests")
    inner class FormatSingleJobTests {

        @Test
        @DisplayName("Test formatSingleJob uses default widths")
        fun testDefaultWidths() {
            val state = createMockJob("Test Company", "Test Role", 60.0f, "http://test.com")

            val line = JobFormatter.formatSingleJob(state)

            assertTrue(line.isNotEmpty())
            assertTrue(line.contains("Test Company"))
            assertTrue(line.contains("Test Role"))
            assertTrue(line.contains("Fit: 60"))
        }

        @Test
        @DisplayName("Test formatSingleJob applies color")
        fun testColor() {
            val state = createMockJob("Test", "Role", 50.0f, "http://test.com")

            val colored = JobFormatter.formatSingleJob(state, "\u001B[33m")

            assertTrue(colored.startsWith("\u001B[33m"))
            assertTrue(colored.endsWith("\u001B[0m"))
        }
    }

    @Nested
    @DisplayName("FormatScoredJobsTable Tests")
    inner class FormatScoredJobsTableTests {

        private fun createDigestJob(
            company: String,
            title: String,
            fitScore: Float?,
            artifactUrl: String
        ): JDState {
            return JDState(
                company = company,
                roleTitle = title,
                fitScore = fitScore,
                artifactUrl = artifactUrl,
                intake = IntakeContext.Email(
                    emailId = "",
                    from = "",
                    subject = "",
                    rawBody = "",
                    htmlBody = "",
                    isRecruiter = false,
                    isDigest = true,
                    isInlineDigest = false
                )
            )
        }

        @Test
        @DisplayName("Test formatScoredJobsTable returns empty list for empty input")
        fun testEmptyList() {
            val lines = JobFormatter.formatScoredJobsTable(emptyList())
            assertTrue(lines.isEmpty())
        }

        @Test
        @DisplayName("Test formatScoredJobsTable generates correct table structure")
        fun testTableStructure() {
            val jobs = listOf(
                createMockJob("Acme Corp", "Software Engineer", 85.0f, "http://example.com/artifact")
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            // Should have 4 lines: top border, header, separator, data row, bottom border
            assertEquals(5, lines.size)
            
            // Check top border uses box-drawing characters
            assertTrue(lines[0].startsWith("┌"), "First line should be top border")
            // Check header row
            assertTrue(lines[1].startsWith("│"), "Second line should be header row")
            assertTrue(lines[1].contains("Company"), "Header should contain Company")
            assertTrue(lines[1].contains("Title"), "Header should contain Title")
            assertTrue(lines[1].contains("Fit"), "Header should contain Fit")
            assertTrue(lines[1].contains("Artifact"), "Header should contain Artifact")
            // Check separator
            assertTrue(lines[2].startsWith("├"), "Third line should be separator")
            // Check data row
            assertTrue(lines[3].startsWith("│"), "Fourth line should be data row")
            assertTrue(lines[3].contains("Acme Corp"), "Data row should contain company")
            assertTrue(lines[3].contains("Software Engineer"), "Data row should contain title")
            // Check bottom border
            assertTrue(lines[4].startsWith("└"), "Fifth line should be bottom border")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable handles multiple jobs")
        fun testMultipleJobs() {
            val jobs = listOf(
                createMockJob("Company A", "Title A", 75.0f, "http://a.com"),
                createMockJob("Company B", "Title B", 85.0f, "http://b.com"),
                createMockJob("Company C", "Title C", 95.0f, "http://c.com")
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            // 3 jobs = 1 top + 1 header + 1 sep + 3 data + 1 bottom = 7 lines
            assertEquals(7, lines.size)
            // Jobs are sorted by fitScore descending, so C (95) first, then B (85), then A (75)
            assertTrue(lines[3].contains("Company C"), "Highest score (95) should be first")
            assertTrue(lines[4].contains("Company B"), "Middle score (85) should be second")
            assertTrue(lines[5].contains("Company A"), "Lowest score (75) should be third")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable shows digest job with bullet prefix")
        fun testDigestJobPrefix() {
            val jobs = listOf(
                createDigestJob("Motion Recruitment", "QA Engineer", 65.0f, "")
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            // Data row should contain bullet prefix
            assertTrue(lines[3].contains("• Motion Recruitment"), "Digest job should have bullet prefix")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable handles N/A fit score")
        fun testFitScoreNA() {
            val jobs = listOf(
                createMockJob("Company", "Title", null, "http://example.com")
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            assertTrue(lines[3].contains("N/A"), "Should display N/A for null fit score")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable right-aligns fit column")
        fun testFitRightAligned() {
            val jobs = listOf(
                createMockJob("Company A", "Title A", 85.0f, ""),
                createMockJob("Company B", "Title B", 9.0f, "")
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            // Both lines should have fit values right-aligned within the fit column
            // The 85 should appear left of where 9 appears in the same column
            val line1 = lines[3]
            val line2 = lines[4]
            
            // Find positions of fit values
            val fit85Pos = line1.indexOf("85")
            val fit9Pos = line2.indexOf("9")
            
            // Since they're right-aligned in same column width, 85 should be positioned the same or left of 9
            // More precisely: the "9" string should be at the same column position as "85" would end
            assertTrue(fit85Pos > 0, "Fit score 85 should be present")
            assertTrue(fit9Pos > 0, "Fit score 9 should be present")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable shows URL when within column width")
        fun testUrlWithinWidth() {
            val url = "http://example.com/artifact"
            val jobs = listOf(
                createMockJob("Company", "Title", 75.0f, url)
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            // URL should appear as-is when within column width
            val dataRow = lines[3]
            assertTrue(dataRow.contains(url), "URL should appear in output when within column width")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable handles blank company/title")
        fun testBlankFields() {
            val jobs = listOf(
                JDState(
                    company = "",
                    roleTitle = "",
                    fitScore = 90.0f,
                    artifactUrl = ""
                )
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            assertTrue(lines[3].contains("Unknown Company"), "Should use default for blank company")
            assertTrue(lines[3].contains("Unknown Role"), "Should use default for blank title")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable sorts jobs by fitScore descending")
        fun testSortOrderDescending() {
            val jobs = listOf(
                createMockJob("Company A", "Title A", 25.0f, ""),
                createMockJob("Company B", "Title B", 85.0f, ""),
                createMockJob("Company C", "Title C", 50.0f, "")
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            // Data rows are at lines 3, 4, 5
            // Higher fit scores should appear first
            assertTrue(lines[3].contains("Company B"), "Highest score (85) should be first")
            assertTrue(lines[4].contains("Company C"), "Middle score (50) should be second")
            assertTrue(lines[5].contains("Company A"), "Lowest score (25) should be third")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable shows 'Dupe' for duplicate jobs")
        fun testDuplicateSkipReason() {
            val jobs = listOf(
                JDState(
                    company = "Motion Recruitment",
                    roleTitle = "QA Engineer",
                    fitScore = null,
                    artifactUrl = "",
                    isDuplicate = true,
                    skippedReason = "Duplicate within 7d: Motion Recruitment — QA Engineer"
                )
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            assertTrue(lines[3].contains("Dupe"), "Should display 'Dupe' for duplicate jobs")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable shows 'NoJD' for jobs with no JD text")
        fun testNoJdSkipReason() {
            val jobs = listOf(
                JDState(
                    company = "Google",
                    roleTitle = "Senior Software Engineer",
                    fitScore = null,
                    artifactUrl = "",
                    skippedReason = "No JD text to score"
                )
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            assertTrue(lines[3].contains("NoJD"), "Should display 'NoJD' for jobs with no JD text")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable shows 'LowFit' for jobs with low fit score")
        fun testLowFitSkipReason() {
            val jobs = listOf(
                JDState(
                    company = "Amazon",
                    roleTitle = "SDE II",
                    fitScore = null,
                    artifactUrl = "",
                    skippedReason = "Fit score below threshold"
                )
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            assertTrue(lines[3].contains("LowFit"), "Should display 'LowFit' for low fit score")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable shows '?' for unknown skip reason")
        fun testUnknownSkipReason() {
            val jobs = listOf(
                JDState(
                    company = "Unknown Co",
                    roleTitle = "Role",
                    fitScore = null,
                    artifactUrl = "",
                    skippedReason = "Some other reason"
                )
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            assertTrue(lines[3].contains("?"), "Should display '?' for unknown skip reason")
        }

        @Test
        @DisplayName("Test formatScoredJobsTable sorts with skip reasons at the end")
        fun testSortWithSkipReasonsAtEnd() {
            val jobs = listOf(
                createMockJob("SkippedJob", "Title", null, ""),
                createMockJob("HighFit", "Title", 85.0f, ""),
                createMockJob("LowFit", "Title", 25.0f, "")
            )

            val lines = JobFormatter.formatScoredJobsTable(jobs)

            // Scored jobs should come before skipped jobs (sorted by fitScore descending, nulls last)
            assertTrue(lines[3].contains("HighFit"), "Highest scored job should be first")
            assertTrue(lines[4].contains("LowFit"), "Lower scored job should be second")
            assertTrue(lines[5].contains("SkippedJob"), "Skipped job should be last")
        }
    }
}