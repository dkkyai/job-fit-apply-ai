package com.jd.jobbot.mcp

import com.google.common.util.concurrent.MoreExecutors
import com.jd.jobbot.actions.AD_HOC_SEQ
import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.IdVerbHandler
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.apply.ApplyService
import com.jd.jobbot.apply.CargoStatusListener
import com.jd.jobbot.apply.Credentials
import com.jd.jobbot.apply.Element
import com.jd.jobbot.apply.FakePage
import com.jd.jobbot.apply.FillExecutor
import com.jd.jobbot.apply.FillResult
import com.jd.jobbot.apply.JobContext
import com.jd.jobbot.apply.Snapshot
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.cargo.CargoRepo
import com.jd.jobbot.cargo.ProcessGit
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.CargoRemote
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CargoToolsTest {
    @TempDir lateinit var tmp: Path
    private val now = Instant.parse("2026-10-22T20:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val remote by lazy { CargoRemote(tmp.resolve("r").also { Files.createDirectories(it) }) }
    private val user = "8679792351"

    private val readyForm = Snapshot(
        "https://jobs.example.com/apply",
        elements = listOf(
            Element("e1", "input", "text", "First name", value = "Richard", inForm = true, formFilled = true),
            Element("e3", "button", "submit", text = "Submit application", inForm = true, formFilled = true),
        ),
    )

    private inner class Rig(applyEnabled: Boolean = true, git: com.jd.jobbot.cargo.GitRunner = ProcessGit(null), dryRun: Boolean = false) {
        val repo = CargoRepo(tmp.resolve("checkout"), remote.bare.toString(), "main", git, clock, Duration.ZERO)
        val store = ActionStore(":memory:", clock)
        val outbox = Outbox(tmp.resolve("o.db").toString(), tmp.resolve("shots"), clock)
        val page = FakePage(readyForm).also { p -> p.onClick = { if (it == "e3") p.snap = p.snap.copy(url = p.snap.url + "/done", text = "Thank you for applying!") } }
        val jobs = mutableListOf<JobContext>()
        var outcome: (com.jd.jobbot.apply.ApplyPage) -> FillResult = { p -> FillResult.Ready(FillExecutor.fieldsOf(p.snapshot()), byteArrayOf(9), "") }
        val lookup = JobLookup(BridgeReadClient("http://unused"))
        val service = ApplyService(
            browser = { _ -> page }, fill = { p, job, _ -> jobs += job; outcome(p) }, lookup = lookup,
            contextFor = { JobContext("", null, null, "", "dkkytech@gmail.com", "years: 15+", null, null, null) },
            store = store, outbox = outbox, tracks = null, viewerUrl = null,
            credentials = Credentials(tmp.resolve("creds.json"), "dkkytech@gmail.com", clock),
            cargoStatus = CargoStatusListener { folder, state, note -> repo.appendStatus(folder, state, note) },
            clock = clock, worker = MoreExecutors.newDirectExecutorService(),
        )
        val taps = TapService(
            lookup, store, emptyMap(), setOf(user), setOf("apply"), false, Duration.ofDays(7), clock,
            idHandlers = mapOf("submit" to IdVerbHandler(service::submit), "discard" to IdVerbHandler(service::discard), "resume" to IdVerbHandler(service::resume)),
        )
        val tools = CargoTools(repo, outbox, if (applyEnabled) service else null, clock, dryRun)
        fun tap(verb: String, n: Long) = taps.tap(TapRequest(verb, n, user, user, 5, now.epochSecond))
        fun review() = outbox.pending().last()
        fun fillId() = review().buttons.first { it.callbackData.startsWith("submit:") }.callbackData.substringAfter(':').toLong()
        fun statusOnRemote(folder: String) = Files.readString(remote.freshClone().resolve("$folder/STATUS.md")).lines().filter { it.startsWith("- ") }
    }

    private fun req(vararg args: Pair<String, Any>) = CallToolRequest(
        CallToolRequestParams(name = "t", arguments = buildJsonObject { args.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) } }),
    )
    private fun CallToolResult.text() = (content.single() as TextContent).text
    private fun CallToolResult.obj() = Json.parseToJsonElement(text()).jsonObject
    private fun CallToolResult.ok(): JsonObject { assertFalse(isError == true, text()); return obj() }
    private fun CallToolResult.fails(): String { assertTrue(isError == true, text()); return text() }

    private fun seedStandard() {
        remote.packet("apple", "tools", 3, title = "Tools Engineer")
        remote.packet("apple", "sdet", 2, title = "SDET")
        remote.packet("becu", "sdet", 1, title = "Sr SDET", statusLines = emptyList())
        remote.packet("becu", "sdet", 0, statusLines = listOf("- 2026-10-01T00:00:00Z | completed | done"))
        remote.packet("capgemini", "sdet", status = "DRAFT", reason = "open questions", url = null)
        remote.packet("flex", "sdet", statusLines = listOf("- 2026-10-16T16:05:00Z | completed | confirmed"))
        remote.packet("tiktok", "qa", status = "DRAFT", reason = "needs review")
        remote.publish()
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cargo_list counts ready and draft and says what can be applied to`() {
        seedStandard()
        val o = Rig().tools.list(req()).ok()
        assertEquals(6, o["total_roles"]!!.jsonPrimitive.int)
        assertEquals(4, o["by_status"]!!.jsonObject["READY"]!!.jsonPrimitive.int)
        assertEquals(2, o["by_status"]!!.jsonObject["DRAFT"]!!.jsonPrimitive.int)
        assertEquals(5, o["by_application"]!!.jsonObject["not_started"]!!.jsonPrimitive.int)
        assertEquals(1, o["by_application"]!!.jsonObject["completed"]!!.jsonPrimitive.int)
        assertEquals(2, o["ready_to_apply"]!!.jsonPrimitive.int, "apple tools and apple sdet; becu has an older version worked")
    }

    @Test
    fun `cargo_list filters by status, application and company`() {
        seedStandard()
        val t = Rig().tools
        assertEquals(2, t.list(req("status" to "draft")).ok()["matching"]!!.jsonPrimitive.int)
        assertEquals(1, t.list(req("application" to "completed")).ok()["matching"]!!.jsonPrimitive.int)
        assertEquals(2, t.list(req("company" to "APPLE")).ok()["matching"]!!.jsonPrimitive.int)
        assertEquals(1, t.list(req("limit" to 1)).ok()["packets"]!!.jsonArray.size)
    }

    @Test
    fun `each row says whether it can be applied to and why not`() {
        seedStandard()
        val rows = Rig().tools.list(req()).ok()["packets"]!!.jsonArray.map { it.jsonObject }.associateBy { it["company"]!!.jsonPrimitive.content + "/" + it["folder"]!!.jsonPrimitive.content.split('/')[2] }
        assertEquals("yes", rows.getValue("Apple/tools")["can_apply"]!!.jsonPrimitive.content)
        assertTrue(rows.getValue("Becu/sdet")["can_apply"]!!.jsonPrimitive.content.contains("older version"))
        assertTrue(rows.getValue("Flex/sdet")["can_apply"]!!.jsonPrimitive.content.startsWith("no: already completed"))
        assertTrue(rows.getValue("Tiktok/qa")["can_apply"]!!.jsonPrimitive.content.contains("DRAFT"))
        assertTrue(rows.getValue("Capgemini/sdet")["can_apply"]!!.jsonPrimitive.content.startsWith("no:"))
    }

    @Test
    fun `an unreachable GitHub is flagged but the last copy is still served`() {
        seedStandard()
        val rig = Rig()
        rig.tools.list(req())
        Files.move(remote.bare, tmp.resolve("gone.git"))
        val o = rig.tools.list(req()).ok()
        assertTrue(o["warning"]!!.jsonPrimitive.content.contains("Couldn't refresh from GitHub"))
        assertEquals(6, o["total_roles"]!!.jsonPrimitive.int)
    }

    @Test
    fun `cargo_get_packet returns the packet, its status, answers and a marked posting`() {
        seedStandard()
        val o = Rig().tools.getPacket(req("folder" to "applications/apple/tools/v03")).ok()
        assertTrue(o["application_packet_md"]!!.jsonPrimitive.content.contains("UPLOAD"))
        assertTrue(o["application_answers_md"]!!.jsonPrimitive.content.contains("Sponsorship"))
        assertTrue(o["job_posting_md"]!!.jsonPrimitive.content.startsWith(CargoTools.UNTRUSTED))
        assertTrue(o["files"]!!.jsonArray.any { it.jsonPrimitive.content.endsWith("apply-2p/") })
    }

    @Test
    fun `cargo_get_packet by company works when unique and asks when not`() {
        seedStandard()
        val t = Rig().tools
        assertTrue(t.getPacket(req("company" to "flex")).ok()["packet"]!!.jsonObject["folder"]!!.jsonPrimitive.content.contains("flex"))
        assertTrue(t.getPacket(req("company" to "apple")).fails().contains("More than one role matches"))
        assertTrue(t.getPacket(req("company" to "apple", "role" to "tools")).ok()["packet"] != null)
        assertTrue(t.getPacket(req("company" to "nobody")).fails().contains("No packet"))
        assertTrue(t.getPacket(req("folder" to "applications/becu/sdet/v00")).fails().contains("only the newest version"))
    }

    @Test
    fun `cargo_read_file reads text, marks postings, refuses binaries and escapes`() {
        seedStandard()
        val t = Rig().tools
        val posting = t.readFile(req("path" to "applications/apple/tools/v03/job-posting.md")).ok()
        assertTrue(posting["content"]!!.jsonPrimitive.content.startsWith(CargoTools.UNTRUSTED))
        assertTrue(t.readFile(req("path" to "applications/apple/tools/v03/apply-2p/Richard-Hatcher-Resume.pdf")).fails().contains("binary"))
        assertTrue(t.readFile(req("path" to "../../etc/passwd")).fails().isNotBlank())
        assertTrue(t.readFile(req("path" to ".git/config")).fails().contains("not available"))
        assertTrue(t.readFile(req("path" to "applications")).fails().contains("cargo_files"))
        val short = t.readFile(req("path" to "applications/apple/tools/v03/APPLICATION_PACKET.md", "max_chars" to 500)).ok()
        assertEquals(500, short["content"]!!.jsonPrimitive.content.length)
        assertEquals("true", short["truncated"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cargo_files and cargo_search answer random questions`() {
        seedStandard()
        val t = Rig().tools
        assertTrue(t.files(req()).text().contains("applications/"))
        assertTrue(t.files(req("path" to "applications/apple")).text().contains("applications/apple/tools/"))
        val hits = t.search(req("query" to "sponsorship")).ok()
        assertTrue(hits["matches"]!!.jsonPrimitive.int >= 6)
        assertTrue(t.search(req("query" to "")).fails().isNotBlank())
    }

    // ── sending files ────────────────────────────────────────────────────────────────────────

    @Test
    fun `cargo_send_resume queues the latest pdf as a Telegram document`() {
        seedStandard()
        val rig = Rig()
        rig.tools.sendResume(req("company" to "flex")).ok()
        val item = rig.outbox.pending().single()
        assertTrue(item.hasDocument)
        assertEquals("Richard-Hatcher-Resume.pdf", item.documentName)
        assertContentEquals(remote.resumes.getValue("applications/flex/sdet/v00"), rig.outbox.document(item.id))
        assertTrue(item.text.contains("Flex"), item.text)
    }

    @Test
    fun `the latest version is the one sent`() {
        remote.packet("acme", "sdet", 0); remote.packet("acme", "sdet", 4); remote.publish()
        val rig = Rig()
        rig.tools.sendResume(req("company" to "acme")).ok()
        assertContentEquals(remote.resumes.getValue("applications/acme/sdet/v04"), rig.outbox.document(rig.outbox.pending().single().id))
    }

    @Test
    fun `cargo_send_resume can send the docx or txt copy`() {
        seedStandard()
        val rig = Rig()
        rig.tools.sendResume(req("company" to "flex", "format" to "docx")).ok()
        rig.tools.sendResume(req("company" to "flex", "format" to ".txt")).ok()
        assertEquals(listOf("Richard-Hatcher-Resume.docx", "Richard-Hatcher-Resume.txt"), rig.outbox.pending().map { it.documentName })
        assertTrue(rig.tools.sendResume(req("company" to "flex", "format" to "exe")).fails().contains("pdf, docx or txt"))
    }

    @Test
    fun `an ambiguous or unknown company is not guessed`() {
        seedStandard()
        val rig = Rig()
        assertTrue(rig.tools.sendResume(req("company" to "apple")).fails().contains("More than one role matches"))
        assertTrue(rig.tools.sendResume(req("company" to "nobody")).fails().contains("No packet"))
        assertEquals(emptyList(), rig.outbox.pending())
        rig.tools.sendResume(req("company" to "apple", "role" to "tools")).ok()
        assertEquals(1, rig.outbox.pending().size)
    }

    @Test
    fun `cargo_send_file sends any repo file but nothing outside it`() {
        seedStandard()
        val rig = Rig()
        rig.tools.sendFile(req("path" to "applications/apple/tools/v03/application-answers.md")).ok()
        assertEquals("application-answers.md", rig.outbox.pending().single().documentName)
        assertTrue(rig.tools.sendFile(req("path" to "../checkout/../x")).fails().isNotBlank())
        assertTrue(rig.tools.sendFile(req("path" to ".git/config")).fails().isNotBlank())
        assertTrue(rig.tools.sendFile(req("path" to "applications")).fails().contains("folder"))
        assertEquals(1, rig.outbox.pending().size)
    }

    // ── the one write ────────────────────────────────────────────────────────────────────────

    @Test
    fun `cargo_append_status records what Richard reports`() {
        seedStandard()
        val rig = Rig()
        val o = rig.tools.appendStatus(req("folder" to "applications/apple/tools/v03", "state" to "blocked", "note" to "waiting on a phone code")).ok()
        assertEquals("true", o["pushed"]!!.jsonPrimitive.content)
        assertTrue(rig.statusOnRemote("applications/apple/tools/v03").single().endsWith("| blocked | waiting on a phone code"))
    }

    @Test
    fun `only the known states are accepted`() {
        seedStandard()
        val t = Rig().tools
        for (bad in listOf("done", "submitted", "COMPLETE", "")) {
            assertTrue(t.appendStatus(req("folder" to "applications/apple/tools/v03", "state" to bad.ifEmpty { " " }, "note" to "x")).fails().isNotBlank(), bad)
        }
    }

    @Test
    fun `completed needs Richard's own words`() {
        seedStandard()
        val rig = Rig()
        val f = "applications/apple/tools/v03"
        assertTrue(rig.tools.appendStatus(req("folder" to f, "state" to "completed", "note" to "done")).fails().contains("Richard's own words"))
        rig.tools.appendStatus(req("folder" to f, "state" to "completed", "note" to "applied by hand", "approval_quote" to "I submitted Apple myself")).ok()
        assertTrue(rig.statusOnRemote(f).single().contains("per Richard: \"I submitted Apple myself\""))
    }

    @Test
    fun `a completed packet takes no more lines`() {
        seedStandard()
        val t = Rig().tools
        assertTrue(t.appendStatus(req("folder" to "applications/flex/sdet/v00", "state" to "in_progress", "note" to "x", "approval_quote" to "yes")).fails().contains("already completed"))
    }

    @Test
    fun `in_progress on a DRAFT needs approval and records the quote`() {
        seedStandard()
        val rig = Rig()
        val f = "applications/tiktok/qa/v00"
        assertTrue(rig.tools.appendStatus(req("folder" to f, "state" to "in_progress", "note" to "opened")).fails().contains("approval"))
        rig.tools.appendStatus(req("folder" to f, "state" to "in_progress", "note" to "opened", "approval_quote" to "go ahead with the tiktok draft")).ok()
        assertTrue(rig.statusOnRemote(f).single().contains("draft approved: \"go ahead with the tiktok draft\""))
    }

    @Test
    fun `an older version of a role takes no status lines, with or without approval`() {
        seedStandard() // becu/sdet has v00 (completed) and v01 (newest)
        val rig = Rig()
        val old = "applications/becu/sdet/v00"
        for (state in listOf("completed", "in_progress", "blocked", "parked_at_review")) {
            val msg = rig.tools.appendStatus(req("folder" to old, "state" to state, "note" to "x", "approval_quote" to "yes do it")).fails()
            assertTrue(msg.contains("newest version"), "$state: $msg")
        }
        val noQuote = rig.tools.appendStatus(req("folder" to old, "state" to "completed", "note" to "forged")).fails()
        assertTrue(noQuote.contains("newest version"), noQuote)
        assertEquals(listOf("- 2026-10-01T00:00:00Z | completed | done"), rig.statusOnRemote(old), "nothing was written to the old version")
    }

    @Test
    fun `an older version is judged by the newest, not by its own history`() {
        remote.packet("acme", "sdet", 0); remote.packet("acme", "sdet", 1); remote.publish()
        val rig = Rig()
        assertTrue(rig.tools.appendStatus(req("folder" to "applications/acme/sdet/v00", "state" to "completed", "note" to "x")).fails().contains("newest version"))
        rig.tools.appendStatus(req("folder" to "applications/acme/sdet/v01", "state" to "blocked", "note" to "ok")).ok()
    }

    @Test
    fun `a folder that is not a packet is refused`() {
        seedStandard()
        val t = Rig().tools
        assertTrue(t.appendStatus(req("folder" to "../x", "state" to "blocked", "note" to "x")).fails().isNotBlank())
        assertTrue(t.appendStatus(req("folder" to "applications/nope/x/v00", "state" to "blocked", "note" to "x")).fails().contains("No packet"))
    }

    // ── applying ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cargo_apply fills from the packet and records its progress in STATUS_md`() {
        seedStandard()
        val rig = Rig()
        val f = "applications/apple/tools/v03"
        rig.tools.applyOne(req("folder" to f)).ok()

        val job = rig.jobs.single()
        assertEquals("https://jobs.example.com/apple/tools", job.jobUrl)
        assertEquals("Apple", job.company)
        assertEquals("Tools Engineer", job.title)
        assertEquals("Richard-Hatcher-Resume.pdf", job.resumeFileName)
        assertContentEquals(remote.resumes.getValue(f), job.resumePdf)
        assertTrue(job.answers!!.contains("Sponsorship: No"))
        assertEquals("years: 15+", job.profileYaml)
        assertEquals(null, job.coverLetter)
        assertTrue(rig.review().text.startsWith("Ready to submit Apple — Tools Engineer"), rig.review().text)

        val lines = rig.statusOnRemote(f)
        assertEquals(listOf("in_progress", "parked_at_review"), lines.map { it.split(" | ")[1] })
        assertTrue(lines[0].contains("opened the form via JobBot with Richard-Hatcher-Resume.pdf"))
        assertTrue(lines[1].contains("NOT submitted"))
    }

    @Test
    fun `the Submit tap records completed and the packet is never applied to again`() {
        seedStandard()
        val rig = Rig()
        val f = "applications/apple/tools/v03"
        rig.tools.applyOne(req("folder" to f)).ok()
        assertEquals("done", rig.tap("submit", rig.fillId()).outcome)
        val lines = rig.statusOnRemote(f)
        assertEquals(listOf("in_progress", "parked_at_review", "completed"), lines.map { it.split(" | ")[1] })
        assertTrue(lines.last().contains("Thank you"), lines.last())
        assertTrue(rig.tools.applyOne(req("folder" to f, "approval_quote" to "again please")).fails().contains("already completed"))
        assertEquals(1, rig.jobs.size)
    }

    @Test
    fun `Discard records blocked and the packet then waits for Richard`() {
        seedStandard()
        val rig = Rig()
        val f = "applications/apple/tools/v03"
        rig.tools.applyOne(req("folder" to f)).ok()
        rig.tap("discard", rig.fillId())
        assertEquals("blocked", rig.statusOnRemote(f).last().split(" | ")[1])
        assertTrue(rig.tools.applyOne(req("folder" to f)).fails().contains("needs Richard's approval"))
        rig.tools.applyOne(req("folder" to f, "approval_quote" to "yes, redo apple tools")).ok()
        assertEquals(2, rig.jobs.size)
        assertTrue(rig.statusOnRemote(f).any { it.contains("draft approved: \"yes, redo apple tools\"") })
    }

    @Test
    fun `a hand-off is recorded as blocked with the reason`() {
        seedStandard()
        val rig = Rig()
        rig.outcome = { FillResult.NeedsHuman("CAPTCHA on jobs.example.com", null) }
        rig.tools.applyOne(req("folder" to "applications/apple/tools/v03")).ok()
        assertTrue(rig.statusOnRemote("applications/apple/tools/v03").last().endsWith("| blocked | needs Richard: CAPTCHA on jobs.example.com"))
    }

    @Test
    fun `a failed fill is recorded as blocked`() {
        seedStandard()
        val rig = Rig()
        rig.outcome = { FillResult.Failed("the model call failed", null) }
        rig.tools.applyOne(req("folder" to "applications/apple/tools/v03")).ok()
        assertTrue(rig.statusOnRemote("applications/apple/tools/v03").last().contains("| blocked | JobBot's fill failed: the model call failed"))
    }

    @Test
    fun `the review window expiring is recorded`() {
        seedStandard()
        val rig = Rig()
        rig.tools.applyOne(req("folder" to "applications/apple/tools/v03")).ok()
        val later = Clock.fixed(now.plus(Duration.ofHours(5)), ZoneOffset.UTC)
        val svc = ApplyService(
            browser = { _ -> rig.page }, fill = { p, _, _ -> rig.outcome(p) }, lookup = rig.lookup, contextFor = { null },
            store = rig.store, outbox = rig.outbox, tracks = null, viewerUrl = null, clock = later,
            cargoStatus = CargoStatusListener { folder, state, note -> rig.repo.appendStatus(folder, state, note) },
            worker = MoreExecutors.newDirectExecutorService(),
        )
        svc.sweep()
        assertTrue(rig.statusOnRemote("applications/apple/tools/v03").last().contains("| blocked | review window expired"))
    }

    @Test
    fun `a DRAFT needs approval, and with it the quote lands in STATUS`() {
        seedStandard()
        val rig = Rig()
        val f = "applications/tiktok/qa/v00"
        val refused = rig.tools.applyOne(req("folder" to f)).fails()
        assertTrue(refused.contains("needs Richard's approval") && refused.contains("DRAFT"), refused)
        assertEquals(0, rig.jobs.size)
        rig.tools.applyOne(req("folder" to f, "approval_quote" to "apply to the tiktok draft")).ok()
        assertEquals(1, rig.jobs.size)
        assertTrue(rig.statusOnRemote(f).first().contains("draft approved: \"apply to the tiktok draft\""))
    }

    @Test
    fun `an apply URL that points at a private address or a non-web scheme is never opened`() {
        val bad = listOf(
            "http://jobbot-mcp:8790/health", "http://127.0.0.1/x", "http://169.254.169.254/latest/meta-data", "file:///etc/passwd",
            "http://localhost:8080/", "ftp://jobs.example.com/x", "https://user:pw@jobs.example.com/x", "http://host.docker.internal/x",
        )
        bad.forEachIndexed { i, url -> remote.packet("evil$i", "sdet", url = url) }
        remote.publish()
        val rig = Rig()
        bad.indices.forEach { i ->
            val msg = rig.tools.applyOne(req("folder" to "applications/evil$i/sdet/v00", "approval_quote" to "yes")).fails()
            assertTrue(msg.contains("apply URL"), "${bad[i]}: $msg")
        }
        assertEquals(0, rig.jobs.size)
        assertEquals(0, rig.outbox.pending().size)
        val o = rig.tools.applyReady(req()).ok()
        assertEquals(0, o["started"]!!.jsonArray.size)
        assertEquals(0, rig.tools.list(req()).ok()["ready_to_apply"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the packet's own apply URL is cleaned before it is opened`() {
        remote.packet("acme", "sdet", url = "https://jobs.example.com/acme/1?gh_jid=7#apply"); remote.publish()
        val rig = Rig()
        rig.tools.applyOne(req("folder" to "applications/acme/sdet/v00")).ok()
        assertEquals("https://jobs.example.com/acme/1?gh_jid=7", rig.jobs.single().jobUrl)
    }

    @Test
    fun `a packet with no apply URL can never be applied to`() {
        seedStandard()
        val msg = Rig().tools.applyOne(req("folder" to "applications/capgemini/sdet/v00", "approval_quote" to "yes")).fails()
        assertTrue(msg.contains("no apply URL"), msg)
    }

    @Test
    fun `a résumé that does not match the packet's hash is not uploaded`() {
        remote.packet("acme", "sdet", badHash = true); remote.publish()
        val rig = Rig()
        assertTrue(rig.tools.applyOne(req("folder" to "applications/acme/sdet/v00")).fails().contains("hash"))
        assertEquals(0, rig.jobs.size)
        assertEquals(0, rig.outbox.pending().size)
    }

    @Test
    fun `a missing upload file is reported`() {
        remote.packet("acme", "sdet", writeUpload = false); remote.publish()
        assertTrue(Rig().tools.applyOne(req("folder" to "applications/acme/sdet/v00")).fails().contains("missing"))
    }

    @Test
    fun `with Apply switched off nothing is started`() {
        seedStandard()
        val rig = Rig(applyEnabled = false)
        assertTrue(rig.tools.applyOne(req("folder" to "applications/apple/tools/v03")).fails().contains("isn't switched on"))
        assertEquals(emptyList(), rig.outbox.pending())
    }

    @Test
    fun `a fresh in_progress line is left alone even with approval`() {
        remote.packet("acme", "sdet", statusLines = listOf("- ${now.minusSeconds(300)} | in_progress | another agent is filling it")); remote.publish()
        val msg = Rig().tools.applyOne(req("folder" to "applications/acme/sdet/v00", "approval_quote" to "yes")).fails()
        assertTrue(msg.contains("another agent"), msg)
    }

    @Test
    fun `the same posting is not filled twice`() {
        remote.packet("acme", "sdet", url = "https://jobs.example.com/same")
        remote.packet("acme", "sdet-copy", url = "https://jobs.example.com/same"); remote.publish()
        val rig = Rig()
        rig.tools.applyOne(req("folder" to "applications/acme/sdet/v00")).ok()
        val second = rig.tools.applyOne(req("folder" to "applications/acme/sdet-copy/v00")).fails()
        assertTrue(second.contains("waiting for your review") || second.contains("Already"), second)
        assertEquals(1, rig.jobs.size)
    }

    // ── apply to all the ready packets ───────────────────────────────────────────────────────

    @Test
    fun `cargo_apply_ready starts the READY ones and lists the rest with reasons`() {
        seedStandard()
        val rig = Rig()
        val o = rig.tools.applyReady(req()).ok()
        val started = o["started"]!!.jsonArray.map { it.jsonObject["packet"]!!.jsonPrimitive.content }
        assertEquals(listOf("applications/apple/sdet/v02", "applications/apple/tools/v03"), started)
        val skipped = o["skipped"]!!.jsonArray.associate { it.jsonObject["packet"]!!.jsonPrimitive.content to it.jsonObject["why"]!!.jsonPrimitive.content }
        assertTrue(skipped.getValue("applications/becu/sdet/v01").contains("older version"), skipped.toString())
        assertTrue(skipped.getValue("applications/capgemini/sdet/v00").contains("no apply URL"))
        assertEquals(2, rig.jobs.size)
        assertEquals(setOf("Apple"), rig.jobs.map { it.company }.toSet())
        assertTrue(o["note"]!!.jsonPrimitive.content.contains("Nothing is submitted"))
    }

    @Test
    fun `cargo_apply_ready never touches DRAFTs or completed packets`() {
        seedStandard()
        val rig = Rig()
        rig.tools.applyReady(req()).ok()
        val statuses = remote.freshClone().let { c -> listOf("tiktok/qa/v00", "capgemini/sdet/v00", "flex/sdet/v00").map { Files.exists(c.resolve("applications/$it/STATUS.md")) && Files.readString(c.resolve("applications/$it/STATUS.md")).contains("JobBot") } }
        assertEquals(listOf(false, false, false), statuses)
        assertFalse(rig.jobs.any { it.company in setOf("Tiktok", "Capgemini", "Flex") })
    }

    @Test
    fun `cargo_apply_ready honours its limit`() {
        seedStandard()
        val rig = Rig()
        val o = rig.tools.applyReady(req("max" to 1)).ok()
        assertEquals(1, o["started"]!!.jsonArray.size)
        assertTrue(o["skipped"]!!.jsonArray.any { it.jsonObject["why"]!!.jsonPrimitive.content.contains("limit of 1") })
        assertEquals(1, rig.jobs.size)
    }

    @Test
    fun `running cargo_apply_ready twice does not start anything twice`() {
        seedStandard()
        val rig = Rig()
        rig.tools.applyReady(req()).ok()
        val again = rig.tools.applyReady(req()).ok()
        assertEquals(0, again["started"]!!.jsonArray.size)
        assertEquals(2, rig.jobs.size)
    }

    @Test
    fun `apply_ready with Apply off starts nothing and says so`() {
        seedStandard()
        val rig = Rig(applyEnabled = false)
        val o = rig.tools.applyReady(req()).ok()
        assertEquals(0, o["started"]!!.jsonArray.size)
        assertTrue(o["skipped"]!!.jsonArray.all { it.jsonObject["why"]!!.jsonPrimitive.content.contains("isn't switched on") || it.jsonObject["why"]!!.jsonPrimitive.content.isNotBlank() })
        assertNotNull(o["skipped"])
    }

    @Test
    fun `simultaneous starts for one job queue exactly one fill`() {
        val upload = (com.jd.jobbot.actions.ResumeUpload.validate("r.pdf", com.jd.jobbot.support.ResumeFiles.pdf()) as com.jd.jobbot.actions.ResumeUpload.Check.Ok).upload
        repeat(60) { round ->
            val store = ActionStore(":memory:", clock)
            val outbox = Outbox(tmp.resolve("race$round.db").toString(), tmp.resolve("shots$round"), clock)
            val queued = mutableListOf<Runnable>()
            val service = ApplyService(
                browser = { _ -> FakePage(readyForm) }, fill = { _, _, _ -> FillResult.Failed("n/a", null) }, lookup = JobLookup(BridgeReadClient("http://unused")),
                contextFor = { JobContext("", null, null, "", "dkkytech@gmail.com", "p", null, null, null) },
                store = store, outbox = outbox, tracks = null, viewerUrl = null, clock = clock,
                worker = object : java.util.concurrent.AbstractExecutorService() {
                    override fun execute(command: Runnable) { synchronized(queued) { queued += command } }
                    override fun shutdown() {}
                    override fun shutdownNow() = emptyList<Runnable>()
                    override fun isShutdown() = false
                    override fun isTerminated() = false
                    override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
                },
            )
            val packet = com.jd.jobbot.apply.PacketApply("applications/acme/sdet/v00", "Acme — SDET", "Acme", "SDET", "https://jobs.example.com/acme/1", upload, null)
            val gate = java.util.concurrent.CyclicBarrier(8)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            try {
                // Half the callers come in as packet applications, half as a pasted link to the same posting.
                val results = (1..8).map { n ->
                    pool.submit<com.jd.jobbot.actions.TapResponse> {
                        gate.await()
                        if (n % 2 == 0) service.applyPacket(packet, "mcp")
                        else service.applyPacket(com.jd.jobbot.apply.PacketApply("applications/acme/sdet/v00", "Acme — SDET", "Acme", "SDET", "https://jobs.example.com/acme/1", upload, null), "mcp")
                    }
                }.map { it.get() }
                assertEquals(1, results.count { it.outcome == com.jd.jobbot.actions.Outcomes.DONE }, "round $round: " + results.joinToString { "${it.outcome}:${it.reply?.take(30)}" })
                assertEquals(1, store.recent(50).count { it.verb == "fill" }, "round $round: one fill row")
                assertEquals(1, queued.size, "round $round: one queued job")
            } finally {
                pool.shutdownNow()
                outbox.close()
            }
        }
    }

    @Test
    fun `fills carry the ad hoc seq so they never touch a JFAA track`() {
        seedStandard()
        val rig = Rig()
        rig.tools.applyOne(req("folder" to "applications/apple/tools/v03")).ok()
        assertEquals(AD_HOC_SEQ, rig.store.recent().first { it.verb == "fill" }.seq)
    }

    @Test
    fun `a non-UTF-8 text file is readable, not an error`() {
        remote.packet("acme", "sdet"); remote.publish()
        java.nio.file.Files.write(remote.seed.resolve("applications/acme/sdet/v00/latin1.txt"), "caf\u00e9 notes".toByteArray(Charsets.ISO_8859_1))
        remote.publish("latin1")
        val o = Rig().tools.readFile(req("path" to "applications/acme/sdet/v00/latin1.txt")).ok()
        assertTrue(o["content"]!!.jsonPrimitive.content.contains("notes"))
    }

    @Test
    fun `a file over the Telegram limit is not queued`() {
        seedStandard()
        val rig = Rig()
        rig.tools.list(req())
        Files.write(tmp.resolve("checkout/big.pdf"), ByteArray(21 * 1024 * 1024))
        assertTrue(rig.tools.sendFile(req("path" to "big.pdf")).fails().contains("over the 20 MB"))
        assertEquals(emptyList(), rig.outbox.pending())
    }

    @Test
    fun `list limits and filters that match nothing are harmless`() {
        seedStandard()
        val t = Rig().tools
        assertEquals(0, t.list(req("company" to "zzz")).ok()["matching"]!!.jsonPrimitive.int)
        assertEquals(6, t.list(req("limit" to 0)).ok().let { it["matching"]!!.jsonPrimitive.int })
        assertEquals(1, t.list(req("limit" to 0)).ok()["packets"]!!.jsonArray.size, "a limit below 1 is raised to 1")
        assertEquals(6, t.list(req("limit" to 99999)).ok()["packets"]!!.jsonArray.size)
        assertEquals(0, t.list(req("status" to "SHIPPED")).ok()["matching"]!!.jsonPrimitive.int)
    }

    @Test
    fun `search limits are clamped and results never exceed them`() {
        seedStandard()
        val t = Rig().tools
        assertEquals(1, t.search(req("query" to "sponsorship", "limit" to 0)).ok()["matches"]!!.jsonPrimitive.int)
        assertTrue(t.search(req("query" to "sponsorship", "limit" to 100000)).ok()["matches"]!!.jsonPrimitive.int <= 100)
    }

    @Test
    fun `a packet without STATUS or posting files still opens`() {
        remote.packet("acme", "sdet", posting = null, answers = null); remote.publish()
        val o = Rig().tools.getPacket(req("folder" to "applications/acme/sdet/v00")).ok()
        assertTrue(o["status_md"] is kotlinx.serialization.json.JsonNull)
        assertTrue(o["job_posting_md"] is kotlinx.serialization.json.JsonNull)
        assertTrue(o["application_answers_md"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `a packet applied without answers still fills, from the profile alone`() {
        remote.packet("acme", "sdet", answers = null); remote.publish()
        val rig = Rig()
        rig.tools.applyOne(req("folder" to "applications/acme/sdet/v00")).ok()
        assertEquals(null, rig.jobs.single().answers)
        assertEquals("years: 15+", rig.jobs.single().profileYaml)
    }

    @Test
    fun `sending the docx copy fails clearly when the packet has none`() {
        remote.packet("acme", "sdet"); remote.publish()
        remote.freshClone().let { }
        val rig = Rig()
        rig.tools.list(req())
        Files.delete(tmp.resolve("checkout/applications/acme/sdet/v00/apply-2p/Richard-Hatcher-Resume.docx"))
        assertTrue(rig.tools.sendResume(req("company" to "acme", "format" to "docx")).fails().contains("no .docx"))
    }

    @Test
    fun `unknown companies and empty arguments give a message, not a crash`() {
        seedStandard()
        val t = Rig().tools
        assertTrue(t.getPacket(req()).fails().isNotBlank())
        assertTrue(t.applyOne(req()).fails().isNotBlank())
        assertTrue(t.sendResume(req("company" to " ")).fails().isNotBlank())
        assertTrue(t.readFile(req()).fails().isNotBlank())
        assertTrue(t.appendStatus(req("folder" to "applications/apple/tools/v03")).fails().isNotBlank())
    }

    @Test
    fun `cargo_apply_ready accounts for every packet, started or skipped with a reason or counted as completed`() {
        remote.packet("ready", "sdet")
        remote.packet("done", "sdet", statusLines = listOf("- 2026-10-16T16:05:00Z | completed | confirmed"))
        remote.packet("draftblocked", "sdet", status = "DRAFT", statusLines = listOf("- 2026-10-16T16:05:00Z | blocked | needs a code"))
        remote.packet("draftparked", "sdet", status = "DRAFT", statusLines = listOf("- 2026-10-16T16:05:00Z | parked_at_review | filled"))
        remote.packet("draftprogress", "sdet", status = "DRAFT", statusLines = listOf("- ${now.minusSeconds(60)} | in_progress | opened"))
        remote.packet("readyblocked", "sdet", statusLines = listOf("- 2026-10-16T16:05:00Z | blocked | needs a code"))
        remote.packet("draftnew", "sdet", status = "DRAFT")
        remote.publish()
        val rig = Rig()
        val o = rig.tools.applyReady(req()).ok()
        val started = o["started"]!!.jsonArray.map { it.jsonObject["packet"]!!.jsonPrimitive.content }
        val skipped = o["skipped"]!!.jsonArray.associate { it.jsonObject["packet"]!!.jsonPrimitive.content to it.jsonObject["why"]!!.jsonPrimitive.content }
        assertEquals(listOf("applications/ready/sdet/v00"), started)
        assertEquals(1, o["already_completed"]!!.jsonPrimitive.int)
        assertEquals(
            setOf("draftblocked", "draftparked", "draftprogress", "readyblocked", "draftnew").map { "applications/$it/sdet/v00" }.toSet(),
            skipped.keys, "no packet is silently dropped",
        )
        assertTrue(skipped.getValue("applications/draftblocked/sdet/v00").contains("blocked"), skipped.toString())
        assertTrue(skipped.getValue("applications/draftparked/sdet/v00").contains("parked_at_review"))
        assertTrue(skipped.getValue("applications/draftprogress/sdet/v00").contains("another agent"))
        assertTrue(skipped.getValue("applications/draftnew/sdet/v00").contains("DRAFT"))
        assertEquals(7, started.size + skipped.size + o["already_completed"]!!.jsonPrimitive.int, "every packet is accounted for")
    }

    // ── JOBBOT_DRY_RUN ───────────────────────────────────────────────────────────────────────

    private fun remoteCommits() = CargoRemote.git(remote.freshClone(), "rev-list", "--count", "HEAD").toInt()

    @Test
    fun `dry run checks a packet and says what it would do, and does nothing`() {
        seedStandard()
        val rig = Rig(dryRun = true)
        val before = remoteCommits()
        val o = rig.tools.applyOne(req("folder" to "applications/apple/tools/v03")).ok()
        assertEquals("true", o["dry_run"]!!.jsonPrimitive.content)
        assertEquals("[dry run] Would apply to Apple — Tools Engineer with Richard-Hatcher-Resume.pdf.", o["result"]!!.jsonPrimitive.content)
        assertEquals(0, rig.jobs.size, "no fill ran")
        assertEquals(emptyList(), rig.page.actions, "no browser was touched")
        assertEquals(emptyList(), rig.outbox.pending(), "no review card was posted")
        assertEquals(before, remoteCommits(), "nothing was pushed to the cargo repo")
        assertFalse(Files.exists(remote.freshClone().resolve("applications/apple/tools/v03/STATUS.md")), "STATUS.md was not created")
        val row = rig.store.recent(5).single()
        assertEquals("dry_run", row.status)
        assertEquals("apply", row.verb)
        assertEquals(AD_HOC_SEQ, row.seq)
        assertTrue(row.details!!.contains("applications/apple/tools/v03"))
    }

    @Test
    fun `dry run still enforces every rule a real run does`() {
        seedStandard()
        remote.packet("badhash", "sdet", badHash = true)
        remote.packet("badurl", "sdet", url = "http://127.1/x")
        remote.publish()
        val t = Rig(dryRun = true).tools
        assertTrue(t.applyOne(req("folder" to "applications/tiktok/qa/v00")).fails().contains("needs Richard's approval"))
        assertTrue(t.applyOne(req("folder" to "applications/flex/sdet/v00", "approval_quote" to "yes")).fails().contains("already completed"))
        assertTrue(t.applyOne(req("folder" to "applications/capgemini/sdet/v00", "approval_quote" to "yes")).fails().contains("no apply URL"))
        assertTrue(t.applyOne(req("folder" to "applications/becu/sdet/v01")).fails().contains("older version"))
        assertTrue(t.applyOne(req("folder" to "applications/badhash/sdet/v00")).fails().contains("hash"))
        assertTrue(t.applyOne(req("folder" to "applications/badurl/sdet/v00")).fails().contains("apply URL"))
        assertTrue(t.applyOne(req("folder" to "applications/nope/x/v00")).fails().contains("No current packet"))
    }

    @Test
    fun `dry run with Richard's approval passes a DRAFT, and still writes nothing`() {
        seedStandard()
        val rig = Rig(dryRun = true)
        val before = remoteCommits()
        rig.tools.applyOne(req("folder" to "applications/tiktok/qa/v00", "approval_quote" to "apply to the tiktok draft")).ok()
        assertEquals(before, remoteCommits())
        assertEquals(0, rig.jobs.size)
    }

    @Test
    fun `dry run of apply-ready lists what would start under its own name, and starts nothing`() {
        seedStandard()
        val rig = Rig(dryRun = true)
        val before = remoteCommits()
        val o = rig.tools.applyReady(req()).ok()
        assertEquals("true", o["dry_run"]!!.jsonPrimitive.content)
        assertTrue("started" !in o, "a dry run never claims to have started anything")
        assertEquals(listOf("applications/apple/sdet/v02", "applications/apple/tools/v03"), o["would_start"]!!.jsonArray.map { it.jsonObject["packet"]!!.jsonPrimitive.content })
        assertTrue(o["would_start"]!!.jsonArray.all { it.jsonObject["result"]!!.jsonPrimitive.content.startsWith("[dry run] Would apply") })
        assertTrue(o["note"]!!.jsonPrimitive.content.contains("nothing was started"))
        // The same packets are skipped for the same reasons as in a live run.
        val skipped = o["skipped"]!!.jsonArray.map { it.jsonObject["packet"]!!.jsonPrimitive.content }.toSet()
        assertTrue("applications/becu/sdet/v01" in skipped && "applications/tiktok/qa/v00" in skipped && "applications/capgemini/sdet/v00" in skipped)
        assertEquals(0, rig.jobs.size)
        assertEquals(emptyList(), rig.outbox.pending())
        assertEquals(before, remoteCommits())
        assertEquals(2, rig.store.recent(10).count { it.status == "dry_run" })
    }

    @Test
    fun `dry run of apply-ready honours its limit like a live run`() {
        seedStandard()
        val o = Rig(dryRun = true).tools.applyReady(req("max" to 1)).ok()
        assertEquals(1, o["would_start"]!!.jsonArray.size)
        assertTrue(o["skipped"]!!.jsonArray.any { it.jsonObject["why"]!!.jsonPrimitive.content.contains("limit of 1") })
    }

    @Test
    fun `dry run writes no status line, but checks the same rules`() {
        seedStandard()
        val rig = Rig(dryRun = true)
        val before = remoteCommits()
        val o = rig.tools.appendStatus(req("folder" to "applications/apple/tools/v03", "state" to "blocked", "note" to "needs a code")).ok()
        assertEquals("true", o["dry_run"]!!.jsonPrimitive.content)
        assertTrue(o["would_append"]!!.jsonPrimitive.content.endsWith("| blocked | needs a code"), o.toString())
        assertEquals(before, remoteCommits())
        assertTrue(rig.tools.appendStatus(req("folder" to "applications/becu/sdet/v00", "state" to "completed", "note" to "x")).fails().contains("newest version"))
        assertTrue(rig.tools.appendStatus(req("folder" to "applications/apple/tools/v03", "state" to "completed", "note" to "x")).fails().contains("Richard's own words"))
        assertTrue(rig.tools.appendStatus(req("folder" to "applications/flex/sdet/v00", "state" to "blocked", "note" to "x")).fails().contains("already completed"))
    }

    @Test
    fun `dry run does not stop reading or sending files, which change nothing for anyone else`() {
        seedStandard()
        val rig = Rig(dryRun = true)
        assertEquals(6, rig.tools.list(req()).ok()["total_roles"]!!.jsonPrimitive.int)
        rig.tools.sendResume(req("company" to "flex")).ok()
        assertEquals(1, rig.outbox.pending().size)
    }

    @Test
    fun `dry run with Apply off gives the same answer as live`() {
        seedStandard()
        assertTrue(Rig(applyEnabled = false, dryRun = true).tools.applyOne(req("folder" to "applications/apple/tools/v03")).fails().contains("isn't switched on"))
    }

    @Test
    fun `a dry run does not block the real application that follows`() {
        seedStandard()
        val dry = Rig(dryRun = true)
        dry.tools.applyOne(req("folder" to "applications/apple/tools/v03")).ok()
        dry.tools.applyOne(req("folder" to "applications/apple/tools/v03")).ok()
        // Same store, dry run off: the real application starts normally.
        val live = CargoTools(dry.repo, dry.outbox, dry.service, clock, dryRun = false)
        live.applyOne(req("folder" to "applications/apple/tools/v03")).ok()
        assertEquals(1, dry.jobs.size)
        assertEquals(listOf("in_progress", "parked_at_review"), dry.statusOnRemote("applications/apple/tools/v03").map { it.split(" | ")[1] })
    }
}
