#!/usr/bin/env python3
"""tuner_tools.py — the two mechanical lookups the tuner previously did by reading
large payloads into model context.

WHY THIS EXISTS
  Section E read all four existing env files (33 KB / ~9,244 tokens) to rewrite them,
  and Section D.3 pulled a 45-column × 398-row leaderboard (~5,278 tokens) to decide
  between ~28 candidate models. Both are mechanical filters. See README-TOKENS.md.

SUBCOMMANDS
  values <profile>       Print the 8 model assignments from an env file. ~640 tok to read
                         instead of 9,244. PROFILE ∈ quality|local-quality|local-good-enough|recommended
  diff <profile> <json>  Compare an env file's values against a JSON {VAR: value} blob.
  models                 Print the installed oMLX models (one per line).
  cloud                  Print the ollama-cloud catalogue (one per line), authenticated.
  leaderboard <names...> Print only the leaderboard rows for the named models, trimming the
                         45-column table to the 6 columns that matter. Reads ~2k tok
                         instead of ~5.3k.

Everything prints plain text designed to be read directly, not re-parsed by a model.
"""
import argparse, json, os, re, sys, urllib.request

D = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # scripts/ -> env-llm-tuner/
FILES = {
    "quality":           ".env.quality",
    "local-quality":     ".env.local-llm-quality",
    "local-good-enough": ".env.local-llm-good-enough",
    "recommended":       ".env.recommended",
}
VARS = ["SCAN_MODEL", "SCRAPE_MODEL", "SCORE_MODEL", "RESUME_REASONING_MODEL",
        "SKILLS_MODEL", "COVER_LETTER_MODEL", "DRAFT_REPLY_MODEL", "RUN_ANALYZER_MODEL"]

MLX_URL = os.environ.get("MLX_LOCAL_BASE_URL", "http://127.0.0.1:11436/v1")
MLX_KEY = os.environ.get("MLX_API_KEY", "11436")
LEADERBOARD = "https://llm-stats.com/leaderboards/open-llm-leaderboard"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36")


def parse_env(path):
    """Return ONLY var=value. Comment lines — the entire cost of the old approach — are skipped."""
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        m = re.match(r"^([A-Z_]+)=(.*)$", line.rstrip("\n"))
        if m:
            out[m.group(1)] = m.group(2).strip()
    return out


def cmd_values(profile):
    path = os.path.join(D, FILES[profile])
    vals = parse_env(path)
    if not vals:
        print(f"missing/empty: {path}", file=sys.stderr)
        return 1
    for v in VARS:
        print(f"{v}={vals.get(v, '')}")
    missing = [v for v in VARS if not vals.get(v)]
    if missing:
        print(f"WARNING missing: {missing}", file=sys.stderr)
    return 0


def cmd_diff(profile, json_path):
    """Report only CHANGED vars — the tuner's actual question, in a few tokens."""
    cur = parse_env(os.path.join(D, FILES[profile]))
    want = json.load(open(json_path, encoding="utf-8"))
    changed = []
    for v in VARS:
        a, b = cur.get(v, ""), want.get(v, "")
        if a != b:
            changed.append((v, a, b))
    if not changed:
        print("no changes")
        return 0
    for v, a, b in changed:
        print(f"CHANGED {v}: {a} -> {b}")
    print(f"{len(changed)} changed, {len(VARS)-len(changed)} unchanged")
    return 0


def cmd_models():
    req = urllib.request.Request(MLX_URL.rstrip("/") + "/models",
                                 headers={"Authorization": f"Bearer {MLX_KEY}"})
    with urllib.request.urlopen(req, timeout=15) as r:
        d = json.load(r)
    for m in sorted(x.get("id", "") for x in d.get("data", [])):
        print(m)
    return 0


