package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CareerEntry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Focused tests for [BulletRewriteNode.applyRewrites] — the fold step that
 * matches per-role LLM output back into the candidate's career history. The
 * LLM call itself is not exercised here.
 */
@DisplayName("BulletRewriteNodeApplyTest")
class BulletRewriteNodeApplyTest {

    private val node = BulletRewriteNode()

    @Test
    @DisplayName("Rewrites land on the right roles even when the LLM returns them out of order")
    fun matchesByRoleCompanyStartDate() {
        val originals = listOf(
            entry("Staff SDET", "Acme", "2024-09", listOf("Built X", "Owned Y")),
            entry("Senior SDET", "Acme", "2022-01", listOf("Shipped Z"))
        )

        // LLM returns role #2 before role #1
        val rewrites = listOf(
            roleRewrite(
                "Senior SDET", "Acme", "2022-01",
                listOf("Shipped Z" to "Drove Z to GA in 3 months")
            ),
            roleRewrite(
                "Staff SDET", "Acme", "2024-09",
                listOf("Built X" to "Architected X", "Owned Y" to "Led Y")
            )
        )

        val (tailored, flat) = node.applyRewrites(originals, rewrites)

        assertEquals(2, tailored.size)
        assertEquals(listOf("Architected X", "Led Y"), tailored[0].bullets.map { it.text })
        assertEquals(listOf("Drove Z to GA in 3 months"), tailored[1].bullets.map { it.text })
        // All non-bullet fields preserved verbatim
        assertEquals("Staff SDET", tailored[0].role)
        assertEquals("Acme", tailored[0].company)
        assertEquals("2024-09", tailored[0].startDate)
        // Flat diagnostic list contains every successful rewrite
        assertEquals(3, flat.size)
    }

    @Test
    @DisplayName("Roles missing from the LLM response keep their original bullets")
    fun preservesOriginalsForMissingRoles() {
        val originals = listOf(
            entry("A", "Co1", "2020-01", listOf("Original 1")),
            entry("B", "Co2", "2018-06", listOf("Original 2"))
        )
        val rewrites = listOf(
            roleRewrite("A", "Co1", "2020-01", listOf("Original 1" to "Rewritten 1"))
        )

        val (tailored, _) = node.applyRewrites(originals, rewrites)

        assertEquals(listOf("Rewritten 1"), tailored[0].bullets.map { it.text })
        assertEquals(listOf("Original 2"), tailored[1].bullets.map { it.text }, "missing-from-LLM role keeps its original bullets")
    }

    @Test
    @DisplayName("Bullets missing from a role's response keep the original at that index")
    fun preservesIndividualMissingBullets() {
        val originals = listOf(
            entry("A", "Co1", "2020-01", listOf("Original 1", "Original 2", "Original 3"))
        )
        // LLM only returns 2 of the 3 bullets, and the second one is blank
        val rewrites = listOf(
            roleRewrite("A", "Co1", "2020-01", listOf(
                "Original 1" to "Rewritten 1",
                "Original 2" to ""
            ))
        )

        val (tailored, _) = node.applyRewrites(originals, rewrites)

        assertEquals(
            listOf("Rewritten 1", "Original 2", "Original 3"),
            tailored[0].bullets.map { it.text },
            "blank rewrites and missing trailing rewrites should keep the original at that index"
        )
    }

    @Test
    @DisplayName("Category is re-labelled from the LLM output, falling back to the original")
    fun rewritesCategoryLabel() {
        val originals = listOf(
            CareerEntry(
                role = "A", company = "Co1", location = "", startDate = "2020-01", endDate = null,
                bullets = listOf(Bullet("Old Label", "Original 1"), Bullet("Keep Me", "Original 2"))
            )
        )
        val rewrites = listOf(
            BulletRewriteNode.RoleRewrite(
                role = "A", company = "Co1", startDate = "2020-01",
                bullets = listOf(
                    BulletRewriteNode.RewrittenBullet(original = "Original 1", category = "New Label", rewritten = "Rewritten 1", jdAlignmentScore = 90),
                    BulletRewriteNode.RewrittenBullet(original = "Original 2", category = "", rewritten = "Rewritten 2", jdAlignmentScore = 70)
                )
            )
        )

        val (tailored, _) = node.applyRewrites(originals, rewrites)

        assertEquals("New Label", tailored[0].bullets[0].category, "category re-labelled from LLM")
        assertEquals("Keep Me", tailored[0].bullets[1].category, "blank category falls back to original")
    }

    @Test
    @DisplayName("Match keys are case-insensitive for role and company")
    fun matchKeysAreCaseInsensitive() {
        val originals = listOf(entry("Staff SDET", "Acme Corp", "2024-09", listOf("Original")))
        val rewrites = listOf(roleRewrite("staff sdet", "acme corp", "2024-09",
            listOf("Original" to "Rewritten")))

        val (tailored, _) = node.applyRewrites(originals, rewrites)

        assertEquals(listOf("Rewritten"), tailored[0].bullets.map { it.text })
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun entry(role: String, company: String, startDate: String, bulletTexts: List<String>) =
        CareerEntry(
            role = role,
            company = company,
            location = "",
            startDate = startDate,
            endDate = null,
            bullets = bulletTexts.map { Bullet("", it) }
        )

    private fun roleRewrite(
        role: String,
        company: String,
        startDate: String,
        bullets: List<Pair<String, String>>
    ) = BulletRewriteNode.RoleRewrite(
        role = role,
        company = company,
        startDate = startDate,
        bullets = bullets.map { (original, rewritten) ->
            BulletRewriteNode.RewrittenBullet(original = original, rewritten = rewritten, jdAlignmentScore = 80)
        }
    )
}
