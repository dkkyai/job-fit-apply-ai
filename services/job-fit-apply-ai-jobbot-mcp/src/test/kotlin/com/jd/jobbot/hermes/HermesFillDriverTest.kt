package com.jd.jobbot.hermes

import com.jd.jobbot.apply.Credentials
import com.jd.jobbot.apply.Element
import com.jd.jobbot.apply.FakePage
import com.jd.jobbot.apply.FillExecutor
import com.jd.jobbot.apply.FillInstructions
import com.jd.jobbot.apply.FillResult
import com.jd.jobbot.apply.FillSessions
import com.jd.jobbot.apply.JobContext
import com.jd.jobbot.apply.Snapshot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The driver that replaced the model loop: it opens a session, asks Hermes to plan the fill, and waits. Hermes is
 * played by a script that does what a planner does, through the same FillSessions registry the MCP tools use.
 */
class HermesFillDriverTest {
    @TempDir lateinit var dir: Path

    private val job = JobContext("#J9", "Acme", "Staff SDET", "https://boards.greenhouse.io/acme/jobs/9", "dkkytech@gmail.com", "years: 15+", null, null, null)
    private val form = Snapshot("https://boards.greenhouse.io/acme/jobs/9", elements = listOf(
        Element("e1", "input", "text", "First name", inForm = true),
        Element("e3", "button", "submit", "", text = "Submit application", inForm = true, formFilled = true),
    ))

    /** A Hermes whose run is whatever [agent] does with the session it was handed. */
    private inner class FakeHermes(
        val sessions: FillSessions,
        var agent: (FillSessionHandle) -> Unit = {},
        var startFails: HermesRuns.HermesException? = null,
        /** The failure only applies to a start that names a model (Hermes not knowing the name). */
        var startFailsOnlyWithModel: Boolean = false,
        var statusAfterAgent: HermesRuns.RunStatus = HermesRuns.RunStatus("completed", "ok", null),
        var statusFlaky: Int = 0,
    ) : HermesRuns("http://unused", "k") {
        val started = CopyOnWriteArrayList<Map<String, String?>>()
        val stopped = CopyOnWriteArrayList<String>()
        @Volatile private var agentDone = false
        private var statusCalls = 0

        override fun start(instructions: String, input: String, sessionId: String, model: String?): String {
            startFails?.let { if (!startFailsOnlyWithModel || model != null) throw it }
            started += mapOf("instructions" to instructions, "input" to input, "session_id" to sessionId, "model" to model)
            val token = Regex("session token is ([0-9a-f]{32})").find(input)!!.groupValues[1]
            Thread { try { agent(FillSessionHandle(sessions, token)) } finally { agentDone = true } }.apply { isDaemon = true }.start()
            return "run_${started.size}"
        }

        override fun status(runId: String): RunStatus {
            if (statusCalls++ < statusFlaky) error("status endpoint hiccup")
            return if (agentDone) statusAfterAgent else RunStatus("running", null, null)
        }

        override fun stop(runId: String) { stopped += runId }
    }

