package com.jd.jobbot.http

import com.google.common.util.concurrent.MoreExecutors
import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.IdVerbHandler
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.StatusReport
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.apply.ApplyService
import com.jd.jobbot.apply.CargoStatusListener
import com.jd.jobbot.apply.Credentials
import com.jd.jobbot.apply.Element
import com.jd.jobbot.apply.FakePage
import com.jd.jobbot.apply.FillAgent
import com.jd.jobbot.apply.FillResult
import com.jd.jobbot.apply.JobContext
import com.jd.jobbot.apply.Snapshot
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.cargo.CargoRepo
import com.jd.jobbot.cargo.ProcessGit
import com.jd.jobbot.files.OutputFiles
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.mcp.CargoTools
import com.jd.jobbot.mcp.JobbotTools
import com.jd.jobbot.profile.ProfileReader
import com.jd.jobbot.support.CargoRemote
import com.jd.jobbot.support.FakeBridge
import com.jd.jobbot.support.ResumeFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole path a Telegram request takes, minus Telegram and the model: a real MCP client over HTTP (as Hermes
 * connects), the real server, a real git remote standing in for GitHub, and the real ApplyService with a fake
 * browser. The plugin's side is exercised through the same /plugin routes it calls.
 */
class CargoEndToEndTest {
    @TempDir lateinit var tmp: Path
    private val token = "e2e-token"
    private val user = "8679792351"
    private lateinit var remote: CargoRemote
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var outbox: Outbox
    private lateinit var bridge: FakeBridge
    private lateinit var store: ActionStore
    private val jobs = java.util.concurrent.CopyOnWriteArrayList<JobContext>()
    private val port = ServerSocket(0).use { it.localPort }
    private val base get() = "http://127.0.0.1:$port"
    private val http = HttpClient(ClientCIO) { install(SSE); install(HttpTimeout) { requestTimeoutMillis = 60_000 } }
    private val form = Snapshot(
        "https://jobs.example.com/apply",
        elements = listOf(
            Element("e1", "input", "text", "First name", value = "Richard", inForm = true, formFilled = true),
            Element("e3", "button", "submit", text = "Submit application", inForm = true, formFilled = true),
        ),
    )

