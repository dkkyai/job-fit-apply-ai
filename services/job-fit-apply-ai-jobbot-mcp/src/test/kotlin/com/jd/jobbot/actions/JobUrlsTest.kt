package com.jd.jobbot.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JobUrlsTest {
    private fun ok(raw: String?) = assertIs<JobUrls.Check.Ok>(JobUrls.check(raw))
    private fun rejected(raw: String?) = assertIs<JobUrls.Check.Rejected>(JobUrls.check(raw)).reason

    @Test
    fun `a public job link is accepted and its host reported`() {
        val c = ok("https://jobs.lever.co/acme/123")
        assertEquals("https://jobs.lever.co/acme/123", c.url)
        assertEquals("jobs.lever.co", c.host)
    }

    @Test
    fun `http links and ports are kept`() {
        assertEquals("http://careers.acme.com:8080/jobs/9?id=4", ok("http://careers.acme.com:8080/jobs/9?id=4").url)
    }

    @Test
    fun `the fragment is dropped and surrounding space trimmed`() {
        assertEquals("https://acme.com/jobs/9?gh_jid=77", ok("  https://acme.com/jobs/9?gh_jid=77#apply \n").url)
    }

    @Test
    fun `hosts are lowercased`() {
        assertEquals("acme.com", ok("https://ACME.com/Jobs").host)
    }

    @Test
    fun `blank and missing links are refused`() {
        assertEquals("There's no job link.", rejected(null))
        assertEquals("There's no job link.", rejected("   "))
    }

    @Test
    fun `only http and https are accepted`() {
        listOf("ftp://acme.com/x", "file:///etc/passwd", "javascript:alert(1)", "data:text/html,hi", "mailto:a@b.co").forEach {
            assertEquals("Only http and https job links work.", rejected(it), it)
        }
    }

    @Test
    fun `text that is not a link is refused`() {
        assertEquals("That doesn't look like a link.", rejected("acme.com/jobs"))
        assertEquals("That doesn't look like a link.", rejected("https://"))
        assertEquals("That doesn't look like a link.", rejected("https://exa mple.com"))
    }

    @Test
    fun `links with credentials are refused`() {
        assertEquals("Links with a username or password in them aren't allowed.", rejected("https://user:pw@acme.com/jobs"))
        assertEquals("Links with a username or password in them aren't allowed.", rejected("https://acme.com@evil.example/jobs"))
    }

    @Test
    fun `addresses on private networks and the host are refused`() {
        listOf(
            "http://localhost/jobs", "http://localhost:8080/", "http://127.0.0.1/x", "http://10.0.0.5/x", "http://192.168.1.1/x",
            "http://169.254.169.254/latest/meta-data", "http://[::1]/x", "http://[fe80::1]/x",
            "http://jobbot-mcp:8790/health", "http://postgres/x", "http://host.docker.internal/x",
            "https://printer.local/x", "https://nas.lan/x", "https://svc.internal/x", "https://a.localhost/x",
        ).forEach { assertEquals(true, JobUrls.check(it) is JobUrls.Check.Rejected, it) }
    }

    @Test
    fun `a trailing dot on the host does not slip past the checks`() {
        assertEquals(true, JobUrls.check("http://localhost./x") is JobUrls.Check.Rejected)
    }

    @Test
    fun `an overlong link is refused`() {
        assertEquals("That link is too long.", rejected("https://acme.com/" + "a".repeat(2100)))
    }

    @Test
    fun `shorthand and numeric forms of an IP address are refused too`() {
        // Resolvers expand these to 127.0.0.1 / 10.0.0.1 even though they are not dotted quads.
        listOf(
            "http://127.1/x", "http://10.1/x", "http://192.168.1/x", "http://0177.0.0.1/x", "http://0x7f.0.0.1/x",
            "http://0x7f.1/x", "http://1.2.3/x", "http://2130706433/x", "http://0x7f000001/x", "http://017700000001/x",
        ).forEach { assertEquals(true, JobUrls.check(it) is JobUrls.Check.Rejected, it) }
    }

    @Test
    fun `an IPv4-mapped IPv6 literal is refused`() {
        assertEquals(true, JobUrls.check("http://[::ffff:127.0.0.1]/x") is JobUrls.Check.Rejected)
        assertEquals(true, JobUrls.check("http://[2001:db8::1]/x") is JobUrls.Check.Rejected, "no IPv6 literal is a job site")
    }

    @Test
    fun `a private suffix is refused even with a trailing dot or capitals`() {
        listOf("https://printer.local./x", "https://NAS.LAN/x", "https://Svc.Internal./x", "https://a.b.localhost/x", "https://host.home.arpa/x")
            .forEach { assertEquals(true, JobUrls.check(it) is JobUrls.Check.Rejected, it) }
    }

    @Test
    fun `real job-board links of every shape are accepted`() {
        listOf(
            "https://job-boards.greenhouse.io/andurilindustries/jobs/5133683007?gh_jid=5133683007",
            "https://www1.jobdiva.com/portal/?a=1zjdnwmtd2sucfnvihd8ikgy20037z08916f7t4ybtbv06620instb5vc66z248q&jr_id=6abd367b0e027c0f3b394190#/jobs/32569645",
            "https://becu.wd1.myworkdayjobs.com/external/job/Remote-WA/Sr-Software-Developer-Engineer-in-Test--SDET-_R-13930-1?jr_id=6ad7c3443dc655658d8efcf5",
            "https://jobs.apple.com/en-us/details/200679096-3337/software-engineer-in-test-system-experience-t3e?team=SFTWR&jr_id=6a879c21e8b6601d12904438",
            "https://www.monster.com/job-openings/senior-software-development-engineer-in-test-issaquah-wa--da6cae3b-88ea-460f-9a7a-51cea14bd62e",
            "HTTPS://Jobs.Example.COM/Path", "https://xn--bcher-kva.example/jobs", "https://jobs.example.co.uk:8443/a%20b?q=1+2",
        ).forEach { assertEquals(true, JobUrls.check(it) is JobUrls.Check.Ok, it) }
    }

    @Test
    fun `an encoded query survives cleaning unchanged`() {
        assertEquals("https://jobs.example.com/x?a=b%26c&d=e%20f", assertIs<JobUrls.Check.Ok>(JobUrls.check("https://jobs.example.com/x?a=b%26c&d=e%20f#frag")).url)
    }
}
