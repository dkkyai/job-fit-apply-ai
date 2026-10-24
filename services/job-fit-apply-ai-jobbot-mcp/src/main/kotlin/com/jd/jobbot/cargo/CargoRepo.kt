package com.jd.jobbot.cargo

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * jobbot-cargo: a git repository of application packets. JobBot reads all of it and writes exactly one thing:
 * it appends lines to a packet's `STATUS.md`, as the packet's own instructions say, and pushes them. Nothing else
 * in the checkout is ever written, deleted or re-exported.
 */
class CargoRepo(
    val dir: Path,
    private val remote: String,
    private val branch: String,
    private val git: GitRunner,
    private val clock: Clock = Clock.systemUTC(),
    private val syncInterval: Duration = Duration.ofSeconds(60),
    private val identityName: String = "JobBot",
    private val identityEmail: String = "jobbot@users.noreply.github.com",
) {
    data class Sync(val ok: Boolean, val message: String)
    data class Appended(val pushed: Boolean, val line: String, val message: String)
    class CargoException(message: String) : Exception(message)

    private var lastSync: Instant? = null
    private var lastResult = Sync(true, "")

    /** Clone if needed, then bring the checkout up to date and push anything still unpushed. Throttled unless [force]. */
    @Synchronized
    fun sync(force: Boolean = false): Sync {
        val now = clock.instant()
        val last = lastSync
        if (!force && last != null && Duration.between(last, now) < syncInterval) return lastResult
        val result = doSync()
        lastSync = now
        lastResult = result
        return result
    }

    private fun doSync(): Sync {
        Files.createDirectories(dir)
        if (!Files.isDirectory(dir.resolve(".git"))) {
            val clone = git.run(dir, listOf("clone", "--branch", branch, remote, "."), network = true)
            if (!clone.ok) return Sync(false, "clone failed: ${clone.out.take(300)}")
            git.run(dir, listOf("config", "user.name", identityName), false)
            git.run(dir, listOf("config", "user.email", identityEmail), false)
            useUnionMergeForStatus()
            return Sync(true, "cloned")
        }
        useUnionMergeForStatus()
        val pull = git.run(dir, listOf("pull", "--rebase", "--autostash", "origin", branch), network = true)
        if (!pull.ok) {
            git.run(dir, listOf("rebase", "--abort"), false)
            return Sync(false, "pull failed: ${pull.out.take(300)}")
        }
        if (ahead() > 0) {
            val push = git.run(dir, listOf("push", "origin", "HEAD:$branch"), network = true)
            if (!push.ok) return Sync(false, "push failed: ${push.out.take(300)}")
        }
        return Sync(true, "up to date")
    }

    /**
     * STATUS.md is an append-only log that several agents add to, so two pushes always add lines at the same place.
     * Git calls that a conflict; for a log the right merge is to keep both sides. The rule lives in this checkout's
     * `.git/info/attributes`, so the repo itself is not modified.
     */
    private fun useUnionMergeForStatus() {
        runCatching {
            val file = dir.resolve(".git/info/attributes")
            val rule = "STATUS.md merge=union"
            val current = if (Files.isRegularFile(file)) Files.readString(file) else ""
            if (current.lines().none { it.trim() == rule }) {
                Files.createDirectories(file.parent)
                Files.writeString(file, current + (if (current.isNotEmpty() && !current.endsWith("\n")) "\n" else "") + rule + "\n")
            }
        }
    }

    private fun ahead(): Int =
        git.run(dir, listOf("rev-list", "--count", "origin/$branch..HEAD"), false).takeIf { it.ok }?.out?.trim()?.toIntOrNull() ?: 0

    fun packets(): List<Packet> = Packets.scan(dir)

    /** The current (newest-version) packet whose folder is exactly [folder]. Older versions are not packets. */
    fun packetAt(folder: String): Packet? = packets().firstOrNull { it.folder == folder.trim().trim('/') }

    /** Whether [folder] is a packet folder of any version, newest or not. */
    fun isPacketFolder(folder: String): Boolean {
        val rel = folder.trim().trim('/')
        if (!FOLDER.matches(rel)) return false
        val d = dir.resolve(rel)
        return Files.isDirectory(d) && Files.isRegularFile(d.resolve("APPLICATION_PACKET.md"))
    }

    /**
     * Packets matching a company (substring, case-insensitive) and, optionally, a role substring. The newest version
     * of each role only.
     */
    fun find(company: String, role: String? = null): List<Packet> {
        val c = company.trim().lowercase()
        val r = role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return packets().filter { p ->
            (p.company.lowercase().contains(c) || p.folder.lowercase().contains(c.replace(' ', '-'))) &&
                (r == null || p.title.lowercase().contains(r) || p.folder.lowercase().contains(r.replace(' ', '-')))
        }
    }

    /** A repo file, confined to the checkout: no `..`, no symlink out of it, never anything under `.git`. */
    fun resolve(rel: String): Path {
        val clean = rel.trim().trimStart('/')
        if (clean.isEmpty()) throw CargoException("Give a path inside the repo.")
        val root = dir.toRealPath()
        val target = root.resolve(clean).normalize()
        if (!target.startsWith(root)) throw CargoException("That path is outside the repo.")
        if (target.startsWith(root.resolve(".git"))) throw CargoException("That path is not available.")
        if (!Files.exists(target)) throw CargoException("No such file: $clean")
        val real = target.toRealPath()
        if (!real.startsWith(root)) throw CargoException("That path is outside the repo.")
        return real
    }

    fun relative(path: Path): String = dir.toRealPath().relativize(path).joinToString("/")

    fun list(rel: String): List<String> {
        val d = if (rel.isBlank() || rel == "/" || rel == ".") dir.toRealPath() else resolve(rel)
        if (!Files.isDirectory(d)) throw CargoException("Not a directory: $rel")
        return Files.newDirectoryStream(d).use { s ->
            s.filter { it.fileName.toString() != ".git" }.sortedBy { it.fileName.toString() }
                .map { relative(it) + if (Files.isDirectory(it)) "/" else "" }
        }
    }

    /** Case-insensitive substring search over the repo's text files. At most [limit] `path:line: text` hits. */
    fun search(query: String, limit: Int = 40): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) throw CargoException("Give something to search for.")
        val hits = mutableListOf<String>()
        val root = dir.toRealPath()
        Files.walk(root).use { stream ->
            for (p in stream.iterator()) {
                if (hits.size >= limit) break
                if (!Files.isRegularFile(p) || p.startsWith(root.resolve(".git")) || !isText(p) || Files.size(p) > MAX_SEARCH_BYTES) continue
                val rel = relative(p)
                Packets.readText(p).lines().forEachIndexed { i, line ->
                    if (hits.size < limit && line.lowercase().contains(q)) hits += "$rel:${i + 1}: ${line.trim().take(200)}"
                }
            }
        }
        return hits
    }

    /**
     * Append one line to a packet's STATUS.md, commit it and push it. Earlier lines are never touched. A push that is
     * rejected is rebased and retried; if it still fails the commit stays local and the next sync pushes it.
     */
    @Synchronized
    fun appendStatus(folder: String, state: String, note: String): Appended {
        val rel = folder.trim().trim('/')
        if (!FOLDER.matches(rel)) throw CargoException("Not a packet folder: $folder")
        val sync = sync(force = true)
        val packetDir = dir.toRealPath().resolve(rel)
        if (!Files.isDirectory(packetDir) || !Files.isRegularFile(packetDir.resolve("APPLICATION_PACKET.md"))) {
            throw CargoException("No packet at $rel.")
        }
        val line = "- ${clock.instant().let { Instant.ofEpochSecond(it.epochSecond) }} | $state | ${note.replace(Regex("\\s+"), " ").trim().take(MAX_NOTE)}"
        val file = packetDir.resolve("STATUS.md")
        val front = Packets.frontMatter(Packets.readText(packetDir.resolve("APPLICATION_PACKET.md")))
        val existing = if (Files.isRegularFile(file)) Packets.readText(file) else ""
        val text = buildString {
            if (existing.isEmpty()) append("# STATUS — ${front["company"] ?: ""}: ${front["title"] ?: rel}\n\n")
            else if (!existing.endsWith("\n")) append("\n")
            append(line).append("\n")
        }
        useUnionMergeForStatus()
        Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        val statusRel = "$rel/STATUS.md"
        git.run(dir, listOf("add", "--", statusRel), false)
        val commit = git.run(dir, listOf("commit", "-m", "status: ${front["company"]?.lowercase() ?: rel} $state", "--", statusRel), false)
        if (!commit.ok) throw CargoException("git commit failed: ${commit.out.take(300)}")
        var pushed = false
        val syncNote = if (sync.ok) "" else "Couldn't sync first (${sync.message}). "
        var detail = ""
        repeat(PUSH_ATTEMPTS) { attempt ->
            if (pushed) return@repeat
            val push = git.run(dir, listOf("push", "origin", "HEAD:$branch"), network = true)
            if (push.ok) pushed = true
            else {
                detail = "push failed: ${push.out.take(200)}"
                if (attempt < PUSH_ATTEMPTS - 1) {
                    val pull = git.run(dir, listOf("pull", "--rebase", "--autostash", "origin", branch), network = true)
                    // A conflict that even a union merge cannot settle: never leave the checkout half-rebased.
                    if (!pull.ok) git.run(dir, listOf("rebase", "--abort"), false)
                }
            }
        }
        lastSync = null
        return Appended(pushed, line, if (pushed) "committed and pushed" else "committed locally; $syncNote$detail")
    }

    /**
     * After a restart cut an application off with nothing submitted: appends the state the packet had before the attempt
     * (`blocked` if Richard had blocked it, `not_started` if it was untouched), so the attempt's `in_progress` line does
     * not lock the packet for two hours. Null, and nothing written, when the packet is not mid-attempt (its last line is
     * not `in_progress` or `parked_at_review`): some other state has been recorded since and is not ours to undo.
     */
    @Synchronized
    fun restoreAfterInterruption(folder: String, note: String): Appended? {
        val rel = folder.trim().trim('/')
        if (!FOLDER.matches(rel)) throw CargoException("Not a packet folder: $folder")
        sync(force = true)
        val lines = Packets.statusLines(dir.toRealPath().resolve(rel))
        if (lines.lastOrNull()?.state !in ATTEMPT_STATES) return null
        val before = lines.dropLastWhile { it.state in ATTEMPT_STATES }.lastOrNull()?.state ?: "not_started"
        return appendStatus(rel, before, "$note (back to $before)")
    }

    companion object {
        /** The states one application attempt writes: the packet is mid-attempt while its last line is one of them. */
        private val ATTEMPT_STATES = setOf("in_progress", "parked_at_review")
        private val FOLDER = Regex("""^applications/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/v\d+$""")
        private const val MAX_NOTE = 600
        private const val MAX_SEARCH_BYTES = 1_000_000L
        private const val PUSH_ATTEMPTS = 3
        val TEXT_EXTENSIONS = setOf("md", "txt", "yaml", "yml", "json", "csv", "html", "py", "toml")

        fun isText(p: Path): Boolean = p.fileName.toString().substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
    }
}
