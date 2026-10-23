package com.jd.jobbot.http

import com.jd.jobbot.actions.InlineButton
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.ResumeApplyRequest
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The plugin's wire with jobbot-mcp, pinned by docker/jobbot/contract/plugin_wire.json, which the plugin's own
 * tests read too. Requests are decoded strictly (an unknown key from the Python side is a failure), and responses
 * are encoded exactly as the server encodes them and compared with what the plugin was tested against.
 */
class PluginWireContractTest {
    @TempDir lateinit var dir: Path

    private val strict = Json { ignoreUnknownKeys = false }
    /** The server's own settings (Server.kt). */
    private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private fun fixture(): JsonObject {
        var d: Path? = Paths.get("").toAbsolutePath()
        while (d != null) {
            d.resolve("docker/jobbot/contract/plugin_wire.json").takeIf { Files.exists(it) }?.let { return Json.parseToJsonElement(Files.readString(it)).jsonObject }
            d = d.parent
        }
        error("plugin_wire.json not found")
    }

    private fun JsonElement?.str() = this?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonElement?.num() = this?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull

    @TestFactory
    fun `the plugin's tap requests decode strictly`(): List<DynamicTest> = fixture()["tap_requests"]!!.jsonArray.map { c ->
        val case = c.jsonObject
        DynamicTest.dynamicTest(case["name"]!!.jsonPrimitive.content) {
            val req = strict.decodeFromJsonElement(TapRequest.serializer(), case["payload"]!!)
            val e = case["expect"]!!.jsonObject
            assertEquals(e["verb"].str(), req.verb)
            assertEquals(e["seq"].num(), req.seq)
            assertEquals(e["userId"].str(), req.userId)
            assertEquals(e["chatId"].str(), req.chatId)
            assertEquals(e["messageId"].num(), req.messageId)
            assertEquals(e["messageDate"].num(), req.messageDate)
        }
    }

    @TestFactory
    fun `the plugin's resume requests decode strictly`(): List<DynamicTest> = fixture()["resume_apply_requests"]!!.jsonArray.map { c ->
        val case = c.jsonObject
        DynamicTest.dynamicTest(case["name"]!!.jsonPrimitive.content) {
            val req = strict.decodeFromJsonElement(ResumeApplyRequest.serializer(), case["payload"]!!)
            val e = case["expect"]!!.jsonObject
            assertEquals(e["seq"].num(), req.seq)
            assertEquals(e["jobUrl"].str(), req.jobUrl)
            assertEquals(e["userId"].str(), req.userId)
            assertEquals(e["chatId"].str(), req.chatId)
            assertEquals(e["messageId"].num(), req.messageId)
            assertEquals(e["messageDate"].num(), req.messageDate)
            assertEquals(e["fileName"].str(), req.fileName)
            assertEquals(e["fileData"].str(), req.fileData)
        }
    }

    @TestFactory
    fun `responses the plugin acts on are exactly what the server sends`(): List<DynamicTest> = fixture()["tap_responses"]!!.jsonArray.map { c ->
        val case = c.jsonObject
        DynamicTest.dynamicTest(case["name"]!!.jsonPrimitive.content) {
            val response = case["response"]!!
            val decoded = strict.decodeFromJsonElement(TapResponse.serializer(), response)
            assertEquals(response, wire.encodeToJsonElement(TapResponse.serializer(), decoded), "the server would send something else")
        }
    }

    @TestFactory
    fun `outbox items the plugin posts are exactly what the outbox serves`(): List<DynamicTest> = fixture()["outbox_items"]!!.jsonArray.map { c ->
        val case = c.jsonObject
        DynamicTest.dynamicTest(case["name"]!!.jsonPrimitive.content) {
            val item = case["item"]!!
            val decoded = strict.decodeFromJsonElement(Outbox.Item.serializer(), item)
            assertEquals(item, wire.encodeToJsonElement(Outbox.Item.serializer(), decoded))
        }
    }

    @Test
    fun `a real outbox emits the same keys the fixture documents for each kind of item`() {
        val byKind = fixture()["outbox_items"]!!.jsonArray.associate { it.jsonObject["sends"]!!.jsonPrimitive.content to it.jsonObject["item"]!!.jsonObject.keys }
        Outbox(dir.resolve("o.db").toString(), dir.resolve("shots")).use { o ->
            o.post("Reminder: the application is waiting.")
            o.post("Ready to submit Acme — SDET", listOf(InlineButton("✅ Submit", "submit:2"), InlineButton("✖ Discard", "discard:2")), byteArrayOf(1))
            o.postDocument("Résumé for Acme — SDET", "Richard-Hatcher-Resume.pdf", byteArrayOf(1))
            val emitted = wire.encodeToJsonElement(ListSerializer(Outbox.Item.serializer()), o.pending()) as JsonArray
            assertEquals(byKind.getValue("message"), emitted[0].jsonObject.keys)
            assertEquals(byKind.getValue("photo"), emitted[1].jsonObject.keys)
            assertEquals(byKind.getValue("document"), emitted[2].jsonObject.keys)
        }
    }
}
