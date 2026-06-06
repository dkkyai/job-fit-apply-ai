package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.CliOutput
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.utils.NodeTimer
import java.time.Instant

object TestCommandHandler {
    fun run() {
        CliOutput.printModels()
        println("[INFO] Running test with sample JD...")

        val sampleJd = """
            Subject: Staff SDET Opportunity – Mobile Platform @ Acme Corp

            Hi [Candidate],

            We have a Staff SDET opening on our Mobile Platform team at Acme Corp (Seattle, hybrid 2 days/wk).

            Responsibilities:
            - Own iOS and Android BVT automation frameworks
            - Build and maintain CI/CD pipelines on Bitrise and GitHub Actions
            - Drive test infrastructure strategy across mobile teams

            Requirements:
            - 6+ years in mobile test automation
            - Deep experience with XCUITest and Espresso
            - CI/CD pipeline ownership (Bitrise preferred)
            - Kotlin or Swift fluency
            - Experience with Terraform or IaC a plus

            TC: $220K–$280K

            Let me know if you'd like to connect.

            Best,
            Sarah
        """.trimIndent()

        val input = JDState(
            intake = IntakeContext.Email(
                emailId = "test-001",
                subject = "Staff SDET Opportunity – Mobile Platform @ Acme Corp",
                from = "sarah@acmecorp.com",
                rawBody = sampleJd,
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false,
            ),
        )

        NodeTimer.reset()
        val batchStartTime = Instant.now()

        val ingestion = IngestionPipeline()
        val processing = ProcessingPipeline()

        val ingested = ingestion.invoke(input)

        if (!ingested.isJobPosting) {
            println("  ↳ Not a job posting — skipped")
            return
        }

        val record = ingestion.toJdRecord(ingested)
        val result = processing.invoke(record)

        println("  → action: ${result.pipelineAction}, score: ${result.fitScore}")
        result.outputPath?.let { println("  → output: $it") }
        result.error?.let { println("  → error: $it") }

        val tailored  = if (result.pipelineAction == "TAILOR" && !result.isDuplicate) 1 else 0
        val duplicate = if (result.isDuplicate) 1 else 0
        val skipped   = if (!result.isDuplicate && result.pipelineAction != "TAILOR") 1 else 0
        CliOutput.printBatchSummary(1, 1, tailored, skipped, duplicate, batchStartTime, emptyList())
        CliOutput.printScrapeBatchWarnings(ingestion)
    }
}
