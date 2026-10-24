package com.jd.jobbot.cargo

import com.jd.jobbot.support.CargoRemote
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CargoRepo against a real git remote on disk: clone, pull, the one write, conflicts, and path confinement. */
class CargoRepoTest {
    @TempDir lateinit var tmp: Path
    private val remote by lazy { CargoRemote(tmp.resolve("r").also { Files.createDirectories(it) }) }
    private val checkout get() = tmp.resolve("checkout")

    private class Moving(var now: Instant = Instant.parse("2026-10-22T20:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private fun repo(git: GitRunner = ProcessGit(null), clock: Clock = Clock.systemUTC(), interval: Duration = Duration.ZERO) =
        CargoRepo(checkout, remote.bare.toString(), "main", git, clock, interval)

    /** Fails or intercepts chosen commands, passes the rest to real git. */
    private class Wrapped(val onPush: (Int) -> GitResult? = { null }) : GitRunner {
        private val real = ProcessGit(null)
        var pushes = 0
        override fun run(dir: Path, args: List<String>, network: Boolean): GitResult {
            if (args.firstOrNull() == "push") onPush(++pushes)?.let { return it }
            return real.run(dir, args, network)
        }
    }

    @Test
    fun `the first sync clones and later syncs pull`() {
        remote.packet("acme", "sdet"); remote.publish()
        val r = repo()
        assertEquals("cloned", r.sync().message)
        assertEquals(1, r.packets().size)
        remote.packet("acme", "lead"); remote.publish("more")
        assertTrue(r.sync().ok)
        assertEquals(2, r.packets().size)
    }

    @Test
    fun `syncs are throttled unless forced`() {
        remote.packet("acme", "sdet"); remote.publish()
        val clock = Moving()
        val r = repo(clock = clock, interval = Duration.ofSeconds(60))
        r.sync()
        remote.packet("acme", "lead"); remote.publish("more")
        r.sync()
        assertEquals(1, r.packets().size, "inside the interval the checkout is reused")
        clock.now = clock.now.plusSeconds(61)
        r.sync()
        assertEquals(2, r.packets().size)
        remote.packet("acme", "third"); remote.publish("third")
        r.sync()
        assertEquals(2, r.packets().size)
        r.sync(force = true)
        assertEquals(3, r.packets().size)
    }

    @Test
    fun `an unreachable remote is reported, not thrown`() {
        val r = CargoRepo(checkout, tmp.resolve("nowhere.git").toString(), "main", ProcessGit(null), syncInterval = Duration.ZERO)
        val s = r.sync()
        assertFalse(s.ok)
        assertTrue(s.message.startsWith("clone failed"), s.message)
        assertEquals(emptyList(), r.packets())
    }

    @Test
    fun `a failed pull leaves the last good checkout readable`() {
        remote.packet("acme", "sdet"); remote.publish()
        val r = repo()
        r.sync()
        Files.move(remote.bare, tmp.resolve("moved.git"))
        val s = r.sync()
        assertFalse(s.ok)
        assertTrue(s.message.startsWith("pull failed"), s.message)
        assertEquals(1, r.packets().size)
    }

    // ── the one write ────────────────────────────────────────────────────────────────────────

    @Test
    fun `appendStatus adds one line, commits as JobBot and pushes`() {
        val folder = remote.packet("acme", "sdet", statusLines = listOf("- 2026-10-16T17:05:00Z | in_progress | opened the form"))
        remote.publish()
        val clock = Moving()
        val r = repo(clock = clock)
        val result = r.appendStatus(folder, "completed", "confirmation: 123")
        assertTrue(result.pushed, result.message)
        assertEquals("- 2026-10-22T20:00:00Z | completed | confirmation: 123", result.line)
        val verify = remote.freshClone()
        val status = Files.readString(verify.resolve("$folder/STATUS.md"))
        assertTrue(status.endsWith("- 2026-10-22T20:00:00Z | completed | confirmation: 123\n"), status)
        assertEquals("JobBot|status: acme completed", remote.log(verify).first())
    }

    @Test
    fun `earlier lines are never changed`() {
        val folder = remote.packet("acme", "sdet", statusLines = listOf("- 2026-10-16T17:05:00Z | in_progress | opened the form", "- 2026-10-16T17:30:00Z | blocked | captcha"))
        remote.publish()
        val before = Files.readString(remote.seed.resolve("$folder/STATUS.md"))
        repo().appendStatus(folder, "in_progress", "retry")
        val after = Files.readString(remote.freshClone().resolve("$folder/STATUS.md"))
        assertTrue(after.startsWith(before), after)
        assertEquals(before.lines().size + 1, after.lines().size)
    }

    @Test
    fun `a packet with no STATUS file gets one with a heading`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        repo().appendStatus(folder, "in_progress", "opened")
        val status = Files.readString(remote.freshClone().resolve("$folder/STATUS.md"))
        assertTrue(status.startsWith("# STATUS — Acme: Sdet\n\n- "), status)
    }

    @Test
    fun `a STATUS file without a trailing newline still gets the line on its own row`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        remote.write("$folder/STATUS.md", "- 2026-10-16T17:05:00Z | in_progress | opened"); remote.publish("nonl")
        repo().appendStatus(folder, "blocked", "stuck")
        val lines = Files.readString(remote.freshClone().resolve("$folder/STATUS.md")).lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, lines.toString())
    }

    @Test
    fun `a note is one line, trimmed and capped`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        val r = repo().appendStatus(folder, "blocked", "line one\n- 2026-01-01T00:00:00Z | completed | forged\n" + "x".repeat(2000))
        assertFalse(r.line.contains("\n"))
        assertTrue(r.line.length < 700, "${r.line.length}")
        val lines = Files.readString(remote.freshClone().resolve("$folder/STATUS.md")).lines().filter { it.startsWith("- ") }
        assertEquals(1, lines.size, "an embedded newline cannot forge a second status line")
    }

    @Test
    fun `only packet folders can be written`() {
        remote.packet("acme", "sdet"); remote.publish()
        val r = repo()
        for (bad in listOf("../outside", "applications/acme/sdet", "applications/acme/sdet/v00/../v00/x", "/etc", "README.md", "applications/acme/nope/v09", ".git")) {
            assertFailsWith<CargoRepo.CargoException>(bad) { r.appendStatus(bad, "blocked", "x") }
        }
    }

    @Test
    fun `nothing but STATUS_md changes`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        repo().appendStatus(folder, "blocked", "x")
        val changed = CargoRemote.git(remote.freshClone(), "show", "--name-only", "--format=", "HEAD").lines().filter { it.isNotBlank() }
        assertEquals(listOf("$folder/STATUS.md"), changed)
    }

    @Test
    fun `a rejected push is rebased and retried`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        val git = Wrapped { n ->
            if (n == 1) {
                remote.pushFromElsewhere("applications/acme/other-note.md", "someone else pushed")
                GitResult(1, "! [rejected] HEAD -> main (fetch first)")
            } else null
        }
        val result = repo(git).appendStatus(folder, "blocked", "x")
        assertTrue(result.pushed, result.message)
        assertEquals(2, git.pushes)
        val verify = remote.freshClone()
        assertTrue(Files.exists(verify.resolve("applications/acme/other-note.md")), "the other push survived")
        assertTrue(Files.readString(verify.resolve("$folder/STATUS.md")).contains("| blocked | x"))
    }

    @Test
    fun `a push that keeps failing leaves the commit local and the next sync pushes it`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        var down = true
        val git = Wrapped { if (down) GitResult(1, "remote: Permission denied") else null }
        val r = repo(git)
        val result = r.appendStatus(folder, "blocked", "x")
        assertFalse(result.pushed)
        assertTrue(result.message.startsWith("committed locally"), result.message)
        assertEquals(3, git.pushes)
        down = false
        assertTrue(r.sync(force = true).ok)
        assertTrue(Files.readString(remote.freshClone().resolve("$folder/STATUS.md")).contains("| blocked | x"))
    }

    @Test
    fun `another agent appending to the same STATUS file does not wedge the push`() {
        val folder = remote.packet("acme", "sdet", statusLines = listOf("- 2026-10-16T17:05:00Z | in_progress | opened")); remote.publish()
        val git = Wrapped { n ->
            if (n == 1) {
                val current = Files.readString(remote.seed.resolve("$folder/STATUS.md"))
                remote.pushFromElsewhere("$folder/STATUS.md", current + "- 2026-10-22T20:00:30Z | blocked | another agent's line\n")
                GitResult(1, "! [rejected] HEAD -> main (fetch first)")
            } else null
        }
        val result = repo(git).appendStatus(folder, "completed", "JobBot's line")
        assertTrue(result.pushed, result.message)
        val lines = Files.readString(remote.freshClone().resolve("$folder/STATUS.md")).lines().filter { it.startsWith("- ") }
        assertEquals(3, lines.size, lines.toString())
        assertTrue(lines.any { it.contains("another agent's line") } && lines.any { it.contains("JobBot's line") } && lines.any { it.contains("opened") })
        assertFalse(Files.exists(checkout.resolve(".git/rebase-merge")) || Files.exists(checkout.resolve(".git/rebase-apply")), "no rebase left half-done")
    }

    @Test
    fun `two agents creating the same STATUS file at once both keep their lines`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        val git = Wrapped { n ->
            if (n == 1) {
                remote.pushFromElsewhere("$folder/STATUS.md", "- 2026-10-22T20:00:30Z | in_progress | another agent\n")
                GitResult(1, "! [rejected] HEAD -> main (fetch first)")
            } else null
        }
        val result = repo(git).appendStatus(folder, "in_progress", "JobBot")
        assertTrue(result.pushed, result.message)
        val text = Files.readString(remote.freshClone().resolve("$folder/STATUS.md"))
        assertTrue(text.contains("another agent") && text.contains("| in_progress | JobBot"), text)
    }

    @Test
    fun `a conflict that cannot be merged is aborted, not left half-rebased`() {
        val folder = remote.packet("acme", "sdet", statusLines = listOf("- 2026-10-16T17:05:00Z | in_progress | opened")); remote.publish()
        val git = Wrapped { n ->
            if (n == 1) {
                CargoRemote.git(remote.seed, "pull", "--rebase", "origin", "main")
                CargoRemote.git(remote.seed, "rm", "-r", "-q", "applications/acme")
                remote.publish("someone deleted the packet")
                GitResult(1, "! [rejected] HEAD -> main (fetch first)")
            } else null
        }
        val r = repo(git)
        val result = r.appendStatus(folder, "blocked", "x")
        assertFalse(result.pushed)
        assertFalse(Files.exists(checkout.resolve(".git/rebase-merge")) || Files.exists(checkout.resolve(".git/rebase-apply")), "the failed rebase was aborted")
        assertTrue(CargoRemote.git(checkout, "status", "--porcelain").isBlank(), "the checkout is clean and usable")
        assertFalse(r.sync(force = true).ok, "the unmergeable commit is reported, not hidden")
    }

    @Test
    fun `the union merge rule is set in the checkout, never in the repo`() {
        remote.packet("acme", "sdet"); remote.publish()
        val r = repo(); r.sync()
        assertTrue(Files.readString(checkout.resolve(".git/info/attributes")).contains("STATUS.md merge=union"))
        assertFalse(Files.exists(checkout.resolve(".gitattributes")), "the repo itself is not modified")
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `find matches company and role case-insensitively`() {
        remote.packet("apple", "tools-engineer", title = "Tools Engineer")
        remote.packet("apple", "sdet-t3e", title = "SDET T3E")
        remote.packet("becu", "sdet")
        remote.publish()
        val r = repo(); r.sync()
        assertEquals(2, r.find("Apple").size)
        assertEquals(listOf("applications/apple/sdet-t3e/v00"), r.find("apple", "t3e").map { it.folder })
        assertEquals(listOf("applications/becu/sdet/v00"), r.find("BECU").map { it.folder })
        assertEquals(emptyList(), r.find("nobody"))
    }

    @Test
    fun `reads are confined to the checkout`() {
        remote.packet("acme", "sdet"); remote.publish()
        val r = repo(); r.sync()
        for (bad in listOf("../remote.git/HEAD", "applications/../../x", ".git/config", ".git", "")) {
            assertFailsWith<CargoRepo.CargoException>(bad) { r.resolve(bad) }
        }
        assertFailsWith<CargoRepo.CargoException> { r.resolve("applications/acme/nope") }
        assertTrue(Files.isRegularFile(r.resolve("applications/acme/sdet/v00/job-posting.md")))
    }

    @Test
    fun `a symlink out of the checkout is refused`() {
        remote.packet("acme", "sdet"); remote.publish()
        val r = repo(); r.sync()
        val secret = Files.writeString(tmp.resolve("secret.txt"), "top secret")
        Files.createSymbolicLink(checkout.resolve("evil.txt"), secret)
        Files.createSymbolicLink(checkout.resolve("evildir"), tmp)
        assertFailsWith<CargoRepo.CargoException> { r.resolve("evil.txt") }
        assertFailsWith<CargoRepo.CargoException> { r.resolve("evildir/secret.txt") }
    }

    @Test
    fun `list hides git and marks folders`() {
        remote.packet("acme", "sdet"); remote.publish()
        val r = repo(); r.sync()
        assertEquals(listOf("applications/"), r.list(""))
        val files = r.list("applications/acme/sdet/v00")
        assertTrue("applications/acme/sdet/v00/APPLICATION_PACKET.md" in files, files.toString())
        assertTrue("applications/acme/sdet/v00/apply-2p/" in files, files.toString())
        assertFailsWith<CargoRepo.CargoException> { r.list("applications/acme/sdet/v00/job-posting.md") }
    }

    @Test
    fun `search finds text across files, skips git and binaries, and honours the limit`() {
        remote.packet("acme", "sdet", answers = "Sponsorship: No\nVisa: none\n")
        remote.packet("beta", "qa", answers = "SPONSORSHIP: Yes\n")
        remote.publish()
        val r = repo(); r.sync()
        val hits = r.search("sponsorship")
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.startsWith("applications/acme/sdet/v00/application-answers.md:1:") })
        assertEquals(1, r.search("sponsorship", limit = 1).size)
        assertEquals(emptyList(), r.search("Richard Hatcher resume text".replace("resume", "zzznotthere")))
        assertFailsWith<CargoRepo.CargoException> { r.search("  ") }
        assertTrue(r.search("%PDF").isEmpty(), "pdf bytes are not searched")
    }

    // ── credentials ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the token reaches git only through the environment of network commands, and is scrubbed from output`() {
        val tokenFile = Files.writeString(tmp.resolve("token"), "github_pat_SECRETVALUE123\n")
        val git = ProcessGit(tokenFile)
        val env = git.run(tmp, listOf("-c", "alias.showenv=!env", "showenv"), network = true)
        assertTrue(env.out.contains("GIT_CONFIG_VALUE_0=Authorization: Basic ***"), env.out)
        val b64 = java.util.Base64.getEncoder().encodeToString("x-access-token:github_pat_SECRETVALUE123".toByteArray())
        assertFalse(env.out.contains("SECRETVALUE123") || env.out.contains(b64), env.out)
        val local = git.run(tmp, listOf("-c", "alias.showenv=!env", "showenv"), network = false)
        assertFalse(local.out.contains("GIT_CONFIG_VALUE_0"), "local commands get no credentials")
    }

    @Test
    fun `the token is never written into the checkout`() {
        remote.packet("acme", "sdet"); remote.publish()
        val tokenFile = Files.writeString(tmp.resolve("token"), "github_pat_SECRETVALUE123")
        val r = repo(ProcessGit(tokenFile))
        r.sync()
        r.appendStatus("applications/acme/sdet/v00", "blocked", "x")
        val config = Files.readString(checkout.resolve(".git/config"))
        assertFalse(config.contains("SECRETVALUE123") || config.contains("extraheader", ignoreCase = true), config)
    }

    @Test
    fun `a missing token file is not an error for git itself`() {
        val git = ProcessGit(tmp.resolve("absent"))
        assertTrue(git.run(tmp, listOf("--version"), network = true).ok)
    }

    @Test
    fun `a hung git is killed`() {
        val git = ProcessGit(null, timeoutSeconds = 1)
        val r = git.run(tmp, listOf("-c", "alias.nap=!sleep 5", "nap"), network = false)
        assertEquals(124, r.code)
    }

    @Test
    fun `a text file that is not valid UTF-8 does not break search`() {
        remote.packet("acme", "sdet", answers = "Sponsorship: No\n"); remote.publish()
        remote.write("applications/acme/sdet/v00/notes.md", "caf\u00e9 sponsorship note")
        java.nio.file.Files.write(remote.seed.resolve("applications/acme/sdet/v00/latin1.txt"), "sponsorship caf\u00e9".toByteArray(Charsets.ISO_8859_1))
        remote.publish("latin1")
        val r = repo(); r.sync()
        val hits = r.search("sponsorship")
        assertTrue(hits.any { it.contains("application-answers.md") }, hits.toString())
        assertTrue(hits.any { it.contains("notes.md") }, "the readable files are still searched: $hits")
    }

    @Test
    fun `append still commits when GitHub cannot be reached, and says it could not sync`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        val r = repo(); r.sync()
        Files.move(remote.bare, tmp.resolve("gone.git"))
        val result = r.appendStatus(folder, "blocked", "offline")
        assertFalse(result.pushed)
        assertTrue(result.message.startsWith("committed locally"), result.message)
        assertTrue(result.message.contains("Couldn't sync first"), result.message)
        assertTrue(Files.readString(checkout.resolve("$folder/STATUS.md")).contains("| blocked | offline"))
        Files.move(tmp.resolve("gone.git"), remote.bare)
        assertTrue(r.sync(force = true).ok)
        assertTrue(Files.readString(remote.freshClone().resolve("$folder/STATUS.md")).contains("| blocked | offline"), "the next sync pushed it")
    }

    @Test
    fun `find tolerates spaces, hyphens and case in company and role`() {
        remote.packet("applab-systems", "mobile-app-testing-engineer", title = "Mobile App Testing Engineer"); remote.publish()
        val r = repo(); r.sync()
        assertEquals(1, r.find("Applab Systems").size)
        assertEquals(1, r.find("applab-systems", "mobile app").size)
        assertEquals(1, r.find("APPLAB", "TESTING").size)
        assertEquals(0, r.find("applab", "nurse").size)
    }

    @Test
    fun `every push in a busy sequence lands and history stays linear`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        val r = repo()
        repeat(6) { r.appendStatus(folder, "blocked", "line $it") }
        val verify = remote.freshClone()
        assertEquals(6, Files.readString(verify.resolve("$folder/STATUS.md")).lines().count { it.startsWith("- ") })
        assertEquals(0, CargoRemote.git(verify, "rev-list", "--merges", "--count", "HEAD").toInt(), "no merge commits")
        assertEquals(7, remote.log(verify).size)
    }

    @Test
    fun `a status line records the committer, not the seed identity`() {
        val folder = remote.packet("acme", "sdet"); remote.publish()
        repo().appendStatus(folder, "blocked", "x")
        assertEquals("JobBot", CargoRemote.git(remote.freshClone(), "log", "-1", "--format=%an"))
        assertEquals("jobbot@users.noreply.github.com", CargoRemote.git(remote.freshClone(), "log", "-1", "--format=%ae"))
    }

    // ── restoring a packet after a crashed attempt ───────────────────────────────────────────────

    private fun lastLine(folder: String) = Files.readString(remote.freshClone("v").resolve("$folder/STATUS.md")).trim().lines().last()

    @Test
    fun `a crashed attempt on a blocked packet puts blocked back, so Richard's override was one-time`() {
        val folder = remote.packet("acme", "sdet", statusLines = listOf(
            "- 2026-10-23T04:04:00Z | blocked | Richard decided not to apply",
            "- 2026-10-24T17:28:58Z | in_progress | draft approved: \"Apply anyway\" — opened the form",
        ))
        remote.publish()
        val r = repo(); r.sync(force = true)
        val done = r.restoreAfterInterruption(folder, "JobBot restarted mid-application; nothing was submitted")
        assertTrue(done!!.pushed, done.message)
        assertTrue(lastLine(folder).contains("| blocked | JobBot restarted mid-application; nothing was submitted"), lastLine(folder))
        assertEquals("blocked", r.packetAt(folder)!!.application)
    }

    @Test
    fun `a crashed attempt on an untouched packet makes it not_started again, so it can be retried at once`() {
        val folder = remote.packet("acme", "sdet", statusLines = listOf("- 2026-10-24T17:28:58Z | in_progress | opened the form"))
        remote.publish()
        val r = repo(); r.sync(force = true)
        assertTrue(r.restoreAfterInterruption(folder, "restarted")!!.pushed)
        assertTrue(lastLine(folder).contains("| not_started |"), lastLine(folder))
        assertEquals("not_started", r.packetAt(folder)!!.application)
    }

    @Test
    fun `the whole attempt is skipped back over, not just its last line`() {
        val folder = remote.packet("acme", "sdet", statusLines = listOf(
            "- 2026-10-20T10:00:00Z | blocked | needs a CAPTCHA",
            "- 2026-10-24T17:00:00Z | in_progress | opened the form",
            "- 2026-10-24T17:05:00Z | parked_at_review | filled; awaiting Submit",
        ))
        remote.publish()
        val r = repo(); r.sync(force = true)
        r.restoreAfterInterruption(folder, "restarted")
        assertTrue(lastLine(folder).contains("| blocked |"), lastLine(folder))
    }

    @Test
    fun `a packet that is not mid-attempt is left alone`() {
        for ((name, last) in listOf("done" to "completed", "stuck" to "blocked", "fresh" to null)) {
            val folder = remote.packet(name, "sdet", statusLines = listOfNotNull(last?.let { "- 2026-10-24T17:00:00Z | $it | whatever" }))
            remote.publish()
            val r = repo(); r.sync(force = true)
            assertEquals(null, r.restoreAfterInterruption(folder, "restarted"), "$name: nothing to restore")
        }
        val after = remote.freshClone("untouched")
        assertEquals(1, Files.readString(after.resolve("applications/done/sdet/v00/STATUS.md")).lines().count { it.startsWith("- ") })
    }
}
