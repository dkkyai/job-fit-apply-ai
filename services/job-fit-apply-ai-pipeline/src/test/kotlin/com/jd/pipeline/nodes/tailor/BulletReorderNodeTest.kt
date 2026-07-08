package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CareerEntry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the deterministic [BulletReorderNode] — pure logic, no LLM.
 */
@DisplayName("BulletReorderNodeTest")
class BulletReorderNodeTest {

    private fun entry(vararg bulletTexts: String, role: String = "SDET", company: String = "Acme", start: String = "2020-01") =
        CareerEntry(role = role, company = company, startDate = start, bullets = bulletTexts.map { Bullet("Cat", it) })

    private fun state(career: List<CareerEntry>, meta: Map<String, List<BulletMeta>>, projects: List<CareerEntry> = emptyList()) =
        TailorState(
            jdText = "jd", company = "Acme", roleTitle = "SDET",
            tailoredCareerHistory = career, tailoredProjects = projects, bulletMeta = meta
        )

    @Test
    @DisplayName("bullets sort by descending score within a role; role order untouched")
    fun sortsWithinRole() {
        val e = entry("weak", "strong", "middle")
        val meta = mapOf(
            roleKey("SDET", "Acme", "2020-01") to listOf(
                BulletMeta(0, quantified = false, senioritySignal = false),  // weak   → 0
                BulletMeta(2, quantified = true, senioritySignal = true),    // strong → 11
                BulletMeta(1, quantified = false, senioritySignal = false)   // middle → 4
            )
        )
        val node = BulletReorderNode(capEnabled = false, capRecent = 6, capOlder = 3)

        val result = node.process(state(listOf(e), meta))

        assertEquals(listOf("strong", "middle", "weak"),
            result.tailoredCareerHistory!![0].bullets.map { it.text })
        assertTrue(result.error.isEmpty())
    }

    @Test
    @DisplayName("stable sort: equal-scoring bullets keep the rewrite's order")
    fun stableSort() {
        val e = entry("first", "second", "third")
        val meta = mapOf(
            roleKey("SDET", "Acme", "2020-01") to listOf(
                BulletMeta(1), BulletMeta(1), BulletMeta(1)
            )
        )
        val node = BulletReorderNode(capEnabled = false, capRecent = 6, capOlder = 3)
        val result = node.process(state(listOf(e), meta))
        assertEquals(listOf("first", "second", "third"), result.tailoredCareerHistory!![0].bullets.map { it.text })
    }

    @Test
    @DisplayName("caps: most recent role keeps capRecent bullets, older roles capOlder — lowest-scoring dropped")
    fun capsApply() {
        val recent = entry("r1", "r2", "r3", role = "Staff SDET", start = "2024-01")
        val older = entry("o1", "o2", "o3", role = "SDET", start = "2018-01")
        val meta = mapOf(
            roleKey("Staff SDET", "Acme", "2024-01") to listOf(BulletMeta(3), BulletMeta(2), BulletMeta(1)),
            roleKey("SDET", "Acme", "2018-01") to listOf(BulletMeta(1), BulletMeta(3), BulletMeta(2))
        )
        val node = BulletReorderNode(capEnabled = true, capRecent = 2, capOlder = 1)

        val result = node.process(state(listOf(recent, older), meta))

        assertEquals(listOf("r1", "r2"), result.tailoredCareerHistory!![0].bullets.map { it.text },
            "recent role capped at 2, lowest dropped")
        assertEquals(listOf("o2"), result.tailoredCareerHistory!![1].bullets.map { it.text },
            "older role capped at 1, keeps its highest-scoring bullet")
    }

    @Test
    @DisplayName("projects always use the older-role cap")
    fun projectsUseOlderCap() {
        val project = entry("p1", "p2", role = "Maintainer", company = "OSS", start = "2022-01")
        val meta = mapOf(roleKey("Maintainer", "OSS", "2022-01") to listOf(BulletMeta(2), BulletMeta(1)))
        val node = BulletReorderNode(capEnabled = true, capRecent = 6, capOlder = 1)

        val result = node.process(state(listOf(entry("c1")), mapOf(roleKey("SDET", "Acme", "2020-01") to listOf(BulletMeta(0))), listOf(project)))

        assertEquals(listOf("p1"), result.tailoredProjects!![0].bullets.map { it.text })
    }

    @Test
    @DisplayName("a role without meta (LLM skipped it) keeps its original bullet order, but caps still apply")
    fun missingMetaKeepsOrder() {
        val e = entry("a", "b", "c")
        val node = BulletReorderNode(capEnabled = true, capRecent = 2, capOlder = 2)
        val result = node.process(state(listOf(e), meta = emptyMap()))
        assertEquals(listOf("a", "b"), result.tailoredCareerHistory!![0].bullets.map { it.text })
    }

    @Test
    @DisplayName("guards: null career history or meta produce named errors")
    fun guards() {
        val node = BulletReorderNode()
        val base = TailorState(jdText = "jd", company = "Acme", roleTitle = "SDET")
        assertTrue(node.process(base).error.contains("tailoredCareerHistory"))
        assertTrue(node.process(base.copy(tailoredCareerHistory = emptyList())).error.contains("bulletMeta"))
    }
}
