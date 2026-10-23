package com.jd.jobbot.http

import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.ApplyNotImplemented
import com.jd.jobbot.actions.Outcomes
import com.jd.jobbot.actions.ResumeApplyHandler
import com.jd.jobbot.actions.TapResponse
import com.jd.jobbot.support.ResumeFiles
import com.jd.jobbot.actions.StatusReport
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.files.OutputFiles
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.mcp.JobbotTools
import com.jd.jobbot.profile.ProfileReader
import com.jd.jobbot.support.FakeBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real HTTP surface on a real port: auth on /plugin and /mcp, the tap and status endpoints,
 * and an actual MCP client (the SDK's Streamable HTTP transport, as Hermes uses) listing and
 * calling the tools.
 */
class ServerTest {
    private val token = "test-token"
    private val user = "8679792351"
    private lateinit var bridge: FakeBridge
    private lateinit var server: EmbeddedServer<*, *>
    private val port = ServerSocket(0).use { it.localPort }
    private val base get() = "http://127.0.0.1:$port"
    private val http = HttpClient(ClientCIO) { install(SSE) }

    @BeforeTest
    fun start() {
        bridge = FakeBridge(events = listOf(FakeBridge.event(11), FakeBridge.event(12, jobUrl = null)))
        val client = BridgeReadClient(bridge.url)
        val lookup = JobLookup(client)
        val store = ActionStore(":memory:")
        val taps = TapService(
            lookup, store, mapOf("apply" to ApplyNotImplemented), setOf(user), setOf("apply"), false, Duration.ofDays(7),
            resumeApply = ResumeApplyHandler { ctx, _, upload -> TapResponse(Outcomes.DONE, reply = "Filling ${ctx.ref} with ${upload.fileName}") },
        )
        val tools = JobbotTools(lookup, client, OutputFiles("/nonexistent"), ProfileReader("/nonexistent/a", "/nonexistent/b"), 55, 400)
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            jobbotModule(token, listOf("127.0.0.1", "localhost"), taps, StatusReport(client, store, setOf("apply"), false), tools)
        }.start(wait = false)
    }

    @AfterTest
    fun stop() {
        http.close()
        server.stop(100, 500)
        bridge.close()
    }

    private fun tapBody(verb: String = "apply", seq: Long = 11, from: String = user) =
        """{"verb":"$verb","seq":$seq,"user_id":"$from","chat_id":"$user","message_id":5,"message_date":${Instant.now().epochSecond}}"""

    @Test
    fun `health needs no token`() = runBlocking {
        assertEquals(HttpStatusCode.OK, http.get("$base/health").status)
    }

    @Test
    fun `plugin and mcp routes refuse a missing or wrong token`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, http.post("$base/plugin/tap") { setBody(tapBody()) }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("$base/plugin/status") { header("Authorization", "Bearer nope") }.status)
        assertEquals(HttpStatusCode.Unauthorized, http.post("$base/mcp") { setBody("{}") }.status)
    }

    @Test
    fun `an Apply tap answers Not implemented yet`() = runBlocking {
        val resp = http.post("$base/plugin/tap") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(tapBody())
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val o = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("not_implemented", o["outcome"]!!.jsonPrimitive.content)
        assertEquals("Not implemented yet", o["reply"]!!.jsonPrimitive.content)
        assertTrue("action_row" !in o, "null fields are omitted so the plugin leaves the keyboard alone: $o")
    }

    private fun resumeBody(seq: String? = "11", url: String? = null, from: String = user, name: String = "Richard.pdf", data: ByteArray = ResumeFiles.pdf()) =
        buildString {
            append("""{"user_id":"$from","chat_id":"$user","file_name":"$name","file_data":"${ResumeFiles.b64(data)}"""")
            seq?.let { append(""","seq":$it,"message_id":5,"message_date":${Instant.now().epochSecond}""") }
            url?.let { append(""","job_url":"$it"""") }
            append("}")
        }

    private suspend fun postResume(body: String, auth: Boolean = true) = http.post("$base/plugin/apply-resume") {
        if (auth) header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json); setBody(body)
    }

    @Test
    fun `apply-resume refuses a missing or wrong token`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, postResume(resumeBody(), auth = false).status)
        val wrong = http.post("$base/plugin/apply-resume") { header("Authorization", "Bearer nope"); setBody(resumeBody()) }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }

    @Test
    fun `a resume replied to a card starts the application`() = runBlocking {
        val resp = postResume(resumeBody())
        assertEquals(HttpStatusCode.OK, resp.status)
        val o = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("done", o["outcome"]!!.jsonPrimitive.content)
        assertEquals("Filling #J11 with Richard.pdf", o["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a resume with a job link starts an ad hoc application`() = runBlocking {
        val o = Json.parseToJsonElement(postResume(resumeBody(seq = null, url = "https://jobs.lever.co/acme/1")).bodyAsText()).jsonObject
        assertEquals("Filling Job at jobs.lever.co with Richard.pdf", o["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a refused resume comes back as a reply for the chat, not an error status`() = runBlocking {
        val resp = postResume(resumeBody(name = "Richard.exe"))
        assertEquals(HttpStatusCode.OK, resp.status)
        val o = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("refused", o["outcome"]!!.jsonPrimitive.content)
        assertEquals("Only a PDF or a Word .docx resume can be used.", o["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a resume from a stranger is refused`() = runBlocking {
        val o = Json.parseToJsonElement(postResume(resumeBody(from = "123")).bodyAsText()).jsonObject
        assertEquals("Not authorized.", o["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a resume for a card that has no posting URL is refused`() = runBlocking {
        val o = Json.parseToJsonElement(postResume(resumeBody(seq = "12")).bodyAsText()).jsonObject
        assertEquals("#J12 has no posting URL, so there's nothing to apply to.", o["reply"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a malformed apply-resume body is a 400`() = runBlocking {
        assertEquals(HttpStatusCode.BadRequest, postResume("""{"user_id":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, postResume("not json").status)
    }

    @Test
    fun `a queued document is served to the plugin, with the token`() = runBlocking {
        val outbox = com.jd.jobbot.actions.Outbox(java.nio.file.Files.createTempFile("ob", ".db").toString(), java.nio.file.Files.createTempDirectory("shots"))
        val id = outbox.postDocument("Résumé", "Resume.pdf", byteArrayOf(37, 80, 68, 70))
        val client = BridgeReadClient(bridge.url)
        val lookup = JobLookup(client)
        val store = ActionStore(":memory:")
        val port2 = ServerSocket(0).use { it.localPort }
        val srv = embeddedServer(CIO, port = port2, host = "127.0.0.1") {
            jobbotModule(
                token, listOf("127.0.0.1"), TapService(lookup, store, emptyMap(), setOf(user), setOf("apply"), false, Duration.ofDays(7)),
                StatusReport(client, store, setOf("apply"), false),
                JobbotTools(lookup, client, OutputFiles("/nonexistent"), ProfileReader("/nonexistent/a", "/nonexistent/b"), 55, 400),
                outbox = outbox,
            )
        }.start(wait = false)
        try {
            val url = "http://127.0.0.1:$port2/plugin/outbox/$id/document"
            assertEquals(HttpStatusCode.Unauthorized, http.get(url).status)
            val ok = http.get(url) { header("Authorization", "Bearer $token") }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(listOf<Byte>(37, 80, 68, 70), ok.readRawBytes().toList())
            assertEquals(HttpStatusCode.NotFound, http.get("http://127.0.0.1:$port2/plugin/outbox/9999/document") { header("Authorization", "Bearer $token") }.status)
            val listed = Json.parseToJsonElement(http.get("http://127.0.0.1:$port2/plugin/outbox") { header("Authorization", "Bearer $token") }.bodyAsText()).let { it as kotlinx.serialization.json.JsonArray }
            assertEquals("Resume.pdf", listed.single().jsonObject["document_name"]!!.jsonPrimitive.content)
            assertEquals("true", listed.single().jsonObject["has_document"]!!.jsonPrimitive.content)
        } finally {
            srv.stop(100, 500)
            outbox.close()
        }
    }

    @Test
    fun `a malformed tap is a 400`() = runBlocking {
        val resp = http.post("$base/plugin/tap") { header("Authorization", "Bearer $token"); setBody("""{"verb":1}""") }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `status returns the jdstatus text`() = runBlocking {
        val text = Json.parseToJsonElement(
            http.get("$base/plugin/status") { header("Authorization", "Bearer $token") }.bodyAsText(),
        ).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("JobBot status"), text)
        assertTrue(text.contains("Latest JFAA job: #J12"), text)
    }

    @Test
    fun `an MCP client lists the read-only tools and calls get_job`() = runBlocking {
        val transport = StreamableHttpClientTransport(http, "$base/mcp", requestBuilder = { header("Authorization", "Bearer $token") })
        val client = Client(Implementation("jobbot-test", "1"))
        client.connect(transport)
        try {
            val tools = client.listTools().tools
            assertEquals(
                setOf("get_job", "list_high_fit", "read_job_file", "list_tracks", "get_profile"),
                tools.map { it.name }.toSet(),
            )
            tools.forEach { assertEquals(true, it.annotations?.readOnlyHint, "${it.name} must be read-only") }

            val result = client.callTool("get_job", mapOf("ref" to "#J11"))
            val text = (result.content.single() as TextContent).text
            assertTrue(text.contains("\"company\": \"Acme\""), text)

            bridge.failWith = 500
            val outage = client.callTool("get_job", mapOf("ref" to "#J11"))
            assertEquals(true, outage.isError, "a bridge outage must be a tool error, not a transport failure")

            // A malformed feed event (no completed_seq) must not crash the tool either.
            bridge.failWith = null
            bridge.events = bridge.events + kotlinx.serialization.json.buildJsonObject {
                put("completed_seq", kotlinx.serialization.json.JsonPrimitive(13)); put("fit_score", kotlinx.serialization.json.JsonPrimitive(90))
            }
            val listed = client.callTool("list_high_fit", emptyMap())
            assertEquals(null, listed.isError, (listed.content.single() as TextContent).text)
        } finally {
            client.close()
        }
    }
}
