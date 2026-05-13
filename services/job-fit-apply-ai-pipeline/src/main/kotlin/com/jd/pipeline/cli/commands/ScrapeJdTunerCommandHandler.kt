package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.tuning.ScrapeJdTuner
import java.nio.file.Files
import java.nio.file.Path

object ScrapeJdTunerCommandHandler {
    fun run(cmd: Command.ScrapeJdTuner) {
        val tunerFile = cmd.file
        if (tunerFile.isNullOrBlank()) {
            println("[ERROR] --scrapetuner requires a dataset file path")
            return
        }

        val path = Path.of(tunerFile)
        if (!Files.exists(path)) {
            println("[ERROR] scrape tuner dataset file not found: $path")
            return
        }

        println("[INFO] Running ScrapeJdTuner for: $path")
        println("[INFO] Max iterations: ${cmd.maxIterations}")

        try {
            val tuner = ScrapeJdTuner()
            val result = tuner.invoke(tunerFile, cmd.maxIterations)
            println("[INFO] ScrapeJdTuner output: ${result.scrapeJdTuningOutputDir}")
            if (result.error.isNotBlank()) {
                println("[WARN] error: ${result.error}")
            }
            if (result.isChromeSessionExpired) {
                println("[WARN] LinkedIn session expired — re-authenticate Chrome profile")
            }
            println("  company: ${result.company.ifBlank { "(empty)" }}")
            println("  role_title: ${result.roleTitle.ifBlank { "(empty)" }}")
            println("  location: ${result.location.ifBlank { "(empty)" }}")
            println("  salary_range: ${result.salaryRange.ifBlank { "(empty)" }}")
            println("  jd_text: ${result.jdText.length} chars")
            if (result.scrapeJdComparisonReport.isNotBlank()) {
                println("    → comparison_report: ${Path.of(result.scrapeJdTuningOutputDir).resolve("scrape_comparison_report.md")}")
            }
        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }
}
