"""Deep per-job scoring audit — judge whether a fit score is *justified by evidence*.

This is a VERIFICATION task, not a re-scoring task. It never recomputes a fit score (re-running
the scorer at temp 0 on the same JD would only reproduce the same number). For a bounded, triaged
subset of jobs it asks: given the score the pipeline already produced and its stated reasoning, is
that score supported by the evidence in the JD?

The primary signal is a **deterministic grounding check** — the evidence quotes the scorer attached
to each strength/gap in `score_fit.txt` (`- <claim> ["<jdEvidence>"]`) are verified to actually
appear in `job_description.txt` (no model needed). A `score==0` on a rich JD whose cited strengths
*do* appear is an internal contradiction → a scoring/extraction bug, not a genuine non-fit. The LLM
adds only the softer judgment on top (score-direction consistency; thin/blocked-JD vs real bug).

Bounded: at most `AUDIT_MAX` jobs/run, triaged by the highest-value signals. Degrades gracefully
(file-only, or skip) when output files or the Postgres container are unavailable.
"""

import json
import os
import re
import subprocess
from pathlib import Path

from analyzer.llm import call_model

ENV = os.environ
AUDIT_MAX = int(ENV.get("RUN_ANALYZER_AUDIT_MAX", "8"))
BORDERLINE_BAND = int(ENV.get("RUN_ANALYZER_BORDERLINE_BAND", "5"))
FIT_THRESHOLD = int(ENV.get("RUN_ANALYZER_FIT_THRESHOLD", "50"))
RICH_JD_CHARS = 1200
JD_EXCERPT_CHARS = 4000
PG_USER = ENV.get("POSTGRES_USER", "jobfit")
PG_DB = ENV.get("POSTGRES_DB", "jobfit")
PG_CONTAINER = ENV.get("JD_DB_CONTAINER", "jobfit-db")

AUDIT_SYSTEM = """You audit ONE fit-score decision from a résumé-tailoring pipeline. You do NOT
re-score. Given the assigned score, its reasoning, the strengths/gaps the scorer cited (each with a
grounded=true/false flag saying whether its evidence quote was found verbatim in the JD), and a JD
excerpt, judge whether the assigned score is supported by the evidence.

Decide:
- verdict: "justified" | "too_low" | "too_high" | "ungrounded"
    justified  = score is consistent with the grounded evidence.
    too_low    = score is far below what the grounded strengths support (e.g. score 0 but several
                 real, grounded strengths and few real gaps) — likely a scoring/extraction bug.
    too_high   = score relies on strengths whose evidence is NOT grounded in the JD.
    ungrounded = most cited evidence is not found in the JD (the reasoning is hallucinated).
- If score==0: is it explained by a THIN/BLOCKED JD (jd_chars small / boilerplate) → note
  cause="thin_jd" (a scraping issue, NOT a scoring bug), or a RICH JD scored 0 → cause="scoring_bug".
- confidence: "high" | "medium" | "low".

Return ONLY this JSON, no prose:
{"verdict":"...","cause":"scoring_bug|thin_jd|na","confidence":"...","reason":"one sentence",
 "evidence_checks":["short concrete check, e.g. strength 'k8s' grounded but score is 0"]}"""


# ── candidate triage ────────────────────────────────────────────────────────────────
def select_candidates(recs):
    """Pick jobs worth deep-auditing, prioritized, deduped, capped at AUDIT_MAX."""
    picked, seen = [], set()

    def take(rec):
        jid = rec.get("jobId")
        if jid and jid not in seen and not rec.get("isDuplicate"):
            seen.add(jid)
            picked.append(rec)

    # 1. score==0 (real-JD-scored-0 is the highest-value bug signal)
    for r in recs:
        if (r.get("score", 0) or 0) == 0:
            take(r)
    # 2. borderline — the TAILOR/SKIP flips most likely to be wrong
    for r in recs:
        if abs((r.get("score", 0) or 0) - FIT_THRESHOLD) <= BORDERLINE_BAND:
            take(r)
    # 3. TAILOR with an error, or TAILOR with a suspiciously low score
    for r in recs:
        act = (r.get("action") or "").upper()
        if act == "TAILOR" and (r.get("error") or (r.get("score", 0) or 0) < FIT_THRESHOLD):
            take(r)
    return picked[:AUDIT_MAX]


