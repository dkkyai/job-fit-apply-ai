package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.config.Config
import com.jd.pipeline.models.ProfileLoader
import com.jd.pipeline.models.ResumeYaml
import com.jd.pipeline.utils.ResumeHtmlRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `--resume-gen [path]` — deterministically render a résumé YAML to HTML (no LLM).
 * Defaults to the canonical `resume.yaml`. Writes `<name>-generated.html` next to the input.
 */
object ResumeGenCommandHandler {
    fun run(cmd: Command.ResumeGen) {
        val resumePath = cmd.path.ifEmpty { Config.RESUME_YAML_PATH.toString() }
        val path = Paths.get(resumePath)
        if (!Files.exists(path)) {
            println("[ERROR] --resume-gen: file not found: $path")
            return
        }
        println("[INFO] Rendering HTML résumé from: ${path.fileName}")
        val resume = try {
            ProfileLoader.YAML.readValue(Files.readString(path), ResumeYaml::class.java)
        } catch (e: Exception) {
            println("[ERROR] --resume-gen: failed to parse ${path.fileName}: ${e.message}")
            return
        }
        val profile = ProfileLoader.merge(resume, ProfileLoader.loadConfig())
        val out = path.resolveSibling("${nameWithoutExt(path)}-generated.html")
        Files.writeString(out, ResumeHtmlRenderer.render(profile))
        println("[INFO] Generated: $out")
    }

    private fun nameWithoutExt(path: Path): String {
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
