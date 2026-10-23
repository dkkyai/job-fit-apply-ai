package com.jd.jobbot.cargo

import com.jd.jobbot.actions.JobUrls
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * One application packet: the newest `vNN` folder of one role, as `APPLICATION_PACKET.md` and `STATUS.md` describe it.
 * [application] is the last state in STATUS.md (`not_started` when there is none): STATUS.md is always current,
 * INDEX.md only as of the last sync, so this never reads INDEX.md.
 */
data class Packet(
    val company: String,
    val title: String,
    /** Repo-relative, e.g. `applications/apple/some-role/v03`. */
    val folder: String,
    val version: Int,
    val status: String,
    val statusReason: String?,
    val applyUrl: String?,
    /** Repo-relative path of the file to upload, from the packet's `upload:` field. */
    val upload: String?,
    val updated: String?,
    val application: String,
    val applicationSince: Instant?,
    val lastNote: String?,
    /** An older version of this role was already worked on: ask Richard first. */
    val olderWorked: Boolean,
    val olderVersions: List<String>,
    /** Lines in STATUS.md that look like status lines (`- …`) but could not be read. */
    val statusUnparsed: Int = 0,
)

data class StatusLine(val at: Instant?, val state: String, val note: String)

object Packets {
    private val VERSION_DIR = Regex("""^v(\d+)$""")
    /** `- <time> | <state> | <note>`. The note, and the bar before it, may be missing: "completed" must never be missed. */
    private val STATUS_LINE = Regex("""^-\s+(\S+)\s+\|\s*([A-Za-z0-9_-]+)\s*(?:\|\s*(.*?))?\s*$""")
    private val HASH_ROW = Regex("""^\|\s*`([^`]+)`\s*\|[^|]*UPLOAD[^|]*\|\s*`([0-9a-f]{6,64})`\s*\|""", RegexOption.MULTILINE)

    /** The newest version of every role under `applications/`. */
    fun scan(root: Path): List<Packet> {
        val apps = root.resolve("applications")
        if (!Files.isDirectory(apps)) return emptyList()
        val out = mutableListOf<Packet>()
        Files.newDirectoryStream(apps).use { companies ->
            companies.filter { Files.isDirectory(it) }.sorted().forEach { company ->
                Files.newDirectoryStream(company).use { roles ->
                    roles.filter { Files.isDirectory(it) }.sorted().forEach { role -> newest(root, role)?.let { out += it } }
                }
            }
        }
        return out
    }

    private fun newest(root: Path, roleDir: Path): Packet? {
        val versions = Files.newDirectoryStream(roleDir).use { s ->
            s.mapNotNull { d -> VERSION_DIR.matchEntire(d.fileName.toString())?.let { it.groupValues[1].toInt() to d } }
        }.sortedBy { it.first }
        val (version, dir) = versions.lastOrNull() ?: return null
        val packetFile = dir.resolve("APPLICATION_PACKET.md")
        if (!Files.isRegularFile(packetFile)) return null
        val front = frontMatter(readText(packetFile))
        val status = readStatus(dir)
        val last = status.lines.lastOrNull()
        val older = versions.dropLast(1)
        val olderWorked = older.filter { (_, d) -> readStatus(d).let { it.lines.isNotEmpty() || it.unparsed > 0 } }
        val rel = root.relativize(dir).joinToString("/")
        return Packet(
            company = front["company"] ?: roleDir.parent.fileName.toString(),
            title = front["title"] ?: roleDir.fileName.toString(),
            folder = rel,
            version = version,
            status = (front["status"] ?: "DRAFT").uppercase(),
            statusReason = front["status_reason"]?.takeIf { it.isNotBlank() },
            applyUrl = front["apply_url"]?.takeIf { it.isNotBlank() },
            upload = front["upload"]?.takeIf { it.isNotBlank() }?.let { "$rel/$it" },
            updated = front["updated"],
            application = last?.state ?: "not_started",
            applicationSince = last?.at,
            lastNote = last?.note?.take(300),
            olderWorked = olderWorked.isNotEmpty(),
            olderVersions = older.map { "v%02d".format(it.first) },
            statusUnparsed = status.unparsed,
        )
    }