def cmd_cloud():
    key = os.environ.get("OLLAMA_API_KEY")
    if not key:
        # fall back to the Hermes .env, which is where the key lives on this machine
        p = os.path.expanduser("~/.hermes/.env")
        if os.path.exists(p):
            for line in open(p):
                if line.startswith("export OLLAMA_API_KEY="):
                    key = line.split("=", 1)[1].strip()
    if not key:
        print("no OLLAMA_API_KEY", file=sys.stderr)
        return 1
    req = urllib.request.Request("https://ollama.com/api/tags",
                                 headers={"Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=20) as r:
        d = json.load(r)
    for m in sorted(x.get("name", "") for x in d.get("models", [])):
        print(m)
    return 0


def _fetch_leaderboard_models():
    """The leaderboard is a client-rendered Next.js app: the served HTML contains ZERO
    table rows. The real data rides in `self.__next_f.push([1, "<json>"])` RSC chunks,
    so reassemble those and extract the model objects."""
    req = urllib.request.Request(LEADERBOARD, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        html = r.read().decode("utf-8", "replace")
    chunks = []
    for m in re.finditer(r'self\.__next_f\.push\(\[1,\s*"((?:[^"\\]|\\.)*)"\]\)', html, re.S):
        try:
            chunks.append(json.loads('"' + m.group(1) + '"'))
        except Exception:
            continue
    blob = "".join(chunks)
    models = []
    for m in re.finditer(r'\{"model_id":"(?:[^"\\]|\\.)*?".*?"index_healthcare":(?:null|[0-9.]+)\}', blob, re.S):
        try:
            models.append(json.loads(m.group(0)))
        except Exception:
            continue
    return models


def cmd_leaderboard(names):
    """Print only the 6 columns that matter, for only the named models.
    Replaces reading the whole 45-column table (~5,278 tok) with ~a few hundred."""
    try:
        models = _fetch_leaderboard_models()
    except Exception as e:
        print(f"leaderboard fetch failed: {e}", file=sys.stderr)
        return 1
    if not models:
        print("no models parsed (page structure may have changed)", file=sys.stderr)
        return 1
    # rank by the general index, matching the site's own ordering
    ranked = [m for m in models if isinstance(m.get("index_general"), (int, float))]
    ranked.sort(key=lambda m: -m["index_general"])
    rank_of = {m.get("model_id"): i + 1 for i, m in enumerate(ranked)}

    def fmt(m):
        g = m.get("index_general"); r = m.get("index_reasoning")
        c = m.get("index_code");    a = m.get("index_agents")
        f = lambda v: f"{v:5.1f}" if isinstance(v, (int, float)) else "    -"
        return (f"#{rank_of.get(m.get('model_id'), 0):>3} {str(m.get('name'))[:34]:34} "
                f"general={f(g)} reason={f(r)} code={f(c)} agent={f(a)} "
                f"in=${m.get('input_price')} out=${m.get('output_price')} "
                f"ctx={m.get('context')}")

    wanted = [n.lower() for n in names]
    hits = [m for m in models if any(w in str(m.get("name", "")).lower() for w in wanted)]
    if not hits:
        print(f"no match for {names}; {len(models)} models available", file=sys.stderr)
        print("available: " + ", ".join(sorted(str(m.get('name')) for m in models)[:40]), file=sys.stderr)
        return 1
    for m in sorted(hits, key=lambda m: -(m.get("index_general") or 0)):
        print(fmt(m))
    print(f"({len(models)} models indexed; {len(hits)} matched)")
    return 0


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("values"); p.add_argument("profile", choices=sorted(FILES))
    p = sub.add_parser("diff");   p.add_argument("profile", choices=sorted(FILES)); p.add_argument("json")
    sub.add_parser("models")
    sub.add_parser("cloud")
    p = sub.add_parser("leaderboard"); p.add_argument("names", nargs="+")
    a = ap.parse_args()
    if a.cmd == "values":      return cmd_values(a.profile)
    if a.cmd == "diff":        return cmd_diff(a.profile, a.json)
    if a.cmd == "models":      return cmd_models()
    if a.cmd == "cloud":       return cmd_cloud()
    if a.cmd == "leaderboard": return cmd_leaderboard(a.names)
    return 2


if __name__ == "__main__":
    sys.exit(main())
