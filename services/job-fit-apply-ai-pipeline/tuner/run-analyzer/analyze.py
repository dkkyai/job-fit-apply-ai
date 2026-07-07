#!/usr/bin/env python3
"""Assemble a run window, ask the configured Ollama model to analyze it, and write
each finding as a self-contained task file. Driven by analyze_run.sh (reads config
from the environment). Best-effort: never crash the cron job."""

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

ENV = os.environ
RUN_LOG = Path(ENV["RUN_LOG"])
START_ISO = ENV["START_ISO"]
RUN_TS = ENV["RUN_TS"]
FINDINGS_DIR = Path(ENV["FINDINGS_DIR"])
METRICS_FILE = Path(ENV["METRICS_FILE"])
WORKER_ERR = Path(ENV.get("WORKER_ERR", ""))
SKILL = Path(ENV["SKILL_FILE"]).read_text()
PROMPT = Path(ENV["PROMPT_FILE"]).read_text()
MODEL = ENV.get("MODEL", "Qwen3.5-9B-OptiQ-4bit")
OLLAMA_URL = ENV.get("OLLAMA_BASE_URL", ENV.get("OLLAMA_URL", "http://localhost:11434"))
MLX_LOCAL_BASE_URL = ENV.get("MLX_LOCAL_BASE_URL", "http://127.0.0.1:11436/v1")
MLX_API_KEY = ENV.get("MLX_API_KEY", "11436")

MAX_RECORDS = 200          # cap tokens for very large runs
STDERR_TAIL_LINES = 150


def load_window():
    """Records in run_log.jsonl whose ts is >= the run start (ISO lexical compare)."""
    if not RUN_LOG.exists():
        return []
    out = []
    for line in RUN_LOG.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            continue
        if rec.get("ts", "") >= START_ISO:
            out.append(rec)
    return out[-MAX_RECORDS:]


def compute_metrics(recs):
    def has(rec, *subs):
        e = (rec.get("error") or "").lower()
        return any(s in e for s in subs)

    jobs = len(recs)
    scores = [r.get("score", 0) for r in recs]
    durs = sorted(r.get("durationMs", 0) for r in recs)
    p95 = durs[int(len(durs) * 0.95)] if durs else 0
    return {
        "jobs": jobs,
        "tailored": sum(1 for r in recs if r.get("action") == "TAILOR"),
        "skipped": sum(1 for r in recs if r.get("action") == "SKIP"),
        "errors": sum(1 for r in recs if r.get("error")),
        "zero_score": sum(1 for r in recs if r.get("score", 0) == 0 and not r.get("isDuplicate")),
        "thin_digest": sum(1 for r in recs if r.get("isDigest") and r.get("jdTextLen", 0) < 400),
        "scrape_blocked": sum(1 for r in recs if has(r, "block", "captcha", "403")),
        "timeouts": sum(1 for r in recs if has(r, "timed out", "timeout")),
        "ollama_oom": sum(1 for r in recs if has(r, "unexpectedly stopped")),
        "avg_score": round(sum(scores) / len(scores), 1) if scores else 0.0,
        "p95_duration_ms": p95,
    }


def call_model(user_content):
    # Mirror the pipeline's LlmClient routing:
    #   no suffix       -> local oMLX (OpenAI /v1/chat/completions)
    #   ":ollama-cloud" -> Ollama Cloud (/api/chat, with API key)
    #   ":ollama-local" -> local Ollama escape hatch (/api/chat)
    model = MODEL
    is_qwen3 = model.lower().startswith("qwen3")

    def think_wrap(text):
        return ("/no_think\n" + text) if is_qwen3 else text

    if model.endswith(":ollama-cloud") or model.endswith(":ollama-local"):
        # ── Ollama wire format (/api/chat) ──
        headers = {"Content-Type": "application/json"}
        if model.endswith(":ollama-cloud"):
            model = model[: -len(":ollama-cloud")]
            url = ENV.get("OLLAMA_CLOUD_BASE_URL", "https://ollama.com")
            api_key = ENV.get("OLLAMA_API_KEY", "")
            if api_key:
                headers["Authorization"] = f"Bearer {api_key}"
        else:
            model = model[: -len(":ollama-local")]
            url = OLLAMA_URL
        body = json.dumps({
            "model": model,
            "messages": [
                {"role": "system", "content": SKILL},
                {"role": "user", "content": think_wrap(user_content)},
            ],
            "stream": False,
            "format": "json",
            "keep_alive": -1,
            "options": {"temperature": 0.0, "num_ctx": 32768},
        }).encode()
        req = urllib.request.Request(f"{url}/api/chat", data=body, headers=headers)
        with urllib.request.urlopen(req, timeout=600) as resp:
            raw = resp.read()
        parsed = json.loads(raw)
        content = parsed["message"]["content"] or parsed["message"].get("thinking", "")
    else:
        # ── oMLX / OpenAI wire format (/v1/chat/completions) ──
        headers = {"Content-Type": "application/json",
                   "Authorization": f"Bearer {MLX_API_KEY}"}
        body = json.dumps({
            "model": model,
            "messages": [
                {"role": "system", "content": SKILL},
                {"role": "user", "content": think_wrap(user_content)},
            ],
            "stream": False,
            "response_format": {"type": "json_object"},
            "temperature": 0.0,
        }).encode()
        req = urllib.request.Request(f"{MLX_LOCAL_BASE_URL}/chat/completions",
                                     data=body, headers=headers)
        with urllib.request.urlopen(req, timeout=600) as resp:
            raw = resp.read()
        parsed = json.loads(raw)
        content = parsed["choices"][0]["message"]["content"]

    if not content:
        raise ValueError(f"model returned empty content. raw={raw[:200]!r}")
    # Strip any <think> reasoning block before the JSON.
    content = re.sub(r"<think>.*?</think>", "", content, flags=re.IGNORECASE | re.DOTALL).strip()
    return content


