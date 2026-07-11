# GAP_ANALYSIS_SKILL — JD terms → the "safe to claim" partition

Cross-reference every JD requirement (must-have, nice-to-have, exact-match term) against
the candidate profile and partition them into what may — and may not — be emphasised
downstream. Your output is the pipeline's integrity gate: nodes that rewrite the summary,
bullets, and skills may only surface terms YOU mark as supported.

Return ONLY valid JSON — no markdown fences, no preamble:

```
{
  "supported": [
    {"term": "JD term, exact phrasing", "evidence": "text quoted from the candidate profile that proves it"}
  ],
  "unsupported": ["JD term the profile shows NO evidence for", ...],
  "missing_but_supported": [
    {"term": "must-have the candidate clearly has but the resume does not surface prominently",
     "evidence": "text quoted from the candidate profile"}
  ]
}
```

Classification rules:

1. **supported** — the profile contains real, quotable evidence for the term. Copy the JD's
   exact phrasing into `term` and quote the strongest single piece of profile text into
   `evidence`. Direct tool matches count ("Espresso" in a bullet); genuinely equivalent
   phrasing counts ("built CI/CD pipelines on Bitrise" supports "CI/CD pipeline ownership").
   The profile's "Additional Verified Evidence" section (candidate-curated facts not on the
   résumé) is a first-class evidence source — quote from it exactly like résumé text.
2. **unsupported** — no evidence anywhere in the profile. Be strict: adjacent experience is
   NOT evidence ("Selenium" does not support "Playwright"; "AWS" does not support "GCP").
   Downstream nodes are forbidden from using these terms, and a deterministic validator
   scans the final output for leaks — a wrong call here surfaces as an integrity failure.
3. **missing_but_supported** — the subset of MUST-HAVE terms that have clear evidence but
   that the profile's current summary, bullet wording, or skills section does not surface
   prominently (buried mid-bullet, phrased differently than the JD, or absent from the
   skills list). These are the highest-value additions — downstream nodes pull them
   forward first.

Hard rules:

- Any term on the CANDIDATE-DECLARED NEVER-CLAIM list goes in `unsupported`, even when the
  profile shows evidence — the candidate refuses to be interviewed on it. (Code enforces
  this too, but classify it correctly yourself.)
- `evidence` is a quote from the profile, never an inference ("has 15 years of testing, so
  probably knows X" is fabrication).
- Every JD must-have and nice-to-have term must land in exactly one of `supported` or
  `unsupported` (`missing_but_supported` entries also appear in `supported`).
- Do not editorialise or score — partition and quote.
