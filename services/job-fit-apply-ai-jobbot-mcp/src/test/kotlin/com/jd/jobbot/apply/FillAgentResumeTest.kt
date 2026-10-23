package com.jd.jobbot.apply

import com.jd.jobbot.actions.ResumeUpload
import com.jd.jobbot.llm.Llm
import com.jd.jobbot.llm.OpenAiCompatibleLlm
import com.jd.jobbot.support.ResumeFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The fill loop with a resume Richard sent: what it uploads, and what the model is told about it. */
class FillAgentResumeTest {
    @TempDir lateinit var dir: Path

    private val tailored = JobContext(
        ref = "#J9", company = "Acme", title = "Staff SDET", jobUrl = "https://boards.greenhouse.io/acme/jobs/9",
        accountEmail = "dkkytech@gmail.com", profileYaml = "years: 15+", resumeYaml = "name: tailored",
        coverLetter = "Dear Acme", resumePdf = "%PDF tailored".toByteArray(),
    )
    private fun upload(name: String, data: ByteArray) =
        (ResumeUpload.validate(name, data) as ResumeUpload.Check.Ok).upload

    private val form = Snapshot(
        url = "https://boards.greenhouse.io/acme/jobs/9",
        elements = listOf(
            Element("e2", "file", "file", "Resume", inForm = true),
            Element("e4", "file", "file", "Cover letter", inForm = true),
            Element("e3", "button", "submit", "", text = "Submit application", inForm = true, formFilled = true),
        ),
    )

    private class Script(vararg replies: String) : Llm {
        val prompts = mutableListOf<String>()
        val systems = mutableListOf<String>()
        private val queue = ArrayDeque(replies.toList())
        override fun json(system: String, user: String) = OpenAiCompatibleLlm.parseJsonObject(queue.removeFirst()).also { prompts += user; systems += system }
    }

    private fun agent(llm: Llm) = FillAgent(llm, Credentials(dir.resolve("c.json"), "dkkytech@gmail.com"), null)

    private class Capturing(snap: Snapshot) : ApplyPage by FakePage(snap) {
        val uploads = mutableListOf<Triple<String, String, ByteArray>>()
        override fun upload(id: String, name: String, mime: String, bytes: ByteArray) { uploads += Triple(name, mime, bytes) }
    }

    @Test
    fun `upload_resume sends the uploaded file under its own name and type`() {
        val docx = ResumeFiles.docx()
        val job = tailored.withUploadedResume(upload("Richard - Senior SDET.docx", docx))
        val page = Capturing(form)
        val r = agent(Script("""{"status":"continue","actions":[{"do":"upload_resume","id":"e2"}]}""", """{"status":"ready"}""")).run(page, job)
        assertIs<FillResult.Ready>(r)
        val (name, mime, bytes) = page.uploads.single()
        assertEquals("Richard - Senior SDET.docx", name)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", mime)
        assertTrue(bytes.contentEquals(docx))
    }

    @Test
    fun `without an upload the tailored resume still goes up as RichardHatcherResume pdf`() {
        val page = Capturing(form)
        agent(Script("""{"status":"continue","actions":[{"do":"upload_resume","id":"e2"}]}""", """{"status":"ready"}""")).run(page, tailored)
        val (name, mime, bytes) = page.uploads.single()
        assertEquals("RichardHatcherResume.pdf", name)
        assertEquals("application/pdf", mime)
        assertTrue(bytes.contentEquals("%PDF tailored".toByteArray()))
    }

    @Test
    fun `with an uploaded resume there is no cover letter to upload`() {
        val job = tailored.withUploadedResume(upload("r.pdf", ResumeFiles.pdf()))
        val llm = Script("""{"status":"continue","actions":[{"do":"upload_cover_letter","id":"e4"}]}""", """{"status":"ready"}""")
        val page = Capturing(form)
        assertIs<FillResult.Ready>(agent(llm).run(page, job))
        assertTrue(page.uploads.isEmpty())
        assertTrue(llm.prompts.last().contains("no cover letter for this job"), llm.prompts.last())
    }

