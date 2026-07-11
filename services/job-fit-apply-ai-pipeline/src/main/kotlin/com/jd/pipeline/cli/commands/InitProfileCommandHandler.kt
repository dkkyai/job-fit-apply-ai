package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.nodes.GenerateCandidateProfileNode
import java.nio.file.Files
import java.nio.file.Paths

object InitProfileCommandHandler {
    fun run(cmd: Command.InitProfile) {
        val resumePath = cmd.path
        if (resumePath.isEmpty()) {
            println("[ERROR] --init-profile: path is null or empty")
            return
        }
        val path = Paths.get(resumePath)
        if (!Files.exists(path)) {
            println("[ERROR] --init-profile: file not found: $path")
            return
        }
        println("[INFO] Initialising candidate profile from: ${path.fileName}")
        try {
            GenerateCandidateProfileNode().init(path)
        } catch (e: Exception) {
            println("[ERROR] --init-profile: ${e.message}")
        }
    }
}
