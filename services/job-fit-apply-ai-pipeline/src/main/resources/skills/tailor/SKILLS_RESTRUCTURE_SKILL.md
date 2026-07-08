# SKILLS_RESTRUCTURE_SKILL — JD-relevance-ordered skills section

Restructure the candidate's skills section for the target JD. ATS parsers read this
section early and weigh it heavily, and a recruiter skims it in one glance — it must be
ruthless: JD-relevant, grouped under the JD's own labels, nothing the candidate would
hesitate to be interviewed on.

Return ONLY valid JSON — no markdown fences, no preamble. `grouped_by_category` key order
IS the render order:

```
{
  "grouped_by_category": {
    "most JD-relevant category label": ["skill", "skill", ...],
    "next category label": ["skill", ...]
  },
  "jd_matched_skills": ["skills present in both the JD and the candidate's list", ...],
  "removed_for_this_role": ["candidate skills you dropped as irrelevant for this JD", ...]
}
```

Rules (non-negotiable):

1. **Source pool only.** Every skill in your output comes from CANDIDATE SKILLS BY GROUP.
   Never add a skill — anything on the DO-NOT-ADD list is forbidden.
2. **Drop irrelevant skills.** Resume skills with no relevance to this JD (legacy domain
   terms, tools from unrelated stacks) go in `removed_for_this_role` and do NOT appear in
   `grouped_by_category`. Cutting noise is the point — a leaner, JD-focused section beats
   a complete inventory.
3. **Verbatim must-haves.** Every SUPPORTED term that is a skill/tool appears in
   `grouped_by_category` using the JD's exact phrasing. Same skill, different spelling →
   use the JD's form ("GH Actions" → "GitHub Actions", "Postgres" → "PostgreSQL"). Never
   substitute a different tool to fake a match ("Selenium" is not "Playwright").
4. **Mirror the JD's groupings.** Where the JD provides its own skill grouping labels, use
   them for your category labels. Where it doesn't, use standard logical categories
   (Languages, Testing & Automation, CI/CD, Cloud & Infrastructure, Observability,
   Leadership).
5. **Order by JD relevance.** The category answering the JD's top requirements comes
   first; the highest-priority skills lead within it. JD-matched skills lead every group.
6. **Acronym + expansion** for key terms once across the section: "Kotlin Multiplatform
   (KMP)".
7. **Scannable.** 3–7 categories; no duplicates across categories; plain text skill names
   only.

If a `PREVIOUS VALIDATION FEEDBACK` block is present, this is a revision pass: add the
listed missing supported terms (verbatim) to the right category, and remove any leaked
unsupported terms.
