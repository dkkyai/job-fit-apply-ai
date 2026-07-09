package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CareerEntry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused tests for [BulletRewriteNode.applyRewrites] — the fold step that matches
 * per-role LLM output back into the candidate's career history and produces the
 * reorder metadata. The LLM call itself is not exercised here.
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
            roleRewrite("Senior SDET", "Acme", "2022-01", listOf("Shipped Z" to "Drove Z to GA in 3 months")),
            roleRewrite("Staff SDET", "Acme", "2024-09", listOf("Built X" to "Architected X", "Owned Y" to "Led Y"))
        )

        val result = node.applyRewrites(originals, rewrites)

        assertEquals(2, result.entries.size)
        assertEquals(listOf("Architected X", "Led Y"), result.entries[0].bullets.map { it.text })
        assertEquals(listOf("Drove Z to GA in 3 months"), result.entries[1].bullets.map { it.text })
        // All non-bullet fields preserved verbatim
        assertEquals("Staff SDET", result.entries[0].role)
        assertEquals("Acme", result.entries[0].company)
        assertEquals("2024-09", result.entries[0].startDate)
        // Flat diagnostic list contains every successful rewrite
        assertEquals(3, result.diagnostics.size)
    }

    @Test
    @DisplayName("Roles missing from the LLM response keep their original bullets and get fallback meta")
    fun preservesOriginalsForMissingRoles() {
        val originals = listOf(
            entry("A", "Co1", "2020-01", listOf("Original 1")),
            entry("B", "Co2", "2018-06", listOf("Cut costs by 30%"))
        )
        val rewrites = listOf(
            roleRewrite("A", "Co1", "2020-01", listOf("Original 1" to "Rewritten 1"))
        )

        val result = node.applyRewrites(originals, rewrites)

        assertEquals(listOf("Rewritten 1"), result.entries[0].bullets.map { it.text })
        assertEquals(listOf("Cut costs by 30%"), result.entries[1].bullets.map { it.text },
            "missing-from-LLM role keeps its original bullets")
        // Fallback meta still exists for the skipped role — quantified detected from digits.
        val meta = result.meta[roleKey("B", "Co2", "2018-06")]
        checkNotNull(meta)
        assertEquals(0, meta[0].mustHaveHits)
        assertTrue(meta[0].quantified, "digit scan marks the original bullet quantified")
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

        val result = node.applyRewrites(originals, rewrites)

        assertEquals(
            listOf("Rewritten 1", "Original 2", "Original 3"),
            result.entries[0].bullets.map { it.text },
            "blank rewrites and missing trailing rewrites should keep the original at that index"
        )
        assertEquals(3, result.meta.getValue(roleKey("A", "Co1", "2020-01")).size,
            "meta stays index-aligned with the merged bullets")
    }

    @Test
    @DisplayName("Category re-labelled from the LLM, falling back to the original; meta carries the flags")
    fun rewritesCategoryAndMeta() {
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
                    BulletRewriteNode.RewrittenBullet(
                        original = "Original 1", category = "New Label", rewritten = "Rewritten 1",
                        mustHaveHits = listOf("Kotlin", "Espresso"), quantified = true, senioritySignal = true
                    ),
                    BulletRewriteNode.RewrittenBullet(original = "Original 2", category = "", rewritten = "Rewritten 2")
                )
            )
        )

        val result = node.applyRewrites(originals, rewrites)

        assertEquals("New Label", result.entries[0].bullets[0].category, "category re-labelled from LLM")
        assertEquals("Keep Me", result.entries[0].bullets[1].category, "blank category falls back to original")

        val meta = result.meta.getValue(roleKey("A", "Co1", "2020-01"))
        assertEquals(2, meta[0].mustHaveHits)
        assertTrue(meta[0].quantified)
        assertTrue(meta[0].senioritySignal)
        assertEquals(0, meta[1].mustHaveHits)
        assertFalse(meta[1].senioritySignal)

        assertEquals(listOf("Kotlin", "Espresso"), result.diagnostics[0].mustHaveHits)
    }

    @Test
    @DisplayName("verifyTerms recomputes hits and quantified from the rewritten text, keeping the LLM's seniority call")
    fun verifiesMetadataDeterministically() {
        val originals = listOf(entry("A", "Co", "2020-01", listOf("Original")))
        val rewrites = listOf(
            BulletRewriteNode.RoleRewrite(
                role = "A", company = "Co", startDate = "2020-01",
                bullets = listOf(
                    BulletRewriteNode.RewrittenBullet(
                        original = "Original",
                        rewritten = "Owned Espresso and GitHub Actions pipelines",
                        mustHaveHits = listOf("Kotlin"),   // claimed but not present in the text
                        quantified = true,                  // claimed but no digits
                        senioritySignal = true
                    )
                )
            )
        )

        val result = node.applyRewrites(originals, rewrites, verifyTerms = listOf("Kotlin", "Espresso", "GitHub Actions"))

        val meta = result.meta.getValue(roleKey("A", "Co", "2020-01"))
        assertEquals(2, meta[0].mustHaveHits, "Espresso + GitHub Actions literally present; claimed Kotlin absent")
        assertFalse(meta[0].quantified, "no digits in the rewritten text")
        assertTrue(meta[0].senioritySignal, "seniority judgment is not verifiable in code — LLM's call kept")
        assertEquals(listOf("Espresso", "GitHub Actions"), result.diagnostics[0].mustHaveHits)
    }

    @Test
    @DisplayName("parseRoleRewrites tolerates a bare array, an object-wrapped array, and a single object")
    fun parseRoleRewritesTolerant() {
        val bullets = """[{"original":"o","category":"c","rewritten":"r"}]"""
        val role = """{"role":"SDET","company":"Acme","start_date":"2020-01","bullets":$bullets}"""

        // 1) bare array (the happy path)
        assertEquals(listOf("SDET"), node.parseRoleRewrites("[$role]").map { it.role })
        // 2) local models often wrap the array under a key
        assertEquals(listOf("SDET"), node.parseRoleRewrites("""{"roles":[$role]}""").map { it.role })
        // 3) or return a single role object, not a list
        assertEquals(listOf("SDET"), node.parseRoleRewrites(role).map { it.role })
    }

    @Test
    @DisplayName("parseRoleRewrites throws on a non-array, non-role JSON scalar")
    fun parseRoleRewritesRejectsGarbage() {
        val ex = kotlin.runCatching { node.parseRoleRewrites("\"just a string\"") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
    }

    @Test
    @DisplayName("Match keys are case-insensitive for role and company")
    fun matchKeysAreCaseInsensitive() {
        val originals = listOf(entry("Staff SDET", "Acme Corp", "2024-09", listOf("Original")))
        val rewrites = listOf(roleRewrite("staff sdet", "acme corp", "2024-09", listOf("Original" to "Rewritten")))

        val result = node.applyRewrites(originals, rewrites)

        assertEquals(listOf("Rewritten"), result.entries[0].bullets.map { it.text })
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
            BulletRewriteNode.RewrittenBullet(original = original, rewritten = rewritten)
        }
    )
}