    /**
     * A repo file as text. Malformed UTF-8 becomes U+FFFD instead of an exception: one badly encoded file written
     * by some other agent must not make the whole repo unreadable.
     */
    fun readText(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)

    /** STATUS.md's readable lines, and how many list items (`- …`) in it were not. */
    data class Status(val lines: List<StatusLine>, val unparsed: Int)

    fun statusLines(dir: Path): List<StatusLine> = readStatus(dir).lines

    fun readStatus(dir: Path): Status {
        val f = dir.resolve("STATUS.md")
        if (!Files.isRegularFile(f)) return Status(emptyList(), 0)
        val lines = mutableListOf<StatusLine>()
        var unparsed = 0
        readText(f).lines().forEach { line ->
            val m = STATUS_LINE.matchEntire(line)
            if (m != null) {
                lines += StatusLine(runCatching { Instant.parse(m.groupValues[1]) }.getOrNull(), m.groupValues[2].lowercase(), m.groupValues[3])
            } else if (line.startsWith("- ")) {
                unparsed++
            }
        }
        return Status(lines, unparsed)
    }

    /** `key: value` lines between the first two `---` lines. */
    fun frontMatter(text: String): Map<String, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyMap()
        return lines.subList(1, end + 1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('"')
        }.toMap()
    }

    /** The hash (first hex characters of SHA-256) the packet's files table gives for its UPLOAD file, if it has one. */
    fun uploadHash(packetMd: String): String? = HASH_ROW.find(packetMd)?.groupValues?.get(2)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Whether a packet may be applied to, by the rules its own INDEX.md and APPLICATION_PACKET.md state:
 * READY (a DRAFT only when Richard says so) and `not_started` or `in_progress`; `completed` never; `blocked`
 * waits for Richard; a ⚠ (an older version already worked) asks Richard first.
 */
object CargoEligibility {
    /** An `in_progress` line this fresh may belong to another agent working the same form right now. */
    val IN_PROGRESS_FRESH: Duration = Duration.ofHours(2)
    private val RETRYABLE = setOf("not_started", "in_progress")

    sealed interface Verdict {
        object Go : Verdict
        /** Not applicable without Richard's say-so: [why] says what, and he can approve it with his words. */
        data class NeedsApproval(val why: String) : Verdict
        /** Never, whatever anyone says. */
        data class No(val why: String) : Verdict
    }

    fun check(p: Packet, now: Instant): Verdict {
        if (p.application == "completed") return Verdict.No("already completed — never apply again")
        if (p.applyUrl == null) return Verdict.No("the packet has no apply URL")
        (JobUrls.check(p.applyUrl) as? JobUrls.Check.Rejected)?.let { return Verdict.No("the apply URL isn't a public web link (${it.reason.trimEnd('.')})") }
        if (p.upload == null) return Verdict.No("the packet names no upload file")
        if (p.application == "in_progress" && p.applicationSince != null && Duration.between(p.applicationSince, now) < IN_PROGRESS_FRESH) {
            return Verdict.No("opened ${Duration.between(p.applicationSince, now).toMinutes()} min ago — another agent may be filling it")
        }
        val needs = buildList {
            if (p.status != "READY") add("it is ${p.status}${p.statusReason?.let { " ($it)" } ?: ""}")
            if (p.application !in RETRYABLE) add("its application state is ${p.application}")
            if (p.olderWorked) add("an older version (${p.olderVersions.joinToString()}) was already worked on")
            if (p.statusUnparsed > 0) add("its STATUS.md has ${p.statusUnparsed} line(s) JobBot can't read, so its real state is unknown")
        }
        return if (needs.isEmpty()) Verdict.Go else Verdict.NeedsApproval(needs.joinToString("; "))
    }
}
