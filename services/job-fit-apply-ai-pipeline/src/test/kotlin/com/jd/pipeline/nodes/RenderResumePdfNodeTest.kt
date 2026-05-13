package com.jd.pipeline.nodes
import com.jd.pipeline.state.PipelineAction

import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test for RenderResumePdfNode.
 * Validates that PDF placeholder generation works correctly.
 */
class RenderResumePdfNodeTest {

    @Test
    fun `test PDF placeholder is created with correct path and content`() {
        // Create a temp directory for the test output
        val tempDir = Files.createTempDirectory("jd-pipeline-test-")
        val outputPath = tempDir.toString()
        
        // Create the HTML file that the test expects
        val htmlPath = tempDir.resolve("tailored_resume.html")
        Files.writeString(htmlPath, "<html><body>Test Resume</body></html>")
        
        // Verify HTML file exists (prerequisite)
        assertTrue(Files.exists(htmlPath), "HTML file should exist at $htmlPath")
        
        // Create mock JDState for PDF generation
        val mockState = JDState(
            pipelineAction = PipelineAction.TAILOR,
            outputPath = outputPath,
            company = "Acme Corp",
            roleTitle = "Staff SDET"
        )
        
        // Invoke PDF node
        val pdfNode = RenderResumePdfNode()
        val result = pdfNode.process(mockState)
        
        // Validation 1: result.resumeHtmlPdf is non-empty
        assertTrue(result.resumeHtmlPdf.isNotEmpty(), 
            "resumeHtmlPdf should be non-empty, but was: '${result.resumeHtmlPdf}'")
        
        // Validation 2: PDF file exists at that path
        val pdfPath = Path.of(result.resumeHtmlPdf)
        assertTrue(Files.exists(pdfPath), 
            "PDF file should exist at ${result.resumeHtmlPdf}")
        
        // Validation 3: File size > 0
        val fileSize = Files.size(pdfPath)
        assertTrue(fileSize > 0, 
            "PDF file should have size > 0, but was: $fileSize")
        
        // Validation 4: Verify PDF magic bytes
        val bytes = Files.readAllBytes(pdfPath)
        val pdfHeader = String(bytes, 0, minOf(8, bytes.size), StandardCharsets.US_ASCII)
        assertTrue(pdfHeader.startsWith("%PDF-"), 
            "PDF file should start with %PDF- magic bytes, but was: ${pdfHeader.take(20)}")
        
        println("=== PDF Verification Test Results ===")
        println("PDF path: ${result.resumeHtmlPdf}")
        println("File size: $fileSize bytes")
        println("PDF header: ${pdfHeader.take(20)}")
        println("=====================================")
        
        // Cleanup
        Files.deleteIfExists(htmlPath)
        Files.deleteIfExists(pdfPath)
        Files.deleteIfExists(tempDir)
    }
    
    @Test
    fun `test PDF node skips when action is not tailor`() {
        val mockState = JDState(
            pipelineAction = PipelineAction.SKIP,
            outputPath = "/tmp/test_output",
            company = "Test Corp",
            roleTitle = "Engineer"
        )
        
        val pdfNode = RenderResumePdfNode()
        val result = pdfNode.process(mockState)
        
        // Should return input unchanged
        assertEquals("", result.resumeHtmlPdf)
    }
    
    @Test
    fun `test PDF node skips when outputPath is null`() {
        val mockState = JDState(
            pipelineAction = PipelineAction.TAILOR,
            outputPath = "",
            company = "Test Corp",
            roleTitle = "Engineer"
        )
        
        val pdfNode = RenderResumePdfNode()
        val result = pdfNode.process(mockState)
        
        // Should return input unchanged
        assertEquals("", result.resumeHtmlPdf)
    }

