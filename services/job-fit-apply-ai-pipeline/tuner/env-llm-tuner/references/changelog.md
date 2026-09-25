# Self-Scan Changelog history

Append-only. **Deliberately NOT in SKILL.md** — history is never needed to execute a run,
and keeping it out of the always-loaded context saves ~1,000 tokens per run.

## Self-Scan Changelog (2026-07-18 run)
- [MATCH]   SCAN_MODEL: ScanEmailNode, LlmDigestStrategy (fromModelString, temp 0.0, jsonMode true)
- [MATCH]   SCRAPE_MODEL: ScrapeJdNode (fromModelString, temp 0.0, jsonMode true; defaults to SCAN_MODEL)
- [CHANGED] SCORE_MODEL: node class `AtsScoringNode` → **`AtsValidationNode`**
            (AtsValidationNode.kt:28, `orchestrationClient`, nodeKey still "ats_scoring").
            Driver unchanged: SCORE_MODEL, temp 0.0, jsonMode true, thinking off.
            ScoreFitNode / JdExtractionNode / GapAnalysisNode unchanged.
- [MATCH]   RESUME_REASONING_MODEL: SummaryRewriteNode, BulletRewriteNode (reasoningClient, temp 0.25;
            BulletRewriteNode overrides timeoutSeconds=480 for the large array output)
- [MATCH]   SKILLS_MODEL: SkillsRestructureNode (skillsClient, temp 0.2, jsonMode true)
- [MATCH]   COVER_LETTER_MODEL: GenerateCoverLetterNode (fromModelString, temp 0.4, jsonMode false)
- [MATCH]   DRAFT_REPLY_MODEL: DraftReplyComposer (fromModelString, temp 0.3, jsonMode false)
- [MATCH]   RESUME_GEN_MODEL / PROFILE_GEN_MODEL: remain absent from Config.kt (removed 2026-07-16).
Backend enum (MLX_LOCAL, OLLAMA_LOCAL, OLLAMA_CLOUD, DEEPSEEK_CLOUD, MINIMAX_CLOUD) and
backendFor() routing (LlmClient.kt:382-391) verified unchanged vs the routing-rules table.
Section A2 re-verified against tuner/run-analyzer/analyzer/llm.py:29 — still oMLX-local /
`:ollama-cloud` / `:ollama-local` only, no `:cloud`. Unchanged.
NOTE: Config.kt:91 still comments "Creative (temp=0.4)" for RESUME_REASONING, but
reasoningClient uses 0.25 (LlmClient.kt:321). Stale source comment only — no behaviour impact.
ENV_LLM_TUNER_SKILL.md updated: 1 change (SCORE_MODEL node-class rename).

## Self-Scan Changelog (2026-07-16 run, superseded)
- [MATCH]   SCAN_MODEL: ScanEmailNode, LlmDigestStrategy (fromModelString, temp 0.0, jsonMode true)
- [MATCH]   SCRAPE_MODEL: ScrapeJdNode (fromModelString, temp 0.0, jsonMode true)
- [MATCH]   SCORE_MODEL: ScoreFitNode, JdExtractionNode, GapAnalysisNode, AtsScoringNode
            (ScoreFitNode now uses fromModelString instead of orchestrationClient —
             functionally equivalent: temp 0.0, jsonMode true, thinking disabled)
- [MATCH]   RESUME_REASONING_MODEL: SummaryRewriteNode, BulletRewriteNode (reasoningClient, temp 0.25)
- [MATCH]   SKILLS_MODEL: SkillsRestructureNode (skillsClient, temp 0.2, jsonMode true)
- [MATCH]   COVER_LETTER_MODEL: GenerateCoverLetterNode (fromModelString, temp 0.4, jsonMode false)
- [MATCH]   DRAFT_REPLY_MODEL: DraftReplyComposer (fromModelString, temp 0.3, jsonMode false)
- [REMOVED] RESUME_GEN_MODEL: GenerateResumeHtmlNode — node removed; resume HTML now rendered
            deterministically from resume.yaml (no LLM). Config.kt line 261-262.
- [REMOVED] PROFILE_GEN_MODEL: GenerateCandidateProfileNode — node removed; candidate profile
            now authored as structured YAML (no LLM). Config.kt line 261-262.
Backend enum (MLX_LOCAL, OLLAMA_LOCAL, OLLAMA_CLOUD, DEEPSEEK_CLOUD, MINIMAX_CLOUD) and
backendFor() routing verified unchanged vs the routing-rules table.
ENV_LLM_TUNER_SKILL.md updated: 2 removals (RESUME_GEN_MODEL, PROFILE_GEN_MODEL).
