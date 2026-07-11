# BULLET_REWRITE_SKILL — per-role bullet rewrite with reorder metadata

Rewrite the bullets of every role in the `CANDIDATE ROLES` JSON array to align with the
target JD while preserving all factual content. Each input bullet is `{ category, text }`
— a short bold label plus the accomplishment text. You rewrite both.

Return ONLY a valid JSON array — one element per input role, in the same order, no
markdown fences, no preamble:

```
[
  {
    "role": "exact role title from input",
    "company": "exact company from input",
    "start_date": "exact start_date from input",
    "bullets": [
      {
        "original": "verbatim original bullet text",
        "category": "short bold label (2–4 words), re-aligned to the JD's terminology where truthful",
        "rewritten": "rewritten ATS-aligned bullet",
        "must_have_hits": ["every MUST-HAVE term this rewritten bullet covers, copied exactly from the input list"],
        "quantified": true,
        "seniority_signal": true
      }
    ]
  }
]
```

Rewrite rules (non-negotiable):

1. **Join keys.** Echo `role`, `company`, `start_date` verbatim from the input. Do not
   reorder roles or merge bullets — a deterministic step reorders bullets later using your
   metadata.
2. **Index alignment.** One rewritten bullet per original bullet, in the same order:
   `bullets[i]` in your output corresponds to `bullets[i]` in the input role.
3. **Bullet formula.** Strong past-tense action verb → what was built/done → tool or
   method → quantified result → scope. Fragment (no "I"), one idea per bullet. If the
   original ends with a measurable outcome, keep the outcome in the final clause where
   recruiters look for impact. **Max ~30 words / 2 rendered lines per bullet** — longer
   means two ideas; keep the stronger one. Vary the structure occasionally so the resume
   doesn't read as one template repeated; the same opening verb may lead at most 2 bullets
   across the whole output.
4. **Preserve quantification.** Every number, percentage, dollar amount, timeframe, and
   scale indicator from the original appears in the rewrite unchanged. Keep hedges
   ("achieving 90% coverage") — do not sharpen approximations into new precise figures.
5. **No fabrication.** Never add tools, skills, scope, metrics, or outcomes that are not
   in the original bullet or the SUPPORTED TERMS evidence. Anything on the DO-NOT-CLAIM
   list is forbidden.
6. **Mirror JD phrasing — same concept only.** When the original and the JD describe the
   same work in different words, use the JD's wording ("UI test suites" → "end-to-end test
   automation"). Never substitute a different tool to match the JD.
7. **Staff-vs-senior test.** If a bullet could appear unchanged on a mid-level SDET
   resume, rewrite it to surface ownership, cross-team adoption, standards set, or
   downstream impact — evidence permitting. If the source genuinely cannot support that,
   leave it plain and set `seniority_signal` to false; the reorder step will demote it.
8. **Category labels are skim signal.** They render as the bullet's bold lead-in. Keep
   them 2–4 words and re-label to echo the JD's language where truthful (JD stresses
   "architecture" → "Framework Architecture"). Otherwise echo the original category.
9. **Acronym + expansion on first use** across the whole output ("Kotlin Multiplatform
   (KMP)"), then the short form is fine.
10. **ATS-safe text.** Plain text only — no markdown, no special bullet characters, no
    smart quotes.

Metadata rules (feed the deterministic reorder — be accurate, not generous):

- `must_have_hits`: copy the exact strings from the MUST-HAVE TERMS input list that this
  rewritten bullet genuinely covers. Empty array when none. These are RE-VERIFIED in code
  by literal word-boundary matching — a hit only counts when the term's exact wording
  appears in the rewritten text, so place the JD's exact term in the bullet (where
  truthful), not a paraphrase of it.
- `quantified`: true only when the rewritten bullet contains a real number/percentage/
  scale from the source (also re-verified in code by digit scan).
- `seniority_signal`: true only when the bullet shows Staff-level scope — ownership,
  cross-team influence, standards set, engineers enabled/mentored.

If a `PREVIOUS VALIDATION FEEDBACK` block is present, this is a revision pass: work the
listed missing supported terms into the strongest truthful bullets, and remove any leaked
unsupported terms.
