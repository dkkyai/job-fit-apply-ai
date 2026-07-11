"""Cross-run findings ledger — classify each run's findings as NEW / WORSENING / UNCHANGED
so the analysis pass surfaces *change* rather than re-describing steady state every tick.

Keyed on the same `fingerprint` (findings.py — canonical id + category) used by the autofix
ledger and git commit trailers, so a root cause maps to one entry across runs. Append-only JSONL under state/;
the latest line per fingerprint wins. Best-effort: a missing/garbled file reads as empty.

- NEW       — fingerprint never seen before.
- WORSENING — severity increased OR more jobs affected than last time.
- UNCHANGED — seen before, no worse (suppressed from notification; last_seen still updated).
"""

import json
from pathlib import Path

from analyzer.findings import fingerprint

SEV_ORDER = {"low": 0, "medium": 1, "high": 2}


def _load(path: Path):
    """Latest ledger record per fingerprint. {} when absent/unreadable."""
    p = Path(path)
    if not p.exists():
        return {}
    out = {}
    for line in p.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        fp = e.get("fingerprint")
        if fp:
            out[fp] = e  # last line wins
    return out


def _affected(f):
    return len(f.get("affected_jobs", []) or [])


def classify(findings, path: Path, run_ts):
    """Split `findings` into new/worsening/unchanged vs the ledger and append updated records.

    Returns {"new": [...], "worsening": [...], "unchanged": [...]} where each list holds the
    live finding dicts (already carrying `fingerprint` from write_findings).
    """
    ledger = _load(path)
    new, worsening, unchanged, records = [], [], [], []

    for f in findings:
        fp = f.get("fingerprint") or fingerprint(f)
        f["fingerprint"] = fp
        sev = f.get("severity", "low")
        aff = _affected(f)
        prior = ledger.get(fp)

        if prior is None:
            bucket, first_seen, seen_count = new, run_ts, 1
        else:
            worse = (SEV_ORDER.get(sev, 0) > SEV_ORDER.get(prior.get("severity", "low"), 0)
                     or aff > int(prior.get("affected_count", 0)))
            bucket = worsening if worse else unchanged
            first_seen = prior.get("first_seen_ts", run_ts)
            seen_count = int(prior.get("seen_count", 0)) + 1

        bucket.append(f)
        records.append({
            "fingerprint": fp,
            "id": f.get("id"),
            "title": f.get("title"),
            "severity": sev,
            "category": f.get("category"),
            "affected_count": aff,
            "first_seen_ts": first_seen,
            "last_seen_ts": run_ts,
            "seen_count": seen_count,
            "status": "active",
        })

    _append(path, records)
    return {"new": new, "worsening": worsening, "unchanged": unchanged}


def _append(path: Path, records):
    if not records:
        return
    try:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open("a") as fh:
            for r in records:
                fh.write(json.dumps(r) + "\n")
    except OSError:
        pass


def summarize(f):
    """One-line finding summary for a notification."""
    return f"[{f.get('severity', '?')}] {f.get('title', '(untitled)')}"
