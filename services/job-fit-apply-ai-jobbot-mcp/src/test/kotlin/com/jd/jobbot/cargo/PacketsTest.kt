package com.jd.jobbot.cargo

import com.jd.jobbot.cargo.CargoEligibility.Verdict
import com.jd.jobbot.support.CargoRemote
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketsTest {
    @TempDir lateinit var tmp: Path
    private val now = Instant.parse("2026-10-22T20:00:00Z")
    private val remote by lazy { CargoRemote(tmp) }
    private fun scan() = Packets.scan(remote.seed)

    @Test
    fun `the newest version of each role is the packet`() {
        remote.packet("acme", "sdet", 0)
        remote.packet("acme", "sdet", 1)
        remote.packet("acme", "sdet", 2)
        remote.packet("acme", "lead", 0)
        val packets = scan()
        assertEquals(listOf("applications/acme/lead/v00", "applications/acme/sdet/v02"), packets.map { it.folder })
        assertEquals(listOf("v00", "v01"), packets.last().olderVersions)
    }

    @Test
    fun `the front matter is read`() {
        remote.packet("apple", "tools-engineer", 3, title = "Tools & Automation Engineer", url = "https://jobs.apple.com/x", status = "READY")
        val p = scan().single()
        assertEquals("Apple", p.company)
        assertEquals("Tools & Automation Engineer", p.title)
        assertEquals("READY", p.status)
        assertEquals("https://jobs.apple.com/x", p.applyUrl)
        assertEquals("applications/apple/tools-engineer/v03/apply-2p/Richard-Hatcher-Resume.pdf", p.upload)
        assertEquals("attested NOTHING FABRICATED; no open questions", p.statusReason)
        assertEquals(3, p.version)
    }

    @Test
    fun `a packet with no STATUS_md has not started`() {
        remote.packet("acme", "sdet")
        val p = scan().single()
        assertEquals("not_started", p.application)
        assertNull(p.applicationSince)
    }

    @Test
    fun `the last STATUS line is the application state`() {
        remote.packet("acme", "sdet", statusLines = listOf(
            "- 2026-10-16T17:05:00Z | in_progress | opened the form",
            "- 2026-10-16T17:30:00Z | completed | confirmation: 123",
        ))
        val p = scan().single()
        assertEquals("completed", p.application)
        assertEquals(Instant.parse("2026-10-16T17:30:00Z"), p.applicationSince)
        assertEquals("confirmation: 123", p.lastNote)
    }

    @Test
    fun `states the repo invented are kept as they are`() {
        remote.packet("apple", "x", statusLines = listOf("- 2026-10-22T22:57:00Z | parked_at_review | filled; NOT submitted"))
        assertEquals("parked_at_review", scan().single().application)
    }

    @Test
    fun `lines that are not status lines are ignored`() {
        remote.packet("acme", "sdet", statusLines = listOf("some prose", "- not a status line", "- 2026-10-16T17:05:00Z | blocked | needs a phone code"))
        assertEquals("blocked", scan().single().application)
    }

    @Test
    fun `an older version that was worked on warns, an untouched one does not`() {
        remote.packet("acme", "worked", 0, statusLines = listOf("- 2026-10-01T00:00:00Z | completed | done"))
        remote.packet("acme", "worked", 1)
        remote.packet("acme", "fresh", 0)
        remote.packet("acme", "fresh", 1)
        val byRole = scan().associateBy { it.folder.split('/')[2] }
        assertTrue(byRole.getValue("worked").olderWorked)
        assertEquals("not_started", byRole.getValue("worked").application, "the new version's own STATUS decides")
        assertFalse(byRole.getValue("fresh").olderWorked)
    }

    @Test
    fun `folders without a packet file or a version name are skipped`() {
        remote.packet("acme", "sdet")
        remote.write("applications/acme/other/notes/readme.md", "x")
        remote.write("applications/acme/empty/v00/readme.md", "x")
        assertEquals(1, scan().size)
    }

    @Test
    fun `a repo without applications is empty`() {
        assertEquals(emptyList(), scan())
    }

    @Test
    fun `a missing status defaults to DRAFT, never READY`() {
        remote.write("applications/acme/odd/v00/APPLICATION_PACKET.md", "---\ncompany: Acme\ntitle: Odd\n---\n")
        assertEquals("DRAFT", scan().single().status)
    }

    @Test
    fun `the upload hash is read from the files table`() {
        val md = "| File | Use | Hash |\n|---|---|---|\n" +
            "| `apply-2p/Richard-Hatcher-Resume.docx` | Word copy — only if the form cannot take a PDF | `dbe573715dc7` |\n" +
            "| `apply-2p/Richard-Hatcher-Resume.pdf` | **UPLOAD** — the default file to attach | `4f21fd50e16e` |\n"
        assertEquals("4f21fd50e16e", Packets.uploadHash(md))
        assertNull(Packets.uploadHash("no table"))
    }

    @Test
    fun `a completed line is read whatever its note or spacing`() {
        for ((i, line) in listOf(
            "- 2026-10-22T23:03:00Z | completed |",
            "- 2026-10-22T23:03:00Z | completed | ",
            "- 2026-10-22T23:03:00Z | completed",
            "- 2026-10-22T23:03:00Z | COMPLETED | confirmed",
            "- 2026-10-22T23:03:00Z | Completed | confirmed",
            "- 2026-10-22T23:03:00Z | completed |   confirmed   ",
        ).withIndex()) {
            remote.packet("co$i", "sdet", statusLines = listOf(line))
        }
        val packets = scan()
        assertEquals(6, packets.size)
        assertTrue(packets.all { it.application == "completed" }, packets.map { it.application }.toString())
        assertTrue(packets.all { CargoEligibility.check(it, now) is Verdict.No })
    }

    @Test
    fun `a status line JobBot cannot read makes the packet need approval, never go ahead`() {
        remote.packet("acme", "sdet", statusLines = listOf("- 2026-10-22T23:03:00Z submitted the form, confirmation 123"))
        val p = scan().single()
        assertEquals("not_started", p.application)
        val v = CargoEligibility.check(p, now)
        assertIs<Verdict.NeedsApproval>(v)
        assertTrue(v.why.contains("STATUS.md") && v.why.contains("can't read"), v.why)
    }

    @Test
    fun `headings, blank lines and prose are not unreadable status lines`() {
        remote.packet("acme", "sdet", statusLines = listOf("", "Some prose about the application.", "  - indented note", "- 2026-10-16T17:05:00Z | in_progress | opened"))
        val p = scan().single()
        assertEquals("in_progress", p.application)
        assertEquals(0, p.statusUnparsed)
    }

    @Test
    fun `the last readable line still wins when an unreadable one sits before it`() {
        remote.packet("acme", "sdet", statusLines = listOf("- garbage", "- 2026-10-16T17:30:00Z | completed | done"))
        val p = scan().single()
        assertEquals("completed", p.application)
        assertIs<Verdict.No>(CargoEligibility.check(p, now), "completed is checked before the unreadable-line rule")
    }

    // ── eligibility ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `an apply URL that is not a public web link is a hard no`() {
        for (url in listOf("http://127.0.0.1/x", "http://jobbot-mcp:8790/", "file:///etc/passwd", "ftp://x.com/j", "https://u:p@x.com/j", "not a url")) {
            val v = CargoEligibility.check(packet(url = url), now)
            assertIs<Verdict.No>(v, url)
            assertTrue(v.why.contains("apply URL"), v.why)
        }
        assertEquals(Verdict.Go, CargoEligibility.check(packet(url = "https://jobs.example.com/j?id=1"), now))
    }

    private fun packet(
        status: String = "READY", application: String = "not_started", since: Instant? = null, older: Boolean = false,
        url: String? = "https://x.co/j", upload: String? = "a/b.pdf",
    ) = Packet("Acme", "SDET", "applications/acme/sdet/v00", 0, status, null, url, upload, null, application, since, null, older, if (older) listOf("v00") else emptyList())

    @Test
    fun `a READY not-started packet can be applied to`() {
        assertEquals(Verdict.Go, CargoEligibility.check(packet(), now))
    }

    @Test
    fun `in_progress is retryable once the line is old enough`() {
        assertEquals(Verdict.Go, CargoEligibility.check(packet(application = "in_progress", since = now.minusSeconds(3 * 3600)), now))
    }

    @Test
    fun `a fresh in_progress line may be another agent and is never overridden`() {
        val v = CargoEligibility.check(packet(application = "in_progress", since = now.minusSeconds(600)), now)
        assertIs<Verdict.No>(v)
        assertTrue(v.why.contains("10 min ago"), v.why)
    }

    @Test
    fun `completed is never applied to again`() {
        assertIs<Verdict.No>(CargoEligibility.check(packet(application = "completed"), now))
        assertIs<Verdict.No>(CargoEligibility.check(packet(status = "DRAFT", application = "completed"), now))
    }

    @Test
    fun `a DRAFT needs Richard's say-so and says why`() {
        val v = CargoEligibility.check(packet(status = "DRAFT"), now)
        assertIs<Verdict.NeedsApproval>(v)
        assertTrue(v.why.contains("DRAFT"), v.why)
    }

    @Test
    fun `blocked and parked packets wait for Richard`() {
        for (state in listOf("blocked", "parked_at_review", "submitted")) {
            assertIs<Verdict.NeedsApproval>(CargoEligibility.check(packet(application = state), now), state)
        }
    }

    @Test
    fun `an older version already worked on asks first`() {
        val v = CargoEligibility.check(packet(older = true), now)
        assertIs<Verdict.NeedsApproval>(v)
        assertTrue(v.why.contains("older version"), v.why)
    }

    @Test
    fun `reasons are combined`() {
        val v = CargoEligibility.check(packet(status = "DRAFT", older = true), now)
        assertIs<Verdict.NeedsApproval>(v)
        assertTrue(v.why.contains("DRAFT") && v.why.contains("older version"), v.why)
    }

    @Test
    fun `no apply URL or no upload file is a hard no`() {
        assertIs<Verdict.No>(CargoEligibility.check(packet(url = null), now))
        assertIs<Verdict.No>(CargoEligibility.check(packet(upload = null), now))
    }

    @Test
    fun `versions are ordered by number, not by name`() {
        for (v in listOf(2, 9, 10, 11)) remote.packet("acme", "sdet", v)
        val p = scan().single()
        assertEquals(11, p.version)
        assertEquals(listOf("v02", "v09", "v10"), p.olderVersions)
    }

    @Test
    fun `windows line endings do not hide a packet's status, state or hash`() {
        val folder = remote.packet("acme", "sdet", statusLines = listOf("- 2026-10-16T17:30:00Z | completed | done"))
        val dir = remote.seed.resolve(folder)
        for (f in listOf("APPLICATION_PACKET.md", "STATUS.md")) {
            java.nio.file.Files.writeString(dir.resolve(f), java.nio.file.Files.readString(dir.resolve(f)).replace("\n", "\r\n"))
        }
        val p = scan().single()
        assertEquals("READY", p.status)
        assertEquals("completed", p.application)
        assertEquals("https://jobs.example.com/acme/sdet", p.applyUrl)
        assertEquals(0, p.statusUnparsed)
        assertEquals(12, Packets.uploadHash(java.nio.file.Files.readString(dir.resolve("APPLICATION_PACKET.md")))?.length)
    }

    @Test
    fun `quoted front matter values are unquoted and a colon in a value is kept`() {
        remote.write("applications/acme/odd/v00/APPLICATION_PACKET.md", "---\ncompany: \"Acme, Inc.\"\ntitle: \"Engineer: Quality\"\nstatus: ready\napply_url: https://x.co/j?a=1:2\nupload: a/b.pdf\n---\n")
        val p = scan().single()
        assertEquals("Acme, Inc.", p.company)
        assertEquals("Engineer: Quality", p.title)
        assertEquals("READY", p.status, "status is case-insensitive")
        assertEquals("https://x.co/j?a=1:2", p.applyUrl)
    }

    @Test
    fun `front matter without a closing line is not trusted`() {
        remote.write("applications/acme/odd/v00/APPLICATION_PACKET.md", "---\ncompany: Acme\nstatus: READY\napply_url: https://x.co/j\nupload: a/b.pdf\n")
        val p = scan().single()
        assertEquals("DRAFT", p.status, "an unterminated block must not make a packet READY")
        assertNull(p.applyUrl)
    }

    @Test
    fun `status lines tolerate extra spaces and a missing time zone`() {
        remote.packet("acme", "sdet", statusLines = listOf("-   2026-10-16T17:05:00Z   |   blocked   |   spaced   out  "))
        val p = scan().single()
        assertEquals("blocked", p.application)
        assertEquals("spaced   out", p.lastNote)
    }

    @Test
    fun `a status time that is not a timestamp is kept as unknown, and in_progress then counts as old`() {
        remote.packet("acme", "sdet", statusLines = listOf("- yesterday | in_progress | opened"))
        val p = scan().single()
        assertEquals("in_progress", p.application)
        assertNull(p.applicationSince)
        assertEquals(Verdict.Go, CargoEligibility.check(p, now))
    }

    @Test
    fun `the older-version warning counts any line, readable or not`() {
        remote.packet("acme", "sdet", 0, statusLines = listOf("- nonsense without bars"))
        remote.packet("acme", "sdet", 1)
        assertTrue(scan().single().olderWorked)
    }

    @Test
    fun `scanning a large repo stays cheap`() {
        repeat(150) { remote.packet("co$it", "role", 0); remote.packet("co$it", "role", 1) }
        val t0 = System.nanoTime()
        assertEquals(150, scan().size)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000, "scanning 300 packet folders should take well under 5 s")
    }

    @Test
    fun `one badly encoded file cannot make the whole repo unreadable`() {
        val bad = remote.packet("acme", "sdet", statusLines = listOf("- 2026-10-16T17:30:00Z | completed | caf\u00e9 done"))
        val ok = remote.packet("beta", "qa")
        java.nio.file.Files.write(remote.seed.resolve("$bad/STATUS.md"), "- 2026-10-16T17:30:00Z | completed | caf\u00e9 done\n".toByteArray(Charsets.ISO_8859_1))
        val packetFile = remote.seed.resolve("$ok/APPLICATION_PACKET.md")
        java.nio.file.Files.write(packetFile, java.nio.file.Files.readAllBytes(packetFile) + "\n caf\u00e9".toByteArray(Charsets.ISO_8859_1))
        val packets = scan().associateBy { it.company }
        assertEquals(2, packets.size)
        assertEquals("completed", packets.getValue("Acme").application, "the line is still read; only the bad byte is replaced")
        assertEquals("READY", packets.getValue("Beta").status)
    }
}