# ── per-job file reads ──────────────────────────────────────────────────────────────
def _read_job_files(output_path):
    """Read score_fit.txt / job_description.txt / metadata.json from a job's output dir."""
    if not output_path:
        return None
    d = Path(output_path)
    if not d.exists():
        return None
    out = {"dir": str(d)}
    sf = d / "score_fit.txt"
    jd = d / "job_description.txt"
    md = d / "metadata.json"
    out["score_fit"] = sf.read_text(errors="replace") if sf.exists() else ""
    out["jd_text"] = jd.read_text(errors="replace") if jd.exists() else ""
    if md.exists():
        try:
            out["metadata"] = json.loads(md.read_text())
        except (json.JSONDecodeError, OSError):
            out["metadata"] = {}
    else:
        out["metadata"] = {}
    return out


# ── deterministic evidence grounding ────────────────────────────────────────────────
_SECTION_RE = re.compile(r"^(Strengths|Gaps):\s*$", re.MULTILINE)
_EVIDENCE_RE = re.compile(r'\["(.+?)"\]\s*$')


def _norm(s):
    return re.sub(r"\s+", " ", (s or "").lower()).strip()


def parse_evidence(score_fit_text):
    """Extract [(section, claim, evidence)] from the Strengths:/Gaps: blocks of score_fit.txt."""
    items = []
    lines = score_fit_text.splitlines()
    section = None
    for line in lines:
        h = _SECTION_RE.match(line)
        if h:
            section = h.group(1)
            continue
        if section and line.startswith("- "):
            body = line[2:]
            m = _EVIDENCE_RE.search(body)
            evidence = m.group(1) if m else ""
            claim = _EVIDENCE_RE.sub("", body).strip()
            items.append((section, claim, evidence))
        elif line.strip() == "" or (section and not line.startswith("- ")):
            # blank line or a new non-bullet line ends the current section
            if line.strip() == "":
                continue
            section = None
    return items


def grounding_check(items, jd_text):
    """For each cited evidence quote, is it present (normalized) in the JD? Returns a summary."""
    jd = _norm(jd_text)
    checked = []
    for section, claim, evidence in items:
        if evidence:
            grounded = _norm(evidence) in jd
        else:
            grounded = None  # no evidence quote to verify
        checked.append({"section": section, "claim": claim[:120],
                        "evidence": evidence[:160], "grounded": grounded})
    strengths = [c for c in checked if c["section"] == "Strengths"]
    grounded_strengths = sum(1 for c in strengths if c["grounded"] is True)
    ungrounded = sum(1 for c in checked if c["grounded"] is False)
    return {"items": checked, "grounded_strengths": grounded_strengths,
            "ungrounded_claims": ungrounded, "total_claims": len(checked)}


# ── Postgres tracks fetch (read-only, via docker exec psql) ──────────────────────────
_DOLLAR_TAG = "$jdanalyzer$"  # dollar-quote tag; URLs/ids never contain this


def fetch_track(job_url=None, message_id=None):
    """Fetch the newest tracks row for a job. Returns dict or None. Never raises."""
    if not job_url and not message_id:
        return None
    col, val = ("job_url", job_url) if job_url else ("email_id", message_id)
    if _DOLLAR_TAG in val:  # can't safely dollar-quote — skip rather than risk injection
        return None
    # Dollar-quote the literal so URLs with ?/&/= don't need escaping (psql -v :'u' is unreliable).
    sql = (f"SELECT row_to_json(t) FROM tracks t "
           f"WHERE {col} = {_DOLLAR_TAG}{val}{_DOLLAR_TAG} "
           f"ORDER BY created_at DESC LIMIT 1")
    try:
        p = subprocess.run(
            ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB,
             "-tAX", "-c", sql],
            capture_output=True, text=True, timeout=15,
        )
    except (subprocess.SubprocessError, OSError):
        return None
    line = (p.stdout or "").strip()
    if p.returncode != 0 or not line:
        return None
    try:
        return json.loads(line.splitlines()[0])
    except json.JSONDecodeError:
        return None