    /** What a planner holds: the session, reached only through the registry and its token. */
    private class FillSessionHandle(val sessions: FillSessions, val token: String) {
        val session get() = sessions.get(token)!!
        fun act(vararg kv: Pair<String, String>) = session.act(listOf(buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }))
    }

    private fun driver(h: FakeHermes, model: String? = "deepseek-v4.1-flash:cloud", maxWait: Duration = Duration.ofSeconds(10), maxActCalls: Int = 30) =
        HermesFillDriver(h, h.sessions, FillExecutor(Credentials(dir.resolve("c.json"), "dkkytech@gmail.com"), null), model, maxWait, maxActCalls, pollMillis = 20)

    @Test
    fun `a planner that fills the form and finishes ready gives a ready fill`() {
        val sessions = FillSessions(); val page = FakePage(form)
        val hermes = FakeHermes(sessions, agent = { a ->
            a.session.context(); a.session.snapshot()
            a.act("do" to "fill", "id" to "e1", "value" to "Richard")
            a.session.finish("ready", "all fields filled")
        })
        val result = driver(hermes).run(page, job)
        val ready = assertIs<FillResult.Ready>(result)
        assertEquals(listOf("First name" to "Richard"), ready.fields)
        assertEquals("all fields filled", ready.note)
        assertEquals(listOf("goto https://boards.greenhouse.io/acme/jobs/9", "fill e1"), page.actions)
        assertFalse("click e3" in page.actions)
    }

    @Test
    fun `the run is started with the rules, the task, a session of its own and the fill model`() {
        val sessions = FillSessions()
        val hermes = FakeHermes(sessions, agent = { it.session.finish("need_human", "x") })
        driver(hermes).run(FakePage(form), job)
        val run = hermes.started.single()
        assertEquals(FillInstructions.SYSTEM, run["instructions"])
        assertContains(run["input"]!!, "Acme — Staff SDET")
        assertContains(run["input"]!!, "Begin with fill_context")
        assertTrue(run["session_id"]!!.startsWith("fill-") && run["session_id"]!!.length == 5 + 32)
        assertEquals("deepseek-v4.1-flash:cloud", run["model"])
    }

    @Test
    fun `a continued fill says so and does not reload the posting`() {
        val sessions = FillSessions(); val page = FakePage(form)
        val hermes = FakeHermes(sessions, agent = { it.session.finish("need_human", "x") })
        driver(hermes).run(page, job, startFresh = false)
        assertContains(hermes.started.single()["input"]!!, "Richard has just finished a step by hand")
        assertEquals(emptyList(), page.actions, "the tab stays where Richard left it")
    }

    @Test
    fun `each run gets its own token, and the token dies with the fill`() {
        val sessions = FillSessions()
        val hermes = FakeHermes(sessions, agent = { it.session.finish("need_human", "x") })
        val d = driver(hermes)
        d.run(FakePage(form), job); d.run(FakePage(form), job)
        val tokens = hermes.started.map { Regex("[0-9a-f]{32}").find(it["input"]!!)!!.value }
        assertEquals(2, tokens.toSet().size)
        assertEquals(0, sessions.active(), "no session outlives its fill")
        assertTrue(tokens.all { sessions.get(it) == null })
    }

    @Test
    fun `a planner that needs a person gives a hand-off with its reason`() {
        val hermes = FakeHermes(FillSessions(), agent = { it.session.finish("need_human", "Which visa status?") })
        val r = assertIs<FillResult.NeedsHuman>(driver(hermes).run(FakePage(form), job))
        assertEquals("Which visa status?", r.reason)
    }

    @Test
    fun `the instant the fill ends, the run is stopped`() {
        val hermes = FakeHermes(FillSessions(), agent = { it.session.finish("ready", "x"); Thread.sleep(500) })
        driver(hermes).run(FakePage(form), job)
        assertEquals(listOf("run_1"), hermes.stopped)
    }

    @Test
    fun `a run that ends without finishing is a hand-off, not a hang`() {
        val hermes = FakeHermes(FillSessions(), agent = { it.session.snapshot() })
        val r = assertIs<FillResult.NeedsHuman>(driver(hermes).run(FakePage(form), job))
        assertContains(r.reason, "stopped before the form was ready")
    }

    @Test
    fun `a run that fails is a failed fill with Hermes' reason`() {
        val hermes = FakeHermes(FillSessions(), agent = { }, statusAfterAgent = HermesRuns.RunStatus("failed", null, "model provider unavailable"))
        val r = assertIs<FillResult.Failed>(driver(hermes).run(FakePage(form), job))
        assertContains(r.reason, "failed"); assertContains(r.reason, "model provider unavailable")
    }

    @Test
    fun `a cancelled or interrupted run is a failed fill too`() {
        for (state in listOf("cancelled", "interrupted")) {
            val hermes = FakeHermes(FillSessions(), agent = { }, statusAfterAgent = HermesRuns.RunStatus(state, null, null))
            val r = assertIs<FillResult.Failed>(driver(hermes).run(FakePage(form), job))
            assertContains(r.reason, state); assertContains(r.reason, "no reason given")
        }
    }

    @Test
    fun `the planner's last tool call winning the race against the run ending still counts`() {
        val sessions = FillSessions()
        val hermes = FakeHermes(sessions, agent = { it.session.finish("ready", "just made it") }, statusAfterAgent = HermesRuns.RunStatus("completed", "done", null))
        assertIs<FillResult.Ready>(driver(hermes).run(FakePage(form), job))
    }

    @Test
    fun `Hermes being unreachable is a failed fill with a plain message`() {
        val hermes = FakeHermes(FillSessions(), startFails = HermesRuns.HermesException(500, "Hermes answered 500"))
        val r = assertIs<FillResult.Failed>(driver(hermes).run(FakePage(form), job))
        assertContains(r.reason, "Couldn't run the assistant")
        assertEquals(0, hermes.sessions.active())
    }

    @Test
    fun `a wrong key is reported, not retried`() {
        val hermes = FakeHermes(FillSessions(), startFails = HermesRuns.HermesException(401, "Invalid gateway API key"))
        val r = assertIs<FillResult.Failed>(driver(hermes, model = null).run(FakePage(form), job))
        assertContains(r.reason, "Invalid gateway API key")
        assertEquals(emptyList(), hermes.started)
    }

    @Test
    fun `if Hermes refuses the model name the run starts on Hermes' own model`() {
        val hermes = FakeHermes(FillSessions(), agent = { it.session.finish("need_human", "x") }, startFails = HermesRuns.HermesException(400, "unknown model"), startFailsOnlyWithModel = true)
        driver(hermes, model = "no-such-model").run(FakePage(form), job)
        assertEquals(1, hermes.started.size)
        assertEquals(null, hermes.started.single()["model"], "the retry went without a model")
    }

    @Test
    fun `a flaky status endpoint does not end the fill`() {
        val hermes = FakeHermes(FillSessions(), statusFlaky = 3, agent = { Thread.sleep(150); it.session.finish("ready", "x") })
        assertIs<FillResult.Ready>(driver(hermes).run(FakePage(form), job))
    }

    @Test
    fun `a planner that never finishes is stopped at the limit and the form is handed over`() {
        val hermes = FakeHermes(FillSessions(), agent = { Thread.sleep(5_000) })
        val r = assertIs<FillResult.NeedsHuman>(driver(hermes, maxWait = Duration.ofMillis(200)).run(FakePage(form), job))
        assertContains(r.reason, "took longer than")
        assertEquals(listOf("run_1"), hermes.stopped)
        assertEquals(0, hermes.sessions.active())
    }

    @Test
    fun `a planner that keeps acting past its budget is handed to Richard`() {
        val hermes = FakeHermes(FillSessions(), agent = { a ->
            a.session.snapshot()
            repeat(10) { a.act("do" to "fill", "id" to "e1", "value" to "x") }
        })
        val r = assertIs<FillResult.NeedsHuman>(driver(hermes, maxActCalls = 3).run(FakePage(form), job))
        assertContains(r.reason, "ran out of steps")
    }

    @Test
    fun `the planner cannot reach the tab once the fill is over`() {
        val sessions = FillSessions(); val page = FakePage(form)
        var late: String? = null
        val hermes = FakeHermes(sessions, agent = { a ->
            a.session.finish("ready", "x")
            Thread.sleep(200)
            late = sessions.get(a.token)?.act(emptyList())
        })
        driver(hermes).run(page, job)
        Thread.sleep(400)
        assertEquals(null, late, "the token no longer opens anything")
    }

    @Test
    fun `a captcha seen on the first look hands off before any action`() {
        val hermes = FakeHermes(FillSessions(), agent = { a -> a.session.snapshot(); a.act("do" to "fill", "id" to "e1", "value" to "x") })
        val page = FakePage(form.copy(captcha = true))
        val r = assertIs<FillResult.NeedsHuman>(driver(hermes).run(page, job))
        assertContains(r.reason, "CAPTCHA")
        assertNotEquals(true, page.actions.any { it.startsWith("fill") })
    }
}
