package com.jd.jobbot.cargo

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

data class GitResult(val code: Int, val out: String) {
    val ok get() = code == 0
}

/** Runs git. A seam so tests can use a plain local repository and production can add the GitHub token. */
fun interface GitRunner {
    /** [network] commands (clone, fetch, pull, push) are the only ones given credentials. */
    fun run(dir: Path, args: List<String>, network: Boolean): GitResult
}

/**
 * The real git. The token is read from [tokenFile] on every network command and handed to git as an
 * `http.extraHeader` through the environment: it is never in argv, in `.git/config` or in the remote URL,
 * and it is scrubbed from any output that comes back.
 */
class ProcessGit(private val tokenFile: Path?, private val timeoutSeconds: Long = 120) : GitRunner {
    override fun run(dir: Path, args: List<String>, network: Boolean): GitResult {
        val pb = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true)
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
        val token = if (network) tokenFile?.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it).trim() }?.takeIf { it.isNotEmpty() } else null
        val basic = token?.let { Base64.getEncoder().encodeToString("x-access-token:$it".toByteArray()) }
        if (basic != null) {
            pb.environment()["GIT_CONFIG_COUNT"] = "1"
            pb.environment()["GIT_CONFIG_KEY_0"] = "http.extraHeader"
            pb.environment()["GIT_CONFIG_VALUE_0"] = "Authorization: Basic $basic"
        }
        val proc = pb.start()
        val out = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { isDaemon = true; start() }
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return GitResult(124, "git ${args.firstOrNull()} timed out after ${timeoutSeconds}s")
        }
        reader.join(2_000)
        var text = out.toString().trim()
        listOfNotNull(token, basic).forEach { text = text.replace(it, "***") }
        return GitResult(proc.exitValue(), text)
    }
}