    @Test
    fun `test PDF filename includes author name from candidateProfile`() {
        val tempDir = Files.createTempDirectory("jd-pipeline-test-")
        val outputPath = tempDir.toString()

        val htmlPath = tempDir.resolve("tailored_resume.html")
        Files.writeString(htmlPath, "<html><body>Test Resume</body></html>")

        val profile = createCandidateProfile(firstName = "Jane", lastName = "Doe")

        val mockState = JDState(
            pipelineAction = PipelineAction.TAILOR,
            outputPath = outputPath,
            company = "Acme Corp",
            roleTitle = "Staff SDET",
            candidateProfile = profile
        )

        val pdfNode = RenderResumePdfNode()
        val result = pdfNode.process(mockState)

        assertTrue(result.resumeHtmlPdf.isNotEmpty())
        val pdfPath = Path.of(result.resumeHtmlPdf)
        val fileName = pdfPath.fileName.toString()

        assertTrue(fileName.contains("Jane_Doe"), "Filename should contain author name: $fileName")
        assertTrue(fileName.contains("Staff_SDET"), "Filename should contain role title: $fileName")
        assertTrue(fileName.endsWith(".pdf"), "Filename should end with .pdf: $fileName")

        // Cleanup
        Files.deleteIfExists(htmlPath)
        Files.deleteIfExists(pdfPath)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test PDF filename falls back to Resume when candidateProfile is null`() {
        val tempDir = Files.createTempDirectory("jd-pipeline-test-")
        val outputPath = tempDir.toString()

        val htmlPath = tempDir.resolve("tailored_resume.html")
        Files.writeString(htmlPath, "<html><body>Test Resume</body></html>")

        val mockState = JDState(
            pipelineAction = PipelineAction.TAILOR,
            outputPath = outputPath,
            company = "Acme Corp",
            roleTitle = "Engineer",
            candidateProfile = null
        )

        val pdfNode = RenderResumePdfNode()
        val result = pdfNode.process(mockState)

        assertTrue(result.resumeHtmlPdf.isNotEmpty())
        val pdfPath = Path.of(result.resumeHtmlPdf)
        val fileName = pdfPath.fileName.toString()

        assertTrue(fileName.startsWith("Resume_"), "Filename should fallback to 'Resume': $fileName")
        assertTrue(fileName.endsWith(".pdf"), "Filename should end with .pdf: $fileName")

        // Cleanup
        Files.deleteIfExists(htmlPath)
        Files.deleteIfExists(pdfPath)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test PDF filename falls back to Resume when fullName is blank`() {
        val tempDir = Files.createTempDirectory("jd-pipeline-test-")
        val outputPath = tempDir.toString()

        val htmlPath = tempDir.resolve("tailored_resume.html")
        Files.writeString(htmlPath, "<html><body>Test Resume</body></html>")

        val profile = createCandidateProfile(firstName = "", lastName = "")

        val mockState = JDState(
            pipelineAction = PipelineAction.TAILOR,
            outputPath = outputPath,
            company = "Acme Corp",
            roleTitle = "Engineer",
            candidateProfile = profile
        )

        val pdfNode = RenderResumePdfNode()
        val result = pdfNode.process(mockState)

        assertTrue(result.resumeHtmlPdf.isNotEmpty())
        val pdfPath = Path.of(result.resumeHtmlPdf)
        val fileName = pdfPath.fileName.toString()

        assertTrue(fileName.startsWith("Resume_"), "Filename should fallback to 'Resume' when name is blank: $fileName")

        // Cleanup
        Files.deleteIfExists(htmlPath)
        Files.deleteIfExists(pdfPath)
        Files.deleteIfExists(tempDir)
    }

    private fun createCandidateProfile(
        firstName: String = "Jane",
        lastName: String = "Doe"
    ): com.jd.pipeline.models.CandidateProfile {
        return com.jd.pipeline.models.CandidateProfile(
            identity = com.jd.pipeline.models.CandidateIdentity(
                name = "$firstName $lastName",
                firstName = firstName,
                lastName = lastName,
                email = "jane@example.com",
                phone = "555-1234",
                location = "Seattle, WA"
            ),
            background = com.jd.pipeline.models.CandidateBackground(
                targetTitle = "SDET",
                yearsExperience = 10,
                education = emptyList(),
                careerHistory = emptyList(),
                coreStrengths = emptyList(),
                languages = emptyList(),
                domainExpertise = emptyList()
            ),
            skills = com.jd.pipeline.models.CandidateSkills(
                primaryStack = emptyList(),
                mobileAutomation = emptyList(),
                ciCdPlatforms = emptyList(),
                webApiAutomation = emptyList(),
                infrastructureObservability = emptyList(),
                leadershipAbilities = emptyList()
            )
        )
    }
}
