package com.jd.jobbot.apply

import com.jd.jobbot.llm.Llm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Clock

/**
 * Test-only scripted planner: what Hermes does with the fill_* tools, played by a scripted [Llm]. It reads the
 * session's JSON exactly as the tools return it and calls the same four operations, so a scenario written against it
 * exercises the real guards in [FillSession] and [FillExecutor].
 */
class FillAgent(
    private val llm: Llm,
    credentials: Credentials,
    verifier: Verifier?,
    private val maxSteps: Int = 30,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val executor = FillExecutor(credentials, verifier)

    fun run(page: ApplyPage, job: JobContext, startFresh: Boolean = true): FillResult {
        if (startFresh) page.goto(job.jobUrl)
        val session = FillSession("scripted", page, job, executor, maxSteps, clock)
        val context = Json.parseToJsonElement(session.context())
        val history = mutableListOf<String>()
        repeat(maxSteps) { step ->
            val snapshot = Json.parseToJsonElement(session.snapshot()).jsonObject
            session.outcome?.let { return it }
            val prompt = buildJsonObject {
                put("context", context)
                put("page", snapshot["page"]!!)
                snapshot["notes"]?.let { put("notes", it) }
                put("previous_steps", buildJsonArray { history.takeLast(20).forEach { add(JsonPrimitive(it)) } })
            }.toString()
            val plan = try {
                llm.json(FillInstructions.SYSTEM, prompt)
            } catch (e: Exception) {
                return FillResult.Failed("The model call failed: ${e.message}", null)
            }
            val note = plan.text("note").orEmpty()
            when (plan.text("status")) {
                "ready", "need_human" -> { session.finish(plan.text("status")!!, note); return session.outcome!! }
            }
            val actions = plan["actions"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            if (actions.isEmpty()) {
                history += "step $step: no actions returned; return status ready, need_human, or actions"
                return@repeat
            }
            val reply = Json.parseToJsonElement(session.act(actions)).jsonObject
            session.outcome?.let { return it }
            reply["results"]?.jsonArray?.forEach { r ->
                val o = r.jsonObject
                history += "${o.text("do")} ${o.text("target").orEmpty()}: ${o.text("result")}"
            }
            reply["error"]?.let { history += "error: ${(it as JsonPrimitive).content}" }
        }
        return session.outcome ?: FillResult.NeedsHuman("I ran out of steps before the form was ready. It's open in the viewer.", null)
    }
}