    @BeforeTest
    fun start() {
        remote = CargoRemote(tmp.resolve("r").also { Files.createDirectories(it) })
        remote.packet("apple", "tools", 3, title = "Tools Engineer")
        remote.packet("apple", "sdet", 2, title = "SDET")
        remote.packet("flex", "sdet", statusLines = listOf("- 2026-10-16T16:05:00Z | completed | confirmed"))
        remote.packet("tiktok", "qa", status = "DRAFT", reason = "needs review")
        remote.publish()
        bridge = FakeBridge(events = emptyList())
        val client = BridgeReadClient(bridge.url)
        val lookup = JobLookup(client)
        store = ActionStore(":memory:")
        outbox = Outbox(tmp.resolve("o.db").toString(), tmp.resolve("shots"))
        val repo = CargoRepo(tmp.resolve("checkout"), remote.bare.toString(), "main", ProcessGit(null), syncInterval = Duration.ZERO)
        val page = FakePage(form).also { p -> p.onClick = { if (it == "e3") p.snap = p.snap.copy(url = p.snap.url + "/done", text = "Thank you for applying!") } }
        val service = ApplyService(
            browser = { _ -> page },
            fill = { p, job, _ -> jobs += job; FillResult.Ready(FillAgent.fieldsOf(p.snapshot()), byteArrayOf(9), "") },
            lookup = lookup,
            contextFor = { JobContext("", null, null, "", "dkkytech@gmail.com", "years: 15+", null, null, null) },
            store = store, outbox = outbox, tracks = null, viewerUrl = null,
            credentials = Credentials(tmp.resolve("creds.json"), "dkkytech@gmail.com"),
            cargoStatus = CargoStatusListener { folder, state, note -> repo.appendStatus(folder, state, note) },
            worker = MoreExecutors.newDirectExecutorService(),
        )
        val taps = TapService(
            lookup, store, emptyMap(), setOf(user), setOf("apply"), false, Duration.ofDays(7),
            idHandlers = mapOf("submit" to IdVerbHandler(service::submit), "discard" to IdVerbHandler(service::discard), "resume" to IdVerbHandler(service::resume)),
            resumeApply = service.applyWithResume,
        )
        val tools = JobbotTools(
            lookup, client, OutputFiles("/nonexistent"), ProfileReader("/nonexistent/a", "/nonexistent/b"), 55, 400,
            cargo = CargoTools(repo, outbox, service),
        )
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            jobbotModule(token, listOf("127.0.0.1", "localhost"), taps, StatusReport(client, store, setOf("apply"), false), tools, outbox = outbox)
        }.start(wait = false)
    }

    @AfterTest
    fun stop() {
        http.close()
        server.stop(100, 500)
        outbox.close()
        bridge.close()
    }

    private suspend fun <T> mcp(block: suspend (Client) -> T): T {
        val client = Client(Implementation("e2e", "1"))
        client.connect(StreamableHttpClientTransport(http, "$base/mcp", requestBuilder = { header("Authorization", "Bearer $token") }))
        try { return block(client) } finally { client.close() }
    }

    private suspend fun Client.call(name: String, args: Map<String, Any?> = emptyMap()) = callTool(name, args).let { r ->
        (r.content.single() as TextContent).text.also { if (r.isError == true) throw AssertionError("$name failed: $it") }
    }

    private suspend fun Client.callExpectingError(name: String, args: Map<String, Any?> = emptyMap()) =
        callTool(name, args).let { r -> assertEquals(true, r.isError, "$name should fail"); (r.content.single() as TextContent).text }

    private suspend fun get(path: String): HttpResponse = http.get("$base$path") { header("Authorization", "Bearer $token") }
    private suspend fun post(path: String, body: String): HttpResponse = http.post("$base$path") {
        header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun outboxItems() = (Json.parseToJsonElement(get("/plugin/outbox").bodyAsText()) as JsonArray).map { it.jsonObject }
    private fun tapBody(verb: String, id: Long) = """{"verb":"$verb","seq":$id,"user_id":"$user","chat_id":"$user","message_id":5,"message_date":${Instant.now().epochSecond}}"""
    private fun statusOnRemote(folder: String) = Files.readString(remote.freshClone().resolve("$folder/STATUS.md")).lines().filter { it.startsWith("- ") }.map { it.split(" | ")[1] }

    @Test
    fun `the model gets the cargo tools over MCP and none of them can submit`() = runBlocking {
        mcp { c ->
            val tools = c.listTools().tools.associateBy { it.name }
            val cargo = setOf("cargo_list", "cargo_get_packet", "cargo_files", "cargo_read_file", "cargo_search", "cargo_send_resume", "cargo_send_file", "cargo_append_status", "cargo_apply", "cargo_apply_ready")
            assertTrue(tools.keys.containsAll(cargo), tools.keys.toString())
            for (name in listOf("cargo_list", "cargo_get_packet", "cargo_files", "cargo_read_file", "cargo_search")) {
                assertEquals(true, tools.getValue(name).annotations?.readOnlyHint, "$name is a read")
            }
            for (name in listOf("cargo_send_resume", "cargo_send_file", "cargo_append_status", "cargo_apply", "cargo_apply_ready")) {
                assertEquals(false, tools.getValue(name).annotations?.readOnlyHint, "$name changes something")
                assertEquals(false, tools.getValue(name).annotations?.destructiveHint, "$name destroys nothing")
            }
            assertTrue(tools.keys.none { it.contains("submit") || it.contains("delete") || it.contains("write_file") || it.contains("shell") }, tools.keys.toString())
        }
    }

    @Test
    fun `an unauthenticated client gets nothing`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, http.post("$base/mcp") { setBody("{}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("$base/plugin/outbox").status)
    }

    @Test
    fun `how many are ready, and which are in draft`() = runBlocking {
        mcp { c ->
            val o = Json.parseToJsonElement(c.call("cargo_list")).jsonObject
            assertEquals(4, o["total_roles"]!!.jsonPrimitive.content.toInt())
            assertEquals(3, o["by_status"]!!.jsonObject["READY"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, o["by_status"]!!.jsonObject["DRAFT"]!!.jsonPrimitive.content.toInt())
            assertEquals(2, o["ready_to_apply"]!!.jsonPrimitive.content.toInt())
            val drafts = Json.parseToJsonElement(c.call("cargo_list", mapOf("status" to "DRAFT"))).jsonObject["packets"]!!.jsonArray
            assertEquals("Tiktok", drafts.single().jsonObject["company"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `send me the latest resume for a company reaches the plugin as a document`() = runBlocking {
        mcp { c -> assertTrue(c.call("cargo_send_resume", mapOf("company" to "flex")).contains("queued")) }
        val item = outboxItems().single()
        assertEquals("true", item["has_document"]!!.jsonPrimitive.content)
        assertEquals("Richard-Hatcher-Resume.pdf", item["document_name"]!!.jsonPrimitive.content)
        val bytes = get("/plugin/outbox/${item["id"]!!.jsonPrimitive.content}/document").readRawBytes()
        assertTrue(bytes.contentEquals(remote.resumes.getValue("applications/flex/sdet/v00")), "the exact file in the packet")
        post("/plugin/outbox/${item["id"]!!.jsonPrimitive.content}/delivered", "{}")
        assertEquals(0, outboxItems().size, "delivered items leave the outbox")
    }

    @Test
    fun `apply to all the ready packets runs to the Submit tap and the whole trail is pushed to GitHub`() = runBlocking {
        val started = mcp { c -> Json.parseToJsonElement(c.call("cargo_apply_ready")).jsonObject }
        assertEquals(2, started["started"]!!.jsonArray.size)
        assertTrue(started["skipped"]!!.jsonArray.any { it.jsonObject["packet"]!!.jsonPrimitive.content.contains("tiktok") }, "the DRAFT is listed, not applied")

        // Nothing is submitted by the model: both applications wait for a tap, and GitHub already says so.
        val reviews = outboxItems().filter { it["text"]!!.jsonPrimitive.content.startsWith("Ready to submit") }
        assertEquals(2, reviews.size)
        assertEquals(listOf("in_progress", "parked_at_review"), statusOnRemote("applications/apple/tools/v03"))
        assertEquals(listOf("in_progress", "parked_at_review"), statusOnRemote("applications/apple/sdet/v02"))

        val review = reviews.first { it["text"]!!.jsonPrimitive.content.contains("Tools Engineer") }
        val submitId = review["buttons"]!!.jsonArray.first().jsonObject["callback_data"]!!.jsonPrimitive.content.substringAfter(':').toLong()
        val tap = Json.parseToJsonElement(post("/plugin/tap", tapBody("submit", submitId)).bodyAsText()).jsonObject
        assertEquals("done", tap["outcome"]!!.jsonPrimitive.content)
        assertEquals(listOf("in_progress", "parked_at_review", "completed"), statusOnRemote("applications/apple/tools/v03"))
        assertEquals(listOf("in_progress", "parked_at_review"), statusOnRemote("applications/apple/sdet/v02"), "the other one still waits for its own tap")
        assertEquals(emptyList(), statusOnRemote0("applications/tiktok/qa/v00"), "the DRAFT was never touched")

        // And a second "apply to all the ready packets" starts nothing: the first is completed, the second is parked.
        val again = mcp { c -> Json.parseToJsonElement(c.call("cargo_apply_ready")).jsonObject }
        assertEquals(0, again["started"]!!.jsonArray.size)
        assertEquals(2, jobs.size)
    }

    @Test
    fun `each application uploads its own packet's file and answers`() = runBlocking {
        mcp { c -> c.call("cargo_apply_ready") }
        assertEquals(2, jobs.size)
        for (job in jobs) {
            val folder = if (job.title == "Tools Engineer") "applications/apple/tools/v03" else "applications/apple/sdet/v02"
            assertTrue(job.resumePdf!!.contentEquals(remote.resumes.getValue(folder)), "${job.title} uploads its own résumé")
            assertTrue(job.answers!!.contains("Sponsorship: No"))
            assertEquals("years: 15+", job.profileYaml)
        }
    }

    @Test
    fun `two clients asking for the same packet at once start one application`() = runBlocking {
        val results = (1..4).map { async { mcp { c -> c.callTool("cargo_apply", mapOf("folder" to "applications/apple/tools/v03")).isError } } }.awaitAll()
        assertEquals(1, results.count { it != true }, results.toString())
        assertEquals(1, jobs.size)
        assertEquals(1, statusOnRemote("applications/apple/tools/v03").count { it == "in_progress" })
    }

    @Test
    fun `a DRAFT is only applied to with Richard's words, and they land in STATUS`() = runBlocking {
        mcp { c ->
            assertTrue(c.callExpectingError("cargo_apply", mapOf("folder" to "applications/tiktok/qa/v00")).contains("needs Richard's approval"))
            assertEquals(0, jobs.size)
            c.call("cargo_apply", mapOf("folder" to "applications/tiktok/qa/v00", "approval_quote" to "go ahead with the tiktok draft"))
        }
        assertEquals(1, jobs.size)
        val status = Files.readString(remote.freshClone().resolve("applications/tiktok/qa/v00/STATUS.md"))
        assertTrue(status.contains("draft approved: \"go ahead with the tiktok draft\""), status)
    }

    @Test
    fun `the only thing the model can write is a status line, and it is pushed`() = runBlocking {
        mcp { c -> c.call("cargo_append_status", mapOf("folder" to "applications/apple/tools/v03", "state" to "blocked", "note" to "needs a phone code")) }
        val changed = CargoRemote.git(remote.freshClone(), "log", "-1", "--name-only", "--format=%an|%s").lines().filter { it.isNotBlank() }
        assertEquals(listOf("JobBot|status: apple blocked", "applications/apple/tools/v03/STATUS.md"), changed)
    }

    @Test
    fun `a resume sent with a job link runs end to end through the plugin route`() = runBlocking {
        val body = """{"user_id":"$user","chat_id":"$user","file_name":"My Resume.pdf","file_data":"${ResumeFiles.b64(ResumeFiles.pdf())}","job_url":"https://jobs.lever.co/acme/1#apply"}"""
        val resp = Json.parseToJsonElement(post("/plugin/apply-resume", body).bodyAsText()).jsonObject
        assertEquals("done", resp["outcome"]!!.jsonPrimitive.content)
        assertTrue(resp["reply"]!!.jsonPrimitive.content.contains("with your resume (My Resume.pdf)"))
        assertEquals("https://jobs.lever.co/acme/1", jobs.single().jobUrl)
        assertEquals("My Resume.pdf", jobs.single().resumeFileName)
        val review = outboxItems().last()
        val id = review["buttons"]!!.jsonArray.first().jsonObject["callback_data"]!!.jsonPrimitive.content.substringAfter(':').toLong()
        assertEquals("done", Json.parseToJsonElement(post("/plugin/tap", tapBody("submit", id)).bodyAsText()).jsonObject["outcome"]!!.jsonPrimitive.content)
        assertTrue(outboxItems().last()["text"]!!.jsonPrimitive.content.startsWith("Submitted: Job at jobs.lever.co"))
        val lines = listOf("applications/apple/tools/v03", "applications/apple/sdet/v02", "applications/flex/sdet/v00", "applications/tiktok/qa/v00").flatMap { statusOnRemote0(it) }
        assertEquals(1, lines.size, "only Flex's seeded line exists: a pasted link wrote nothing to the cargo repo")
    }

    private fun statusOnRemote0(folder: String): List<String> {
        val f = remote.freshClone().resolve("$folder/STATUS.md")
        return if (Files.exists(f)) Files.readString(f).lines().filter { it.startsWith("- ") } else emptyList()
    }

    @Test
    fun `the biggest allowed resume gets through the HTTP route, one byte more does not`() = runBlocking {
        val limit = com.jd.jobbot.actions.ResumeUpload.MAX_BYTES
        val head = ResumeFiles.pdf()
        val exact = head + ByteArray(limit - head.size)
        val ok = Json.parseToJsonElement(
            post("/plugin/apply-resume", """{"user_id":"$user","chat_id":"$user","file_name":"big.pdf","file_data":"${ResumeFiles.b64(exact)}","job_url":"https://jobs.lever.co/acme/big"}""").bodyAsText(),
        ).jsonObject
        assertEquals("done", ok["outcome"]!!.jsonPrimitive.content, ok.toString())
        assertEquals(limit, jobs.single().resumePdf!!.size)

        val tooBig = Json.parseToJsonElement(
            post("/plugin/apply-resume", """{"user_id":"$user","chat_id":"$user","file_name":"big.pdf","file_data":"${ResumeFiles.b64(exact + byteArrayOf(0))}","job_url":"https://jobs.lever.co/acme/bigger"}""").bodyAsText(),
        ).jsonObject
        assertEquals("refused", tooBig["outcome"]!!.jsonPrimitive.content)
        assertEquals(1, jobs.size)
    }

    @Test
    fun `jdstatus shows pasted-link and packet applications without job numbers`() = runBlocking {
        mcp { c -> c.call("cargo_apply", mapOf("folder" to "applications/apple/tools/v03")) }
        val text = Json.parseToJsonElement(get("/plugin/status").bodyAsText()).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("fill (not a JFAA job)"), text)
        assertFalse(text.contains("fill #J0"), text)
    }
}