def slugify(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")[:50] or "finding"


def write_findings(analysis):
    FINDINGS_DIR.mkdir(parents=True, exist_ok=True)
    (FINDINGS_DIR / "analysis.json").write_text(json.dumps(analysis, indent=2))
    findings = analysis.get("findings", []) or []
    for i, f in enumerate(findings, 1):
        name = f"{i:02d}-{slugify(f.get('id') or f.get('title', 'finding'))}.md"
        body = [
            f"# {f.get('title', '(untitled finding)')}",
            "",
            f"- **Severity:** {f.get('severity', '?')}",
            f"- **Category:** {f.get('category', '?')}",
            f"- **Run:** {RUN_TS}",
            "",
            "## Evidence",
            *[f"- {e}" for e in f.get("evidence", [])],
            "",
            "## Affected jobs",
            ", ".join(f.get("affected_jobs", [])) or "(see evidence)",
            "",
            "## Proposed fix",
            f.get("proposed_fix", ""),
            "",
            "## Files",
            *[f"- `{p}`" for p in f.get("files", [])],
            "",
            "## Agent prompt (hand this to a fresh coding session)",
            "",
            f.get("agent_prompt", ""),
            "",
        ]
        (FINDINGS_DIR / name).write_text("\n".join(body))
    return findings


def main():
    recs = load_window()
    metrics = compute_metrics(recs)

    prior = {}
    if METRICS_FILE.exists():
        try:
            prior = json.loads(METRICS_FILE.read_text())
        except json.JSONDecodeError:
            prior = {}

    stderr_tail = ""
    if WORKER_ERR and WORKER_ERR.exists():
        stderr_tail = "\n".join(WORKER_ERR.read_text(errors="replace").splitlines()[-STDERR_TAIL_LINES:])

    if not recs:
        print("[analyze] no jobs in run window — nothing to analyze")
        FINDINGS_DIR.mkdir(parents=True, exist_ok=True)
        (FINDINGS_DIR / "analysis.json").write_text(json.dumps(
            {"health": "healthy", "summary": "No jobs processed in this run window.",
             "metrics": metrics, "regressions": [], "findings": []}, indent=2))
        METRICS_FILE.write_text(json.dumps(metrics, indent=2))
        return 0

    user = (
        f"{PROMPT}\n\n"
        f"COMPUTED_METRICS (pre-computed for you — verify and refine):\n{json.dumps(metrics)}\n\n"
        f"PRIOR_METRICS:\n{json.dumps(prior)}\n\n"
        f"RUN_REPORT ({len(recs)} job record(s), one JSON per line):\n"
        + "\n".join(json.dumps(r) for r in recs)
        + f"\n\nWORKER_STDERR_TAIL (last {STDERR_TAIL_LINES} lines, for root-causing only):\n{stderr_tail}\n"
    )

    try:
        raw = call_model(user)
        analysis = json.loads(raw)
    except Exception as e:  # noqa: BLE001 — never crash the cron job
        print(f"[analyze] model call/parse failed: {e}", file=sys.stderr)
        FINDINGS_DIR.mkdir(parents=True, exist_ok=True)
        (FINDINGS_DIR / "analysis.json").write_text(json.dumps(
            {"health": "unknown", "summary": f"Analyzer model failed: {e}",
             "metrics": metrics, "regressions": [], "findings": []}, indent=2))
        METRICS_FILE.write_text(json.dumps(metrics, indent=2))
        return 1

    # Prefer the model's metrics if present, else our computed ones.
    analysis.setdefault("metrics", metrics)
    findings = write_findings(analysis)
    METRICS_FILE.write_text(json.dumps(analysis.get("metrics", metrics), indent=2))

    print(f"[analyze] health={analysis.get('health')} "
          f"jobs={metrics['jobs']} errors={metrics['errors']} "
          f"zero_score={metrics['zero_score']} thin_digest={metrics['thin_digest']} "
          f"findings={len(findings)}")
    print(f"[analyze] {analysis.get('summary', '')}")
    for f in findings:
        print(f"  - [{f.get('severity')}] {f.get('title')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