# ── the audit ───────────────────────────────────────────────────────────────────────
def _audit_one(rec, model):
    output_path = rec.get("outputPath")
    track = fetch_track(rec.get("job_url"), rec.get("message_id"))
    if not output_path and track:
        output_path = track.get("output_path")
    files = _read_job_files(output_path)
    if not files or not files.get("score_fit"):
        return None  # no evidence to verify — skip (degrade gracefully)

    items = parse_evidence(files["score_fit"])
    ground = grounding_check(items, files["jd_text"])
    jd_chars = len(files["jd_text"])
    threshold = (files.get("metadata") or {}).get("fit_threshold", FIT_THRESHOLD)

    user = (
        f"assigned_score: {rec.get('score')}\n"
        f"fit_threshold: {threshold}\n"
        f"pipeline_action: {rec.get('action')}\n"
        f"jd_chars: {jd_chars} (rich if >= {RICH_JD_CHARS})\n"
        f"grounding_summary: {json.dumps({k: ground[k] for k in ('grounded_strengths','ungrounded_claims','total_claims')})}\n"
        f"cited_claims (with grounded flag):\n{json.dumps(ground['items'], indent=1)}\n\n"
        f"reasoning + score_fit.txt:\n{files['score_fit'][:2500]}\n\n"
        f"JD excerpt (first {JD_EXCERPT_CHARS} chars):\n{files['jd_text'][:JD_EXCERPT_CHARS]}\n"
    )
    try:
        verdict = json.loads(call_model(AUDIT_SYSTEM, user, model=model, timeout=300))
    except Exception:  # noqa: BLE001 — one bad audit must not sink the run
        return None
    # Some models wrap the object in a list or under a key — normalize to a single dict.
    if isinstance(verdict, list):
        verdict = next((x for x in verdict if isinstance(x, dict)), None)
    elif isinstance(verdict, dict) and "verdict" not in verdict:
        nested = next((v for v in verdict.values() if isinstance(v, dict) and "verdict" in v), None)
        verdict = nested or verdict
    if not isinstance(verdict, dict) or "verdict" not in verdict:
        return None
    verdict.update({
        "job_id": rec.get("jobId"), "company": rec.get("company"),
        "assigned_score": rec.get("score"), "jd_chars": jd_chars,
        "grounded_strengths": ground["grounded_strengths"],
        "ungrounded_claims": ground["ungrounded_claims"],
    })
    return verdict


def run_audit(recs, model):
    """Audit a triaged subset. Returns (findings, verdicts). Bounded and best-effort."""
    if AUDIT_MAX <= 0:
        return [], []
    candidates = select_candidates(recs)
    verdicts = [v for v in (_audit_one(r, model) for r in candidates) if v]
    print(f"[audit] candidates={len(candidates)} audited={len(verdicts)} (cap={AUDIT_MAX})")
    findings = _findings_from_verdicts(verdicts)
    return findings, verdicts


