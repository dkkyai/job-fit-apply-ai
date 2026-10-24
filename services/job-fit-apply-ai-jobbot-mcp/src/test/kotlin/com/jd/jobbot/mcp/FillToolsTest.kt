package com.jd.jobbot.mcp

import com.jd.jobbot.apply.Credentials
import com.jd.jobbot.apply.Element
import com.jd.jobbot.apply.FakePage
import com.jd.jobbot.apply.FillExecutor
import com.jd.jobbot.apply.FillInstructions
import com.jd.jobbot.apply.FillResult
import com.jd.jobbot.apply.FillSession
import com.jd.jobbot.apply.FillSessions
import com.jd.jobbot.apply.JobContext
import com.jd.jobbot.apply.Snapshot
import com.jd.jobbot.apply.Verifier
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What JobBot (through Hermes) can do to a fill, one tool call at a time. The point of these tests is the promise
 * that moving the planner into Hermes took nothing away and added nothing: every action FillAgent had is a tool
 * action, and every guard FillAgent had still stands in front of it.
 */
class FillToolsTest {
    @TempDir lateinit var dir: Path

    private val job = JobContext(
        ref = "#J9", company = "Acme", title = "Staff SDET", jobUrl = "https://boards.greenhouse.io/acme/jobs/9",
        accountEmail = "dkkytech@gmail.com", profileYaml = "years: 15+", resumeYaml = null, coverLetter = null, resumePdf = "%PDF".toByteArray(),
    )

    private val sessions = FillSessions()
    private val tools = FillTools(sessions)

    private fun open(page: FakePage, forJob: JobContext = job, verifier: Verifier? = null): String {
        val s = FillSession(sessions.newToken(), page, forJob, FillExecutor(Credentials(dir.resolve("creds-${page.hashCode()}.json"), "dkkytech@gmail.com"), verifier), 30)
        sessions.open(s)
        return s.token
    }

