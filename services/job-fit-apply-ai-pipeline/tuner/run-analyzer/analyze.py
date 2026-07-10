#!/usr/bin/env python3
"""Run-analyzer orchestrator.

Drains the bridge completed-event feed from a persisted, independent cursor, joins the
window with run_log.jsonl, computes deterministic metrics, asks the configured model to
judge health + scoring quality, and writes each finding as a self-contained task file.

Driven by run_analyzer.sh (config via the environment). Best-effort: never crash the
scheduled job. No batch is triggered — a "run" is simply the set of jobs completed since
the last cursor position (the poller feeds Gmail->bridge and the processor drains it
continuously).

Later phases layer on: rolling-baseline trend tracking (Phase 2), a deep scoring audit
(Phase 3), and a gated auto-fix -> living draft PR loop (Phase 4, --autofix).
"""

import json
import os
import sys
from pathlib import Path

from analyzer import audit, history
from analyzer.bridge import BridgeClient
from analyzer.cursor import Cursor
from analyzer.findings import write_findings
from analyzer.llm import call_model
from analyzer.metrics import compute_metrics
from analyzer.sources import join_window, load_run_log

ENV = os.environ

RUN_LOG = Path(ENV["RUN_LOG"])
RUN_TS = ENV["RUN_TS"]
FINDINGS_DIR = Path(ENV["FINDINGS_DIR"])
CURSOR_FILE = Path(ENV.get("CURSOR_FILE", "state/cursor"))
METRICS_FILE = Path(ENV.get("METRICS_FILE", "state/last_metrics.json"))
HISTORY_FILE = Path(ENV.get("HISTORY_FILE", "state/metrics_history.jsonl"))
BASELINE_WINDOW = int(ENV.get("RUN_ANALYZER_BASELINE_WINDOW", "10"))
SKILL = Path(ENV["SKILL_FILE"]).read_text()
PROMPT = Path(ENV["PROMPT_FILE"]).read_text()
MODEL = ENV.get("MODEL", "Qwen3.5-9B-OptiQ-4bit")
BRIDGE_URL = ENV.get("JD_BRIDGE_URL", "http://127.0.0.1:8765")
PROCESSOR_LOG_TAIL = ENV.get("PROCESSOR_LOG_TAIL", "")

MAX_RECORDS = 200  # cap tokens for very large windows


def drain_window():
    """Consume completed jobs since the persisted cursor. Returns the joined window.

    Cold start (no cursor): seed at the bridge head and return [] (skip history), exactly
    like the notifier. Persists the cursor incrementally per page (at-least-once).
    """
    bridge = BridgeClient(BRIDGE_URL)
    cursor = Cursor(CURSOR_FILE)

    since = cursor.read()
    if since is None:
        head = bridge.head_seq()
        cursor.write(head)
        print(f"[analyze] cold start — seeding cursor at head seq {head} (skipping history)")
        return [], head, head

    completed, last_seq = bridge.drain(since, on_page=cursor.write)
    run_log_by_id = load_run_log(RUN_LOG)
    window = join_window(completed, run_log_by_id)
    return window[-MAX_RECORDS:], since, last_seq


def build_user_prompt(metrics, prior, base, recs):
    return (
        f"{PROMPT}\n\n"
        f"COMPUTED_METRICS (pre-computed for you — verify and refine):\n{json.dumps(metrics)}\n\n"
        f"PRIOR_METRICS (immediately previous run):\n{json.dumps(prior)}\n\n"
        f"ROLLING_BASELINE (median over the last {BASELINE_WINDOW} runs — a metric moving off "
        f"this median is a regression even if absolute counts look fine):\n{json.dumps(base)}\n\n"
        f"RUN_REPORT ({len(recs)} job record(s), one JSON per line):\n"
        + "\n".join(json.dumps(r) for r in recs)
        + f"\n\nPROCESSOR_LOG_TAIL (recent jobfit-processor log, for root-causing only):\n{PROCESSOR_LOG_TAIL}\n"
    )


def main():
    recs, since, last_seq = drain_window()

    # Empty window: nothing consumed. Exit quietly and cheaply — no LLM call, no findings dir.
    if not recs:
        print(f"[analyze] no new completed jobs since seq {since} — nothing to do")
        return 0

    metrics = compute_metrics(recs)

    prior = {}
    if METRICS_FILE.exists():
        try:
            prior = json.loads(METRICS_FILE.read_text())
        except json.JSONDecodeError:
            prior = {}
    base = history.baseline(HISTORY_FILE, window=BASELINE_WINDOW)

    user = build_user_prompt(metrics, prior, base, recs)

    try:
        raw = call_model(SKILL, user, model=MODEL)
        analysis = json.loads(raw)
    except Exception as e:  # noqa: BLE001 — never crash the scheduled job
        print(f"[analyze] model call/parse failed: {e}", file=sys.stderr)
        FINDINGS_DIR.mkdir(parents=True, exist_ok=True)
        (FINDINGS_DIR / "analysis.json").write_text(json.dumps(
            {"health": "unknown", "summary": f"Analyzer model failed: {e}",
             "metrics": metrics, "regressions": [], "findings": []}, indent=2))
        _save_metrics(metrics)
        return 1

    analysis.setdefault("metrics", metrics)
    analysis["cursor"] = {"from": since, "to": last_seq, "window_jobs": len(recs)}
    analysis["baseline"] = base

    # Deep scoring audit (Phase 3): verify a triaged subset of scores against JD evidence and
    # merge any resulting findings. Best-effort — never sink the run.
    try:
        audit_findings, verdicts = audit.run_audit(recs, MODEL)
        if audit_findings:
            analysis.setdefault("findings", []).extend(audit_findings)
        if verdicts:
            analysis["scoring_audit"] = verdicts
    except Exception as e:  # noqa: BLE001
        print(f"[analyze] scoring audit failed (non-fatal): {e}", file=sys.stderr)

    findings = write_findings(analysis, FINDINGS_DIR, RUN_TS)
    _save_metrics(analysis.get("metrics", metrics))
    # Trend history: record our deterministic metrics (not the model's, which may drift).
    history.append(HISTORY_FILE, RUN_TS, since, last_seq, len(recs), metrics)

    print(f"[analyze] health={analysis.get('health')} "
          f"jobs={metrics['jobs']} errors={metrics['errors']} "
          f"zero_score={metrics['zero_score']} thin_digest={metrics['thin_digest']} "
          f"seq={since}->{last_seq} findings={len(findings)}")
    print(f"[analyze] {analysis.get('summary', '')}")
    for f in findings:
        print(f"  - [{f.get('severity')}] {f.get('title')}")
    return 0


def _save_metrics(metrics):
    try:
        METRICS_FILE.parent.mkdir(parents=True, exist_ok=True)
        METRICS_FILE.write_text(json.dumps(metrics, indent=2))
    except OSError as e:
        print(f"[analyze] failed to persist metrics: {e}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
