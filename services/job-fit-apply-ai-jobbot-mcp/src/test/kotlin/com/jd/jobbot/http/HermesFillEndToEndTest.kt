package com.jd.jobbot.http

import com.google.common.util.concurrent.MoreExecutors
import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.IdVerbHandler
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.Outcomes
import com.jd.jobbot.actions.ResumeApplyRequest
import com.jd.jobbot.actions.StatusReport
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.apply.ApplyService
import com.jd.jobbot.apply.Credentials
import com.jd.jobbot.apply.Element
import com.jd.jobbot.apply.FakePage
import com.jd.jobbot.apply.FillExecutor
import com.jd.jobbot.apply.FillSessions
import com.jd.jobbot.apply.JobContext
import com.jd.jobbot.apply.Snapshot
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.files.OutputFiles
import com.jd.jobbot.hermes.HermesFillDriver
import com.jd.jobbot.hermes.HermesRuns
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.mcp.FillTools
import com.jd.jobbot.mcp.JobbotTools
import com.jd.jobbot.profile.ProfileReader
import com.jd.jobbot.support.FakeBridge
import com.jd.jobbot.support.ResumeFiles
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An application fill with Hermes as the planner, end to end and with nothing in between faked except Hermes' brain:
 * ApplyService starts a run on a stand-in Hermes API; the stand-in plays the planner by calling the real fill_* tools
 * through a real MCP client over HTTP, against the real server, FillSession, executor and review flow.
 */
class HermesFillEndToEndTest {
    @TempDir lateinit var tmp: Path
    private val mcpToken = "mcp-token"
    private val hermesKey = "hermes-key"
    private val user = "8679792351"

    private lateinit var mcpServer: EmbeddedServer<*, *>
    private lateinit var hermes: HttpServer
    private lateinit var bridge: FakeBridge
    private lateinit var outbox: Outbox
    private lateinit var store: ActionStore
    private lateinit var page: FakePage
    private lateinit var taps: TapService
    private val sessions = FillSessions()
    private val jobs = java.util.concurrent.CopyOnWriteArrayList<JobContext>()
    private val mcpPort = ServerSocket(0).use { it.localPort }
    private val http = HttpClient(ClientCIO) { install(SSE) }
    private val runCount = AtomicInteger()
    private val runStates = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()
    private val runBodies = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
    private val wrongKeys = AtomicInteger()

    /** What the stand-in planner does with the tools; set per test. */
    private var planner: suspend (Client, String) -> Unit = { _, _ -> }

    private val form = Snapshot(
        "https://jobs.lever.co/acme/1/apply",
        elements = listOf(
            Element("e1", "input", "text", "First name", inForm = true),
            Element("e2", "file", "file", "Resume", inForm = true),
            Element("e3", "button", "submit", "", text = "Submit application", inForm = true, formFilled = true),
        ),
    )

