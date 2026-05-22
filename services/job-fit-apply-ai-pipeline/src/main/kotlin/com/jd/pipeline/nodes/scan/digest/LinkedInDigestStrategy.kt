package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState

object LinkedInDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val emailBody = email.rawBody
        val blocks = emailBody.split(Regex("-{20,}"))
        val jobs = mutableListOf<JDState>()
        for (block in blocks) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            val viewJobLine = lines.firstOrNull { it.startsWith("View job:", ignoreCase = true) } ?: continue
            val detailLines = lines.takeWhile { !it.startsWith("View job:", ignoreCase = true) }
                .filterNot {
                    it.equals("Apply with resume & profile", ignoreCase = true) ||
                    it.startsWith("Your job alert for ", ignoreCase = true) ||
                    it.equals("New jobs match your preferences.", ignoreCase = true) ||
                    it.equals("This company is actively hiring", ignoreCase = true) ||
                    it.contains("connections", ignoreCase = true) ||
                    it.contains("company alum", ignoreCase = true)
                }
            if (detailLines.size < 3) continue
            val jobLines = detailLines.takeLast(3)
            jobs.add(createParsedDigestJob(parent, jobLines[1], jobLines[0], jobLines[2], "", viewJobLine.substringAfter("View job:").trim()))
        }
        return jobs
    }
}
