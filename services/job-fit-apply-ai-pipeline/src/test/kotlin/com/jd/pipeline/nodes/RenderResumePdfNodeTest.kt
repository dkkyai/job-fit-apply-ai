package com.jd.pipeline.nodes

import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for RenderResumePdfNode (YAML → LaTeX → PDF).
 *
 * The real yaml_to_tex.py needs jinja2 + tectonic, which aren't test dependencies, so these
 * tests inject a stub script that honours the same contract: run in the job dir with
 * `<yaml> -o tailored_resume.tex --pdf`, it writes tailored_resume.tex, a fonts/ copy, and
 * tailored_resume.pdf. The real-toolchain compile is exercised at Docker build time (warm-up)
 * and by --test-resume on a machine with tectonic installed.
 */
class RenderResumePdfNodeTest {

    /** Stub yaml_to_tex.py: mimics outputs (tex + fonts/ + pdf) without jinja2/tectonic. */
    private val stubScript = """
        import pathlib, sys
        assert sys.argv[1] == "tailored_resume.yaml", f"unexpected yaml arg: {sys.argv[1]}"
        assert "--pdf" in sys.argv, "expected --pdf flag"
        pathlib.Path("tailored_resume.tex").write_text("stub tex")
        fonts = pathlib.Path("fonts"); fonts.mkdir(exist_ok=True)
        (fonts / "Roboto-Regular.ttf").write_bytes(b"stub font")
        pathlib.Path("tailored_resume.pdf").write_bytes(b"%PDF-1.4 stub resume")
    """.trimIndent()

    private val failingScript = """
        import sys
        print("tectonic: error: something exploded")
        sys.exit(3)
    """.trimIndent()

    private fun tempJobDir(withYaml: Boolean = true): Path {
        val dir = Files.createTempDirectory("jd-pipeline-test-")
        if (withYaml) Files.writeString(dir.resolve("tailored_resume.yaml"), "summary: test")
        return dir
    }

    private fun node(dir: Path, script: String = stubScript): RenderResumePdfNode {
        val scriptPath = dir.resolve("stub_yaml_to_tex.py")
        Files.writeString(scriptPath, script)
        return RenderResumePdfNode(pythonBin = "python3", script = scriptPath, timeoutMs = 30_000)
    }

    private fun tailorState(dir: Path, profile: com.jd.pipeline.models.CandidateProfile? = null) = JDState(
        pipelineAction = PipelineAction.TAILOR,
        outputPath = dir.toString(),
        company = "Acme Corp",
        roleTitle = "Staff SDET",
        candidateProfile = profile,
    )

    @Test
    fun `renders PDF from tailored_resume yaml, keeps tex, removes fonts copy`() {
        val dir = tempJobDir()
        val result = node(dir).process(tailorState(dir, createCandidateProfile()))

        assertEquals("", result.error, "render should succeed: ${result.error}")
        assertTrue(result.resumeHtmlPdf.isNotEmpty(), "resumeHtmlPdf should be set")

        val pdfPath = Path.of(result.resumeHtmlPdf)
        assertTrue(Files.exists(pdfPath), "PDF should exist at $pdfPath")
        assertEquals("Jane_Doe_Staff_SDET.pdf", pdfPath.fileName.toString())
        assertTrue(Files.readAllBytes(pdfPath).decodeToString().startsWith("%PDF-"), "PDF magic bytes")

        assertTrue(Files.exists(dir.resolve("tailored_resume.tex")), ".tex kept for debugging")
        assertFalse(Files.exists(dir.resolve("tailored_resume.pdf")), "intermediate pdf renamed away")
        assertFalse(Files.exists(dir.resolve("fonts")), "per-job fonts/ copy removed")
        assertFalse(Files.exists(dir.resolve("render_pdf.log")), "log removed on success")
    }

    @Test
    fun `skips when action is not tailor`() {
        val dir = tempJobDir()
        val result = node(dir).process(tailorState(dir).copy(pipelineAction = PipelineAction.SKIP))
        assertEquals("", result.resumeHtmlPdf)
    }

    @Test
    fun `skips when outputPath is empty`() {
        val dir = tempJobDir()
        val result = node(dir).process(tailorState(dir).copy(outputPath = ""))
        assertEquals("", result.resumeHtmlPdf)
    }

    @Test
    fun `errors when tailored_resume yaml is missing`() {
        val dir = tempJobDir(withYaml = false)
        val result = node(dir).process(tailorState(dir))
        assertTrue(result.error.contains("tailored_resume.yaml not found"), "got: ${result.error}")
        assertEquals("", result.resumeHtmlPdf)
    }

    @Test
    fun `errors with script output tail when the toolchain fails`() {
        val dir = tempJobDir()
        val result = node(dir, script = failingScript).process(tailorState(dir))
        assertTrue(result.error.contains("exited 3"), "should carry exit code: ${result.error}")
        assertTrue(result.error.contains("something exploded"), "should carry output tail: ${result.error}")
        assertTrue(Files.exists(dir.resolve("render_pdf.log")), "log kept on failure")
        assertEquals("", result.resumeHtmlPdf)
    }

    @Test
    fun `filename falls back to Resume when candidateProfile is null`() {
        val dir = tempJobDir()
        val result = node(dir).process(tailorState(dir, profile = null))
        assertTrue(result.resumeHtmlPdf.isNotEmpty())
        val fileName = Path.of(result.resumeHtmlPdf).fileName.toString()
        assertTrue(fileName.startsWith("Resume_"), "Filename should fall back to 'Resume': $fileName")
        assertTrue(fileName.endsWith(".pdf"))
    }

    @Test
    fun `filename falls back to Resume when fullName is blank`() {
        val dir = tempJobDir()
        val profile = createCandidateProfile(firstName = "", lastName = "")
        val result = node(dir).process(tailorState(dir, profile))
        assertTrue(result.resumeHtmlPdf.isNotEmpty())
        val fileName = Path.of(result.resumeHtmlPdf).fileName.toString()
        assertTrue(fileName.startsWith("Resume_"), "Filename should fall back to 'Resume': $fileName")
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
            skills = emptyList()
        )
    }
}