    @Test
    fun `the model is told the file is Richard's, unreadable, and that answers come from the profile`() {
        val job = tailored.withUploadedResume(upload("Mine.pdf", ResumeFiles.pdf()))
        val llm = Script("""{"status":"ready"}""")
        agent(llm).run(FakePage(form), job)
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("Mine.pdf"), prompt)
        assertTrue(prompt.contains("answer from candidate_profile_yaml only"), prompt)
        assertTrue(prompt.contains("candidate_profile_yaml") && prompt.contains("years: 15+"), prompt)
    }

    @Test
    fun `the tailored resume and cover letter are not shown to the model when a file was uploaded`() {
        val job = tailored.withUploadedResume(upload("Mine.pdf", ResumeFiles.pdf()))
        val llm = Script("""{"status":"ready"}""")
        agent(llm).run(FakePage(form), job)
        val prompt = llm.prompts.single()
        assertFalse(prompt.contains("name: tailored"), prompt)
        assertFalse(prompt.contains("Dear Acme"), prompt)
    }

    @Test
    fun `without an upload the prompt is unchanged, with tailored resume and cover letter and no resume_file note`() {
        val llm = Script("""{"status":"ready"}""")
        agent(llm).run(FakePage(form), tailored)
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("name: tailored") && prompt.contains("Dear Acme"), prompt)
        assertFalse(prompt.contains("resume_file"), prompt)
    }

    @Test
    fun `an upload action fails cleanly when there is no file at all`() {
        val job = tailored.copy(resumePdf = null)
        val llm = Script("""{"status":"continue","actions":[{"do":"upload_resume","id":"e2"}]}""", """{"status":"ready"}""")
        agent(llm).run(FakePage(form), job)
        assertTrue(llm.prompts.last().contains("no resume file for this job"), llm.prompts.last())
    }

    @Test
    fun `the system prompt makes a cover letter optional and answers profile-first`() {
        val llm = Script("""{"status":"ready"}""")
        agent(llm).run(FakePage(form), tailored)
        val system = llm.systems.single()
        assertTrue(system.contains("the resume file into a file input"), system)
        assertTrue(system.contains("A cover letter is optional"), system)
        assertTrue(system.contains("candidate_profile_yaml, application_answers, tailored_resume_yaml and cover_letter (each when present)"), system)
    }

    @Test
    fun `withUploadedResume keeps everything about the job except the resume sources`() {
        val up = upload("r.pdf", ResumeFiles.pdf())
        val job = tailored.withUploadedResume(up)
        assertEquals(tailored.ref, job.ref)
        assertEquals(tailored.jobUrl, job.jobUrl)
        assertEquals(tailored.profileYaml, job.profileYaml)
        assertEquals(tailored.accountEmail, job.accountEmail)
        assertTrue(job.resumeIsUpload)
        assertFalse(tailored.resumeIsUpload)
    }

    @Test
    fun `a packet's application answers reach the model, and are absent otherwise`() {
        val withAnswers = tailored.copy(answers = "Sponsorship: No\nAuthorized: Yes").withUploadedResume(upload("r.pdf", ResumeFiles.pdf()))
        val llm = Script("""{"status":"ready"}""", """{"status":"ready"}""")
        agent(llm).run(FakePage(form), withAnswers)
        assertTrue(llm.prompts.single().contains("application_answers") && llm.prompts.single().contains("Sponsorship: No"), llm.prompts.single())
        val without = Script("""{"status":"ready"}""")
        agent(without).run(FakePage(form), tailored)
        assertFalse(without.prompts.single().contains("application_answers"), without.prompts.single())
    }

    @Test
    fun `the system prompt takes the application's questions from the answers and the rest from the profile`() {
        val llm = Script("""{"status":"ready"}""")
        agent(llm).run(FakePage(form), tailored)
        val system = llm.systems.single()
        assertTrue(system.contains("take the application's own questions from it"), system)
        assertTrue(system.contains("demographics, EEO answers, pay and preferences from candidate_profile_yaml"), system)
        assertTrue(system.contains("""Where application_answers says "ask", treat the answer as missing."""), system)
    }

    @Test
    fun `withUploadedResume keeps the answers`() {
        val job = tailored.copy(answers = "a: b").withUploadedResume(upload("r.pdf", ResumeFiles.pdf()))
        assertEquals("a: b", job.answers)
    }

    @Test
    fun `very long answers are cut to what fits the prompt`() {
        val job = tailored.copy(answers = "A".repeat(20_000)).withUploadedResume(upload("r.pdf", ResumeFiles.pdf()))
        val llm = Script("""{"status":"ready"}""")
        agent(llm).run(FakePage(form), job)
        val prompt = llm.prompts.single()
        assertTrue(prompt.count { it == 'A' } in 12_000..12_050, "${prompt.count { it == 'A' }}")
    }

    @Test
    fun `the profile and answers are both shown and clearly separate`() {
        val job = tailored.copy(answers = "Sponsorship: No").withUploadedResume(upload("r.pdf", ResumeFiles.pdf()))
        val llm = Script("""{"status":"ready"}""")
        agent(llm).run(FakePage(form), job)
        val prompt = llm.prompts.single()
        assertTrue(prompt.indexOf("candidate_profile_yaml") in 0 until prompt.indexOf("application_answers"), prompt)
    }
}