def _findings_from_verdicts(verdicts):
    """Aggregate problematic verdicts into normal findings (no extra LLM call)."""
    bad = [v for v in verdicts
           if v.get("verdict") in ("too_low", "too_high", "ungrounded")
           and v.get("confidence") in ("high", "medium")]
    if not bad:
        return []

    # A rich JD scored 0 with grounded strengths is the headline scoring bug.
    scoring_bugs = [v for v in bad
                    if v.get("cause") == "scoring_bug"
                    or (v.get("verdict") == "too_low" and (v.get("assigned_score") or 0) == 0
                        and (v.get("grounded_strengths") or 0) >= 1)]
    ungrounded = [v for v in bad if v.get("verdict") in ("too_high", "ungrounded")
                  and v not in scoring_bugs]

    findings = []
    if scoring_bugs:
        sev = "high" if len(scoring_bugs) >= 2 else "medium"
        findings.append({
            "id": "scoring-zeroed-real-fits",
            "title": "Real JDs scored 0 despite grounded strengths (scoring/extraction bug)",
            "severity": sev,
            "category": "scoring",
            "evidence": [f"{v['job_id']} ({v.get('company')}): score={v['assigned_score']}, "
                         f"grounded_strengths={v.get('grounded_strengths')}, jd_chars={v.get('jd_chars')} — {v.get('reason','')}"
                         for v in scoring_bugs],
            "affected_jobs": [v["job_id"] for v in scoring_bugs],
            "proposed_fix": "Investigate why rich JDs with genuine, JD-grounded strengths receive a 0 "
                            "fit score. Likely an extraction/parse gap feeding the scorer, or the "
                            "scoring rubric/prompt collapsing to 0. Compare the JD the scorer saw "
                            "(job_description.txt) against the raw page/email to confirm the scorer "
                            "received the full JD.",
            "files": ["src/main/kotlin/com/jd/pipeline/nodes/ScoreFitNode.kt"],
            "agent_prompt": _scoring_agent_prompt(scoring_bugs),
        })
    if ungrounded:
        findings.append({
            "id": "ungrounded-score-evidence",
            "title": "Fit scores rely on strengths whose evidence is not in the JD",
            "severity": "medium",
            "category": "scoring",
            "evidence": [f"{v['job_id']} ({v.get('company')}): score={v['assigned_score']}, "
                         f"ungrounded_claims={v.get('ungrounded_claims')} — {v.get('reason','')}"
                         for v in ungrounded],
            "affected_jobs": [v["job_id"] for v in ungrounded],
            "proposed_fix": "The scorer cites strengths/gaps whose evidence quotes do not appear in "
                            "the JD (hallucinated grounding). Tighten the scoring prompt to require "
                            "verbatim JD evidence for every strength/gap and to down-weight claims it "
                            "cannot ground.",
            "files": ["src/main/kotlin/com/jd/pipeline/nodes/ScoreFitNode.kt",
                      "src/main/resources/skills"],
            "agent_prompt": _ungrounded_agent_prompt(ungrounded),
        })
    return findings


def _scoring_agent_prompt(vs):
    jobs = ", ".join(v["job_id"] for v in vs)
    return (
        "Symptom: the JD pipeline assigned a fit score of 0 to job(s) that DO fit — the scorer's own "
        "cited strengths were verified to appear verbatim in the job description, yet the score is 0.\n\n"
        f"Affected jobIds: {jobs}. For each, inspect its output dir (path in the pipeline `output/` tree, "
        "or Postgres tracks.output_path): compare score_fit.txt (Strengths with [\"evidence\"]) and "
        "job_description.txt. Confirm the strengths' evidence is present in the JD.\n\n"
        "Root-cause in src/main/kotlin/com/jd/pipeline/nodes/ScoreFitNode.kt: determine why a rich JD "
        "with grounded strengths collapses to score 0 — e.g. a parse/extraction path zeroing the score, "
        "a hard-gate misfiring, or the scoring prompt returning 0 on a formatting issue.\n\n"
        "Acceptance check: re-score the affected JD(s) with the scoring path (or the scan/scrape tuner) "
        "and assert the fit score is > 0 and consistent with the grounded strengths. Run `./gradlew test`."
    )


def _ungrounded_agent_prompt(vs):
    jobs = ", ".join(v["job_id"] for v in vs)
    return (
        "Symptom: the JD pipeline's fit scores cite strengths/gaps whose evidence quotes are NOT found "
        f"in the job description (hallucinated grounding). Affected jobIds: {jobs}.\n\n"
        "For each, compare score_fit.txt's Strengths/Gaps `[\"evidence\"]` quotes against "
        "job_description.txt — several do not appear.\n\n"
        "Fix in src/main/kotlin/com/jd/pipeline/nodes/ScoreFitNode.kt and the scoring skill under "
        "src/main/resources/skills: require every cited strength/gap to include a verbatim JD evidence "
        "quote, and instruct the scorer to discard/down-weight any claim it cannot ground in the JD.\n\n"
        "Acceptance check: re-score an affected JD and assert every cited evidence quote appears "
        "verbatim in the JD. Run `./gradlew test`."
    )
