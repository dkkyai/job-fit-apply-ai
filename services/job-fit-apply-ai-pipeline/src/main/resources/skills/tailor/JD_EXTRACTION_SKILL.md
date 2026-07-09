# JD_EXTRACTION_SKILL — JD → structured requirement set

Parse the job description (inside `<job_description>` tags) into the structured requirement
set every downstream tailoring node consumes. This extraction is the ONLY read of the raw
JD — be complete, and quote the JD's exact wording, because ATS matching is literal.

Return ONLY valid JSON with no markdown fences, no preamble:

```
{
  "target_title": "the JD's job title, verbatim (not paraphrased)",
  "seniority_signals": ["phrases signalling level, quoted from the JD", ...],
  "must_have": ["one hard requirement per entry, in the JD's exact phrasing", ...],
  "nice_to_have": ["preferred / 'plus' / 'bonus' items, exact phrasing", ...],
  "exact_match_terms": ["tools, certifications, protocols, platforms named in the JD", ...],
  "skill_groupings": [
    {"label": "the JD's own skill grouping or section label", "items": ["skills it lists", ...]}
  ],
  "domain_keywords": ["industry/domain vocabulary", ...],
  "company_value_signals": ["culture and values clues", ...]
}
```

Field rules:

- **target_title** — copy the title exactly as posted ("Staff Software Development Engineer
  in Test", not "Staff SDET") so the summary can mirror it.
- **seniority_signals** — quote the phrases that reveal expected scope: "set standards",
  "mentor senior engineers", "cross-team", "drive strategy", "influence without authority".
- **must_have** — requirements stated as required/must/minimum qualifications. Keep the JD's
  exact phrasing per entry, 1–6 words each where possible ("cross-functional collaboration",
  "Kotlin", "CI/CD pipeline ownership"). These are the ATS match targets — precision matters.
- **nice_to_have** — "preferred", "nice to have", "a plus" items, exact phrasing.
- **exact_match_terms** — the subset of terms that ATS parsers match literally: named tools,
  frameworks, cloud platforms, certifications, standards/protocols (e.g. "Espresso",
  "GitHub Actions", "Firebase Test Lab", "gRPC", "Pact"). May overlap must_have/nice_to_have.
  Keep each entry ATOMIC — one tool/technology per entry. Split compound listings the JD
  writes as one phrase ("Selenium/Appium" → "Selenium", "Appium"; "Java or Kotlin" →
  "Java", "Kotlin"). These entries are the coverage denominators downstream — a fused
  entry can never be matched.
- **skill_groupings** — if the JD groups its requirements under labels (e.g. "Testing",
  "Cloud & Infrastructure", "Leadership"), reproduce those labels and their items; the
  skills-restructure node mirrors them. Empty array if the JD has no groupings.
- **domain_keywords** — industry vocabulary worth weaving into bullets where truthful
  ("device farms", "flakiness dashboards", "quality gates").
- **company_value_signals** — culture clues ("move fast", "data-driven quality",
  "ownership mentality").

Hard rules:

1. Never invent a term that is not in the JD.
2. Never paraphrase — quote. "End-to-end test automation" stays exactly that.
3. A term may appear in more than one list when it genuinely belongs to both.
4. Emit an empty array for any field the JD does not provide.