    private fun req(vararg args: Pair<String, Any?>) = CallToolRequest(
        CallToolRequestParams(name = "t", arguments = buildJsonObject {
            args.forEach { (k, v) ->
                when (v) {
                    null -> {}
                    is kotlinx.serialization.json.JsonElement -> put(k, v)
                    is Number -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }),
    )

    private fun actions(vararg a: JsonObject) = buildJsonArray { a.forEach { add(it) } }
    private fun act(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }
    private fun CallToolResult.text() = (content.single() as TextContent).text
    private fun CallToolResult.json() = Json.parseToJsonElement(text()).jsonObject
    private fun page(vararg extra: Element, url: String = "https://boards.greenhouse.io/acme/jobs/9") =
        FakePage(Snapshot(url, elements = listOf(
            Element("e1", "input", "text", "First name", inForm = true),
            Element("e2", "file", "file", "Resume", inForm = true),
            Element("e3", "button", "submit", "", text = "Submit application", inForm = true, formFilled = true),
        ) + extra))

    // ── parity: nothing FillAgent could do is missing ────────────────────────────────────────

    private fun executorSource(): String {
        var d: Path? = Paths.get("").toAbsolutePath()
        while (d != null) {
            d.resolve("src/main/kotlin/com/jd/jobbot/apply/FillExecutor.kt").takeIf { Files.exists(it) }?.let { return Files.readString(it) }
            d = d.parent
        }
        error("FillExecutor.kt not found")
    }

    @Test
    fun `the twelve actions FillAgent had are the executor's whole vocabulary`() {
        assertEquals(
            listOf("fill", "select", "check", "upload_resume", "upload_cover_letter", "click", "goto", "password", "email", "google_sign_in", "verification_code", "open_verification_link"),
            FillExecutor.ACTIONS,
        )
    }

    @Test
    fun `every action the executor handles is offered, and nothing is offered that it does not handle`() {
        val handled = Regex("""^\s{12}"([a-z_]+)" ->""", RegexOption.MULTILINE).findAll(executorSource().substringAfter("internal fun execute(").substringBefore("internal sealed interface GoogleOutcome"))
            .map { it.groupValues[1] }.toSet()
        assertEquals(handled, FillExecutor.ACTIONS.toSet(), "a branch was added to (or dropped from) execute() without updating ACTIONS")
    }

    @Test
    fun `the instructions explain every action`() {
        val text = FillInstructions.SYSTEM
        for (action in FillExecutor.ACTIONS) {
            assertTrue(Regex("""^\s+$action\b""", RegexOption.MULTILINE).containsMatchIn(text), "the instructions never explain $action")
        }
    }

    @TestFactory
    fun `every action reaches the executor through the tool`(): List<DynamicTest> = FillExecutor.ACTIONS.map { name ->
        DynamicTest.dynamicTest(name) {
            val p = page(Element("e9", "input", "password", "Password", inForm = true), Element("e8", "button", "button", text = "Continue with Google", google = true))
            val token = open(p)
            tools.snapshot(req("session" to token))
            val r = tools.act(req("session" to token, "actions" to actions(act("do" to name, "id" to "e1", "value" to "x", "option" to "x", "url" to "https://boards.greenhouse.io/acme/jobs/9", "purpose" to "login"))))
            assertFalse(r.text().contains("unknown action"), "$name is not understood: ${r.text()}")
        }
    }

    @Test
    fun `an action outside the vocabulary is an error, not a surprise`() {
        val token = open(page())
        tools.snapshot(req("session" to token))
        for (bad in listOf("eval", "screenshot", "download", "cookies", "evaluate", "cdp", "navigate", "submit", "type")) {
            val r = tools.act(req("session" to token, "actions" to actions(act("do" to bad, "id" to "e1"))))
            assertContains(r.text(), "unknown action $bad")
        }
    }

    // ── the session token is the only way in ─────────────────────────────────────────────────

    @Test
    fun `without a live fill's token every tool refuses`() {
        for (call in listOf<() -> CallToolResult>(
            { tools.context(req()) }, { tools.snapshot(req("session" to "nope")) },
            { tools.act(req("session" to "0".repeat(32), "actions" to actions(act("do" to "fill", "id" to "e1", "value" to "x")))) },
            { tools.finish(req("session" to "../../etc", "status" to "ready")) },
        )) {
            val r = call()
            assertEquals(true, r.isError)
            assertContains(r.text(), "No fill is running with that session token")
        }
    }

    @Test
    fun `a finished fill's token stops working`() {
        val token = open(page())
        sessions.close(token)
        assertEquals(true, tools.snapshot(req("session" to token)).isError)
    }

    @Test
    fun `two fills never see each other`() {
        val a = page(); val b = page(); b.values["e1"] = "B's value"
        val ta = open(a); val tb = open(b)
        tools.snapshot(req("session" to ta)); tools.snapshot(req("session" to tb))
        tools.act(req("session" to ta, "actions" to actions(act("do" to "fill", "id" to "e1", "value" to "for A"))))
        assertEquals(listOf("fill e1"), a.actions)
        assertEquals(emptyList(), b.actions)
    }

    // ── the guards, through the tools ────────────────────────────────────────────────────────

    private fun actResult(token: String, vararg a: JsonObject): String {
        tools.snapshot(req("session" to token))
        return tools.act(req("session" to token, "actions" to actions(*a))).text()
    }

    @Test
    fun `there is no way to submit`() {
        val p = page(Element("e4", "button", "button", text = "Finish", inForm = true, formFilled = true), Element("e5", "button", "button", text = "Apply", inForm = true, formFilled = true))
        val token = open(p)
        for (id in listOf("e3", "e4", "e5")) assertContains(actResult(token, act("do" to "click", "id" to id)), "refused")
        assertFalse(p.actions.any { it.startsWith("click") }, "no submit-like control was clicked: ${p.actions}")
    }

    @Test
    fun `the browser cannot be sent to Gmail, Drive or an off-site page`() {
        val p = page(); val token = open(p)
        for (url in listOf("https://mail.google.com/mail/u/0", "https://drive.google.com/drive", "https://myaccount.google.com/", "https://evil.example.com/login", "file:///etc/passwd", "javascript:alert(1)")) {
            assertContains(actResult(token, act("do" to "goto", "url" to url)), "refused", message = url)
        }
        assertEquals(emptyList(), p.actions)
    }

    @Test
    fun `a password is never typed from the model's words`() {
        val p = page(Element("e9", "input", "password", "Password", inForm = true)); val token = open(p)
        assertContains(actResult(token, act("do" to "fill", "id" to "e9", "value" to "hunter2hunter2")), "use the password action")
        assertFalse(p.values.containsKey("e9"))
        val ok = actResult(token, act("do" to "password", "id" to "e9", "purpose" to "new"))
        assertContains(ok, "not shown")
        assertFalse(p.values["e9"].isNullOrBlank(), "the executor filled it from the store")
    }

    @Test
    fun `google sign-in only on a control the page marked as Google`() {
        val p = page(Element("e7", "button", "button", text = "Sign in", inForm = true)); val token = open(p)
        assertContains(actResult(token, act("do" to "google_sign_in", "id" to "e7")), "is not a Sign in with Google control")
        assertContains(actResult(token, act("do" to "google_sign_in", "id" to "e1")), "refused")
    }

    @Test
    fun `ids the page did not produce cannot be addressed`() {
        val p = page(); val token = open(p)
        for (id in listOf("e99", "e1\"]; document.cookie //", "#password", "../e1", "")) {
            assertContains(actResult(token, act("do" to "fill", "id" to id, "value" to "x")), "error: no element")
        }
        assertEquals(emptyList(), p.actions)
    }

    @Test
    fun `a cover letter cannot be uploaded when there is none`() {
        val token = open(page())
        assertContains(actResult(token, act("do" to "upload_cover_letter", "id" to "e2")), "no cover letter for this job")
    }

    @Test
    fun `a verification link that points off-site is refused`() {
        val verifier = object : Verifier(com.jd.jobbot.gmail.GmailClient(com.jd.jobbot.gmail.GmailAuth(dir.resolve("none"), dir.resolve("none2")))) {
            override fun link(site: String, sinceMillis: Long, wait: java.time.Duration): String? = "https://evil.example.com/verify?t=1"
        }
        val token = open(page(), verifier = verifier)
        assertContains(actResult(token, act("do" to "open_verification_link")), "points off-site")
    }

    // ── the tools themselves ─────────────────────────────────────────────────────────────────

    @Test
    fun `act with no actions is an error the planner can read`() {
        val token = open(page())
        for (r in listOf(tools.act(req("session" to token)), tools.act(req("session" to token, "actions" to buildJsonArray { })))) {
            assertContains(r.text(), "Give at least one action")
        }
    }

    @Test
    fun `finishing through the tool ends the fill with the planner's note`() {
        val p = page(); p.values["e1"] = "Richard"
        val token = open(p)
        tools.snapshot(req("session" to token))
        assertContains(tools.finish(req("session" to token, "status" to "ready", "note" to "done")).text(), "stopped")
        val outcome = sessions.get(token)!!.outcome
        assertEquals(listOf("First name" to "Richard"), assertIs<FillResult.Ready>(outcome).fields)
    }

    @Test
    fun `a browser failure inside a tool is an error result, not a transport failure`() {
        val p = object : FakePage(Snapshot("https://boards.greenhouse.io/acme/jobs/9")) { override fun snapshot() = error("tab crashed") }
        val token = open(p)
        val r = tools.snapshot(req("session" to token))
        assertEquals(true, r.isError)
        assertContains(r.text(), "The browser step failed")
    }

    @Test
    fun `the tool names are the ones Hermes is allowed to call`() {
        val names = Regex("""name = "(fill_[a-z]+)"""").findAll(Files.readString(sourcePath("FillTools.kt"))).map { it.groupValues[1] }.toList()
        assertEquals(listOf("fill_context", "fill_snapshot", "fill_act", "fill_finish"), names)
    }

    private fun sourcePath(file: String): Path {
        var d: Path? = Paths.get("").toAbsolutePath()
        while (d != null) {
            d.resolve("src/main/kotlin/com/jd/jobbot/mcp/$file").takeIf { Files.exists(it) }?.let { return it }
            d = d.parent
        }
        error("$file not found")
    }
}
