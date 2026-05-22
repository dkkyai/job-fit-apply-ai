package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.config.Config
import com.jd.pipeline.nodes.GenerateResumeHtmlNode
import java.nio.file.Files
import java.nio.file.Paths

object ResumeGenCommandHandler {
    fun run(cmd: Command.ResumeGen) {
        val resumePath = cmd.path
        if (resumePath.isEmpty()) {
            println("[ERROR] --resume-gen: path is null or empty")
            return
        }
        val path = Paths.get(resumePath)
        if (!Files.exists(path)) {
            println("[ERROR] --resume-gen: file not found: $path")
            return
        }
        println("[INFO] Generating HTML resume from: ${path.fileName}")
        println("[INFO] Model: ${Config.RESUME_GEN_MODEL}")
        val outputPath = GenerateResumeHtmlNode().generate(path)
        println("[INFO] Generated: $outputPath")
    }
}
