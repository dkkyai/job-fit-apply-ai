package com.jd.pipeline.nodes

import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import java.net.URLEncoder
import java.nio.file.Path

/**
 * Node: add_artifact_url
 * 
 * Add artifact URL based on output path.
 */
class AddArtifactUrlNode : Node<JDState> {

    override fun process(input: JDState): JDState {
        val outputPath = input.outputPath

        if (outputPath.isNullOrEmpty() || Config.ARTIFACT_BASE_URL.isEmpty()) {
            return input
        }

        // Strip the artifact base path prefix if configured
        val relativePath = if (Config.ARTIFACT_BASE_PATH.isNotEmpty() && outputPath.startsWith(Config.ARTIFACT_BASE_PATH)) {
            outputPath.removePrefix(Config.ARTIFACT_BASE_PATH)
        } else {
            outputPath
        }

        // Build artifact URL — use only the folder name (last path segment)
        val path = Path.of(relativePath)
        val folderName = path.fileName?.toString() ?: relativePath
        val encoded = URLEncoder.encode(folderName, "UTF-8").replace("+", "%20")
        val artifactUrl = Config.ARTIFACT_BASE_URL.replace(Regex("/$"), "") + "/$encoded/"

        return input.copy(artifactUrl = artifactUrl)
    }
}
