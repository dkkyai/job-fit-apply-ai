package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.CliOutput
import com.jd.pipeline.client.JSearchClient
import com.jd.pipeline.config.JSearchConfig
import com.jd.pipeline.models.JobListing
import com.jd.pipeline.pipeline.JDPipeline
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.utils.NodeTimer
import java.time.Instant

object JSearchCommandHandler {
    fun run() {
        println("[INFO] Fetching jobs via JSearch API...")

        val client = JSearchClient()
        val results: List<JobListing> = client.search(JSearchConfig.DEFAULT_LIST)

        val remoteCount = results.count { it.jobIsRemote }
        val localCount = results.size - remoteCount
        val employerCount = results.map { it.employerName }.toSet().size

        println("[INFO] Fetched ${results.size} unique job(s) — $remoteCount remote, $localCount local, $employerCount employer(s)")
        println()

        if (results.isEmpty()) {
            println("[WARN] No jobs returned from JSearch.")
            return
        }

        NodeTimer.reset()
        val batchStartTime = Instant.now()
        val pipeline = JDPipeline()
        var processed = 0
        var tailored = 0
        var skipped = 0
        var duplicate = 0
        val scoredJobs = mutableListOf<JDState>()

        for (listing in results) {
            processed++
            val state = JSearchClient.toJDState(listing)
            println("[Processing $processed/${results.size}] ${state.roleTitle} @ ${state.company} — ${state.location}")

            val result = pipeline.invoke(state)
            scoredJobs.add(result)

            when {
                result.isDuplicate -> duplicate++
                result.pipelineAction == PipelineAction.TAILOR -> tailored++
                else -> skipped++
            }

            CliOutput.printResult(result)
        }

        CliOutput.printBatchSummary(processed, processed, tailored, skipped, duplicate, batchStartTime, scoredJobs)
    }
}
