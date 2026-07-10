"""Run-analyzer package.

The analyzer consumes the pipeline's completed-job stream (bridge feed, joined with
run_log.jsonl and — for the deep audit — Postgres tracks + per-job output files),
has an LLM judge pipeline health and scoring quality, writes findings as task files,
and (gated, opt-in) opens a living draft PR with proposed fixes.

Split into small modules so each phase stays independently reviewable:
  cursor    — persisted, independent completed_seq cursor (mirrors the notifier)
  bridge    — HTTP client for the bridge completed-event feed
  sources   — assemble the run window: bridge spine + run_log enrichment
  metrics   — deterministic aggregate metrics over the window
  history   — append-only metrics history + rolling baseline (Phase 2)
  llm       — model routing (oMLX / ollama-cloud / ollama-local)
  findings  — write analysis + per-finding task files
  audit     — deep per-job scoring-correctness audit (Phase 3)
  autofix   — gated auto-fix → living draft PR loop (Phase 4)
  notify    — Discord/Telegram messaging (Phase 4)

`analyze.py` (one level up) is the thin orchestrator that wires these together.
"""
