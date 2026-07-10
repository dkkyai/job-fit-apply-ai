"""Append-only metrics history + rolling baseline for regression detection.

Replaces the single-snapshot `last_metrics.json` (kept for one release as a shim). Each
non-empty run appends one line; the baseline is the per-metric median over the last N runs
(median is robust to a single outlier run). The LLM is given both the immediate prior run
and the rolling median so "regression" means a move off the trend, not just vs one run.

Rates (error_rate, zero_score_rate, thin_digest_rate) are the comparable signals across
variable-size cursor windows; raw counts vary with window size and are for evidence only.
"""

import json
from pathlib import Path

# Metrics worth trending. Rates first (comparable across window sizes), then stable gauges.
BASELINE_KEYS = [
    "error_rate", "zero_score_rate", "thin_digest_rate",
    "avg_score", "p95_duration_ms",
]


def append(history_file: Path, run_ts, cursor_from, cursor_to, window_jobs, metrics):
    """Append one run's metrics. Best-effort; never raises."""
    rec = {
        "run_ts": run_ts,
        "cursor_from": cursor_from,
        "cursor_to": cursor_to,
        "window_jobs": window_jobs,
        "metrics": metrics,
    }
    try:
        p = Path(history_file)
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open("a") as f:
            f.write(json.dumps(rec) + "\n")
    except OSError:
        pass


def _read(history_file: Path):
    p = Path(history_file)
    if not p.exists():
        return []
    out = []
    for line in p.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


def _median(vals):
    vals = sorted(v for v in vals if isinstance(v, (int, float)))
    n = len(vals)
    if n == 0:
        return None
    mid = n // 2
    return vals[mid] if n % 2 else round((vals[mid - 1] + vals[mid]) / 2, 3)


def baseline(history_file: Path, window: int = 10):
    """Per-metric median over the last `window` recorded runs.

    Returns {"runs": k, "window": N, "median": {metric: value, ...},
             "last": {metric: value, ...}} — or {} when there is no history yet.
    """
    hist = _read(history_file)
    if not hist:
        return {}
    recent = hist[-window:]
    med = {}
    for k in BASELINE_KEYS:
        vals = [h.get("metrics", {}).get(k) for h in recent]
        m = _median([v for v in vals if v is not None])
        if m is not None:
            med[k] = m
    last = {k: recent[-1].get("metrics", {}).get(k) for k in BASELINE_KEYS}
    return {"runs": len(recent), "window": window, "median": med, "last": last}
