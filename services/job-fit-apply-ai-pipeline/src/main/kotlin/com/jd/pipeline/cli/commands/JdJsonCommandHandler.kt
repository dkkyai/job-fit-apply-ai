package com.jd.pipeline.cli.commands

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.cli.CliOutput
import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.JdJsonInput
import com.jd.pipeline.pipeline.JDPipeline
import com.jd.pipeline.source.IntakeContext

object JdJsonCommandHandler {
    fun run(cmd: Command.JdJson) {
        println("[INFO] Processing JD JSON payload...")

        val inputState = try {
            val mapper = ObjectMapper().registerKotlinModule()
            val input = mapper.readValue(cmd.json, JdJsonInput::class.java)
            // Bypass scan — treat as a pre-scanned job posting
            input.toState().copy(
                isJobPosting = true,
                intake = IntakeContext.Synthetic(label = "jd-json-${System.currentTimeMillis()}")
            )
        } catch (e: Exception) {
            println("[ERROR] Failed to parse --jd-json: ${e.message}")
            CliOutput.printJsonSummary(mapOf(
                "error" to "JSON parse error: ${e.message}",
                "pipeline_action" to "error"
            ))
            return
        }

        try {
            val pipeline = JDPipeline()
            val result = pipeline.invoke(inputState)
            CliOutput.printJsonSummary(result)
        } catch (e: Exception) {
            println("[ERROR] Pipeline failed: ${e.message}")
            CliOutput.printJsonSummary(mapOf(
                "error" to "Pipeline error: ${e.message}",
                "pipeline_action" to "error"
            ))
        }
    }
}
