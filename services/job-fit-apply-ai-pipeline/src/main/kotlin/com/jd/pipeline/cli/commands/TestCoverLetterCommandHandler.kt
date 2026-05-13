package com.jd.pipeline.cli.commands

import com.jd.pipeline.nodes.GenerateCoverLetterNode
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction

object TestCoverLetterCommandHandler {
    fun run() {
        println("[INFO] Testing cover letter generation...")

        val mockState = JDState(
            fitScore = 90.0f,
            pipelineAction = PipelineAction.TAILOR,
            company = "Acme Corp",
            roleTitle = "Staff SDET",
            jdText = "Looking for Staff SDET with mobile automation experience...",
            strengths = listOf("Mobile automation depth", "CI/CD ownership"),
            outputPath = System.getProperty("java.io.tmpdir")
        )

        val coverNode = GenerateCoverLetterNode()
        val result = coverNode.process(mockState)

        if (result.coverLetter.isNotEmpty()) {
            println("[OK] Cover letter generated")
            println("---")
            println(result.coverLetter.take(500))
            println("---")
        }
    }
}
