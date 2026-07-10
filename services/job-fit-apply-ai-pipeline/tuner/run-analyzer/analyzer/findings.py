"""Write the analysis + one self-contained task file per finding.

Moved from the original analyze.py:140-175. Each finding file ends with an "Agent prompt"
section a fresh coding session (or the Phase-4 autofix loop) can act on with no other
context. Also computes the stable finding fingerprint the autofix loop dedupes on.
"""

import hashlib
import json
import re
from pathlib import Path


def slugify(s):
    return re.sub(r"[^a-z0-9]+", "-", (s or "").lower()).strip("-")[:50] or "finding"


def fingerprint(f):
    """Stable id for a root cause: hash of (id + sorted(files) + category).

    Deliberately excludes run-specific job ids so the same root cause across runs maps to
    one fingerprint (the autofix loop keys dedup on this via git commit trailers).
    """
    fid = f.get("id") or slugify(f.get("title", "finding"))
    files = ",".join(sorted(f.get("files", []) or []))
    cat = f.get("category", "")
    return hashlib.sha1(f"{fid}|{files}|{cat}".encode()).hexdigest()[:12]


def write_findings(analysis, findings_dir: Path, run_ts: str):
    findings_dir = Path(findings_dir)
    findings_dir.mkdir(parents=True, exist_ok=True)
    findings = analysis.get("findings", []) or []
    # Attach fingerprints BEFORE serializing analysis.json so the autofix loop can read them.
    for f in findings:
        f["fingerprint"] = fingerprint(f)
    (findings_dir / "analysis.json").write_text(json.dumps(analysis, indent=2))
    for i, f in enumerate(findings, 1):
        name = f"{i:02d}-{slugify(f.get('id') or f.get('title', 'finding'))}.md"
        body = [
            f"# {f.get('title', '(untitled finding)')}",
            "",
            f"- **Severity:** {f.get('severity', '?')}",
            f"- **Category:** {f.get('category', '?')}",
            f"- **Run:** {run_ts}",
            f"- **Fingerprint:** {f['fingerprint']}",
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
        (findings_dir / name).write_text("\n".join(body))
    return findings