    @BeforeTest
    fun start() {
        bridge = FakeBridge(events = emptyList())
        val client = BridgeReadClient(bridge.url)
        val lookup = JobLookup(client)
        store = ActionStore(":memory:")
        outbox = Outbox(tmp.resolve("o.db").toString(), tmp.resolve("shots"))
        page = FakePage(form).also { p -> p.onClick = { if (it == "e3") p.snap = p.snap.copy(url = p.snap.url + "/done", text = "Thank you for applying!") } }

        hermes = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/runs") { ex ->
                val respond = { code: Int, body: String -> val b = body.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                if (ex.requestHeaders.getFirst("Authorization") != "Bearer $hermesKey") { wrongKeys.incrementAndGet(); return@createContext respond(401, """{"error":"Invalid gateway API key"}""") }
                val path = ex.requestURI.path
                when {
                    ex.requestMethod == "POST" && path == "/v1/runs" -> {
                        val body = Json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
                        runBodies += body
                        val id = "run_${runCount.incrementAndGet()}"
                        val token = Regex("session token is ([0-9a-f]{32})").find(body["input"]!!.jsonPrimitive.content)!!.groupValues[1]
                        runStates[id] = "running" to null
                        Thread {
                            try {
                                runBlocking {
                                    val mcp = Client(Implementation("planner", "1"))
                                    mcp.connect(StreamableHttpClientTransport(http, "http://127.0.0.1:$mcpPort/mcp-fill", requestBuilder = { header("Authorization", "Bearer $mcpToken") }))
                                    try { planner(mcp, token) } finally { mcp.close() }
                                }
                                runStates[id] = "completed" to null
                            } catch (e: Throwable) {
                                runStates[id] = "failed" to (e.message ?: e.javaClass.simpleName)
                            }
                        }.apply { isDaemon = true }.start()
                        respond(202, """{"run_id":"$id","status":"started"}""")
                    }
                    ex.requestMethod == "GET" -> {
                        val (state, error) = runStates[path.substringAfterLast('/')] ?: return@createContext respond(404, """{"error":"not found"}""")
                        respond(200, buildString { append("""{"status":"$state","output":"ok""""); error?.let { append(""","error":"${it.replace("\"", "'")}"""") }; append("}") })
                    }
                    else -> respond(200, """{"status":"stopping"}""")
                }
            }
            start()
        }

        val driver = HermesFillDriver(
            HermesRuns("http://127.0.0.1:${hermes.address.port}", hermesKey), sessions,
            FillExecutor(Credentials(tmp.resolve("creds.json"), "dkkytech@gmail.com"), null),
            model = "deepseek-v4.1-flash:cloud", maxWait = Duration.ofSeconds(30), pollMillis = 20,
        )
        val service = ApplyService(
            browser = { _ -> page }, fill = { p, job, fresh -> jobs += job; driver.run(p, job, fresh) }, lookup = lookup,
            contextFor = { JobContext("", null, null, "", "dkkytech@gmail.com", "years: 15+", null, null, null) },
            store = store, outbox = outbox, tracks = null, viewerUrl = null,
            credentials = Credentials(tmp.resolve("creds2.json"), "dkkytech@gmail.com"),
            worker = MoreExecutors.newDirectExecutorService(),
        )
        taps = TapService(
            lookup, store, emptyMap(), setOf(user), setOf("apply"), false, Duration.ofDays(7),
            idHandlers = mapOf("submit" to IdVerbHandler(service::submit), "discard" to IdVerbHandler(service::discard), "resume" to IdVerbHandler(service::resume)),
            resumeApply = service.applyWithResume,
        )
        val tools = JobbotTools(lookup, client, OutputFiles("/nonexistent"), ProfileReader("/nonexistent/a", "/nonexistent/b"), 55, 400)
        mcpServer = embeddedServer(CIO, port = mcpPort, host = "127.0.0.1") {
            jobbotModule(mcpToken, listOf("127.0.0.1", "localhost"), taps, StatusReport(client, store, setOf("apply"), false), tools, outbox = outbox, fills = FillTools(sessions))
        }.start(wait = false)
    }

    @AfterTest
    fun stop() {
        http.close(); mcpServer.stop(100, 500); hermes.stop(0); outbox.close(); bridge.close()
    }

    private fun applyWithLink(url: String = "https://jobs.lever.co/acme/1") = taps.applyWithResume(
        ResumeApplyRequest(jobUrl = url, userId = user, chatId = user, fileName = "My Resume.pdf", fileData = ResumeFiles.b64(ResumeFiles.pdf())),
    )

    private suspend fun Client.call(name: String, args: Map<String, Any?>) = callTool(name, args).let { r ->
        r to Json.parseToJsonElement((r.content.single() as TextContent).text).jsonObject
    }

    private fun actionList(vararg a: Map<String, String>) = a.toList()

    // ── the happy path ───────────────────────────────────────────────────────────────────────

    @Test
    fun `Hermes plans the fill through the tools and the form is posted for review`() {
        planner = { mcp, token ->
            val (_, ctx) = mcp.call("fill_context", mapOf("session" to token))
            assertEquals("years: 15+", ctx["candidate_profile_yaml"]!!.jsonPrimitive.content)
            val (_, snap) = mcp.call("fill_snapshot", mapOf("session" to token))
            assertEquals(listOf("e1", "e2", "e3"), snap["page"]!!.jsonObject["elements"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val (_, acted) = mcp.call("fill_act", mapOf("session" to token, "actions" to actionList(
                mapOf("do" to "fill", "id" to "e1", "value" to "Richard"), mapOf("do" to "upload_resume", "id" to "e2"),
            )))
            assertEquals(listOf("ok", "ok"), acted["results"]!!.jsonArray.map { it.jsonObject["result"]!!.jsonPrimitive.content })
            val (_, fin) = mcp.call("fill_finish", mapOf("session" to token, "status" to "ready", "note" to "complete"))
            assertEquals("stopped", fin["status"]!!.jsonPrimitive.content)
        }
        val r = applyWithLink()
        assertEquals(Outcomes.DONE, r.outcome)

        val review = outbox.pending().last()
        assertContains(review.text, "Ready to submit Job at jobs.lever.co")
        assertContains(review.text, "First name: Richard")
        assertContains(review.text, "Note: complete")
        assertEquals(listOf("✅ Submit", "✖ Discard"), review.buttons.map { it.text })
        assertEquals(listOf("goto https://jobs.lever.co/acme/1", "fill e1", "upload e2 My Resume.pdf"), page.actions)
        assertFalse("click e3" in page.actions, "nothing was submitted")
        assertEquals(0, sessions.active())

        // The run carried the key, the rules and the fill model.
        assertEquals(0, wrongKeys.get())
        val body = runBodies.single()
        assertContains(body["instructions"]!!.jsonPrimitive.content, "NEVER click the final submit")
        assertEquals("deepseek-v4.1-flash:cloud", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Richard's Submit tap is still the only thing that submits`() {
        planner = { mcp, token ->
            mcp.call("fill_snapshot", mapOf("session" to token))
            mcp.call("fill_act", mapOf("session" to token, "actions" to actionList(mapOf("do" to "fill", "id" to "e1", "value" to "Richard"))))
            mcp.call("fill_finish", mapOf("session" to token, "status" to "ready", "note" to "ok"))
        }
        applyWithLink()
        assertFalse("click e3" in page.actions)
        val id = outbox.pending().last().buttons.first().callbackData.substringAfter(':').toLong()
        val tapped = taps.tap(TapRequest("submit", id, user, user, 5, Instant.now().epochSecond))
        assertEquals(Outcomes.DONE, tapped.outcome)
        assertTrue("click e3" in page.actions)
        assertEquals("Submitted: Job at jobs.lever.co.", outbox.pending().last().text)
    }

    // ── what Hermes cannot do ────────────────────────────────────────────────────────────────

    @Test
    fun `a planner that tries to submit, leave the site or read mail is refused at every step`() {
        var results: List<String> = emptyList()
        planner = { mcp, token ->
            mcp.call("fill_snapshot", mapOf("session" to token))
            val (_, acted) = mcp.call("fill_act", mapOf("session" to token, "actions" to actionList(mapOf("do" to "click", "id" to "e3"))))
            results = acted["results"]!!.jsonArray.map { it.jsonObject["result"]!!.jsonPrimitive.content }
            val (_, nav) = mcp.call("fill_act", mapOf("session" to token, "actions" to actionList(mapOf("do" to "goto", "url" to "https://mail.google.com/mail/u/0"))))
            results = results + nav["results"]!!.jsonArray.map { it.jsonObject["result"]!!.jsonPrimitive.content }
            mcp.call("fill_finish", mapOf("session" to token, "status" to "need_human", "note" to "could not continue"))
        }
        applyWithLink()
        assertTrue(results[0].startsWith("refused") && "Never submit" in results[0], results.toString())
        assertTrue(results[1].startsWith("refused") && "not allowed" in results[1], results.toString())
        assertEquals(listOf("goto https://jobs.lever.co/acme/1"), page.actions, "the browser did nothing the rules forbid")
    }

    @Test
    fun `the tools do nothing without the token of a fill that jobbot-mcp started`() {
        var answer: Pair<Boolean?, String>? = null
        planner = { mcp, token ->
            val r = mcp.callTool("fill_snapshot", mapOf("session" to "0".repeat(32)))
            answer = r.isError to (r.content.single() as TextContent).text
            mcp.call("fill_finish", mapOf("session" to token, "status" to "need_human", "note" to "x"))
        }
        applyWithLink()
        assertEquals(true, answer!!.first)
        assertContains(answer!!.second, "No fill is running with that session token")
    }

    @Test
    fun `the planner's server offers the four fill tools and every action, and nothing else`() {
        runBlocking {
            val mcp = Client(Implementation("lister", "1"))
            mcp.connect(StreamableHttpClientTransport(http, "http://127.0.0.1:$mcpPort/mcp-fill", requestBuilder = { header("Authorization", "Bearer $mcpToken") }))
            try {
                val fill = mcp.listTools().tools.associateBy { it.name }
                assertEquals(setOf("fill_context", "fill_snapshot", "fill_act", "fill_finish"), fill.keys, "no job, mail, draft, track or cargo tool is reachable from a fill")
                val enum = fill.getValue("fill_act").inputSchema.properties!!["actions"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["do"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(FillExecutor.ACTIONS, enum, "FillAgent's twelve actions, all of them, nothing more")
                assertEquals(listOf("session", "actions"), fill.getValue("fill_act").inputSchema.required)
                assertEquals(listOf("session", "status"), fill.getValue("fill_finish").inputSchema.required)
                assertEquals(false, fill.getValue("fill_act").annotations?.destructiveHint)
            } finally { mcp.close() }
        }
    }

    @Test
    fun `the chat's server does not offer the fill tools`() {
        runBlocking {
            val mcp = Client(Implementation("chat", "1"))
            mcp.connect(StreamableHttpClientTransport(http, "http://127.0.0.1:$mcpPort/mcp", requestBuilder = { header("Authorization", "Bearer $mcpToken") }))
            try {
                val names = mcp.listTools().tools.map { it.name }
                assertTrue(names.none { it.startsWith("fill_") }, names.toString())
                assertTrue("get_job" in names, "the chat keeps its own tools")
            } finally { mcp.close() }
        }
    }

    @Test
    fun `the fill endpoint needs the token like every other route`() {
        runBlocking {
            assertEquals(io.ktor.http.HttpStatusCode.Unauthorized, http.post("http://127.0.0.1:$mcpPort/mcp-fill") { setBody("{}") }.status)
        }
    }

    // ── hand-offs and failures reach Richard ─────────────────────────────────────────────────

    @Test
    fun `a hand-off from the planner is posted with Continue and Discard`() {
        planner = { mcp, token ->
            mcp.call("fill_snapshot", mapOf("session" to token))
            mcp.call("fill_finish", mapOf("session" to token, "status" to "need_human", "note" to "Which visa status applies?"))
        }
        applyWithLink()
        val msg = outbox.pending().last()
        assertContains(msg.text, "needs you: Which visa status applies?")
        assertEquals(listOf("▶ Continue", "✖ Discard"), msg.buttons.map { it.text })
    }

    @Test
    fun `a real CAPTCHA is handed off by code before the planner can act, an invisible one is not`() {
        page.snap = form.copy(captcha = true)
        planner = { mcp, token ->
            val (_, snap) = mcp.call("fill_snapshot", mapOf("session" to token))
            assertEquals("stopped", snap["status"]!!.jsonPrimitive.content)
        }
        applyWithLink()
        assertContains(outbox.pending().last().text, "CAPTCHA")
    }

    @Test
    fun `a planner that crashes is reported as a failed fill, not a hang`() {
        planner = { _, _ -> error("the model provider fell over") }
        applyWithLink()
        assertContains(outbox.pending().last().text, "Couldn't fill")
        assertContains(outbox.pending().last().text, "model provider fell over")
    }

    @Test
    fun `a planner that gives up without finishing hands the form to Richard`() {
        planner = { mcp, token -> mcp.call("fill_snapshot", mapOf("session" to token)) }
        applyWithLink()
        assertContains(outbox.pending().last().text, "stopped before the form was ready")
    }

    @Test
    fun `Continue after a hand-off starts a fresh run on the same tab without reloading it`() {
        var calls = 0
        planner = { mcp, token ->
            mcp.call("fill_snapshot", mapOf("session" to token))
            if (++calls == 1) mcp.call("fill_finish", mapOf("session" to token, "status" to "need_human", "note" to "CAPTCHA"))
            else {
                mcp.call("fill_act", mapOf("session" to token, "actions" to actionList(mapOf("do" to "fill", "id" to "e1", "value" to "Richard"))))
                mcp.call("fill_finish", mapOf("session" to token, "status" to "ready", "note" to "done"))
            }
        }
        applyWithLink()
        val resumeId = outbox.pending().last().buttons.first().callbackData.substringAfter(':').toLong()
        taps.tap(TapRequest("resume", resumeId, user, user, 5, Instant.now().epochSecond))
        assertEquals(2, runBodies.size)
        assertContains(runBodies[1]["input"]!!.jsonPrimitive.content, "Richard has just finished a step by hand")
        assertEquals(1, page.actions.count { it.startsWith("goto") }, "the tab was not reloaded")
        assertContains(outbox.pending().last().text, "Ready to submit")
        assertContains(outbox.pending().last().text, "First name: Richard")
    }

    @Test
    fun `the packet's answers and the resume file reach the planner as context`() {
        var context: JsonObject? = null
        planner = { mcp, token ->
            context = mcp.call("fill_context", mapOf("session" to token)).second
            mcp.call("fill_finish", mapOf("session" to token, "status" to "need_human", "note" to "x"))
        }
        applyWithLink()
        assertContains(context!!["resume_file"]!!.jsonPrimitive.content, "My Resume.pdf")
        assertContains(context!!["resume_file"]!!.jsonPrimitive.content, "answer from candidate_profile_yaml only")
    }
}
