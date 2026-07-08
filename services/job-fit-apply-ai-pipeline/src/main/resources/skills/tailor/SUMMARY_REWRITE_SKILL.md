# SUMMARY_REWRITE_SKILL — JD-framed professional summary

Write the candidate's professional summary for the target role. The recruiter reads this
first, the ATS parses it early, and the hiring manager checks its claims — it must serve
all three.

Output: ONLY the summary text. Plain text, no JSON, no markdown, no preamble, no labels.

Shape (3–4 lines, dense, every word earns its place):

1. **Line 1 frames to the TARGET TITLE** provided in the input — the JD's own title wording —
   plus years of experience and domain. Example skeleton:
   "Staff Software Development Engineer in Test with 15+ years building mobile test
   infrastructure at consumer scale."
2. **Lines 2–3 weave in the top supported must-have terms** using the JD's exact phrasing.
   Prioritise anything listed under PULL FORWARD FIRST — those are must-haves the current
   resume under-surfaces.
3. **Include 1–2 quantified proof points** pulled from the candidate profile's real metrics
   (team size led, coverage %, PR execution time, apps/teams served). Numbers must come
   from the profile verbatim — never invent or sharpen them.
4. Mirror the JD's seniority signals ("set standards", "cross-team") where the evidence
   supports them.

Bans (hard rules):

- No filler adjectives: "passionate", "hardworking", "adept at", "results-driven",
  "seasoned", "dynamic".
- No first person ("I", "my"), no third person ("he", "she", "they").
- Do NOT reuse sentences from the CURRENT SUMMARY — it is a fact source only. Write fresh
  sentences framed to THIS job.
- The source summary may contain grammar artifacts (e.g. a doubled word like "reducing
  reducing") — never reproduce doubled words or grammar errors.
- Never mention a term from the DO-NOT-CLAIM list.
- Acronym + expansion on first use for key terms: "Kotlin Multiplatform (KMP)".

If a PREVIOUS VALIDATION FEEDBACK block is present, this is a revision pass: work the
listed missing supported terms into the summary where truthful, and remove any leaked
unsupported terms.
