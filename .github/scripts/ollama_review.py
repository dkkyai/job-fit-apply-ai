#!/usr/bin/env python3
"""Ollama Cloud PR review: context-rich, inline comments, one-click fixes.

Reads the PR from GITHUB_EVENT_PATH, builds review context (full file contents
for small PRs, diffs with generous hunks otherwise), asks an Ollama Cloud
model for findings as JSON, and posts them as an inline PR review. Mechanical
fixes are posted as GitHub suggested-change blocks (one-click apply); judgment
calls are flagged, never rewritten. The workflow never pushes commits.

Required env: GITHUB_TOKEN, OLLAMA_CLOUD_API_KEY, GITHUB_REPOSITORY,
GITHUB_EVENT_PATH.
Optional env: OLLAMA_MODEL (default deepseek-v4-pro:0813),
OLLAMA_BASE_URL (default https://ollama.com/v1), PR_NUMBER (workflow_dispatch).
"""

import base64
import json
import os
import re
import sys
import urllib.request
import urllib.error

# ---------------------------------------------------------------- tunables
MODEL = os.environ.get("OLLAMA_MODEL", "deepseek-v4-pro:0813")
OLLAMA_BASE = os.environ.get("OLLAMA_BASE_URL", "https://ollama.com/v1")
FULL_FILE_BUDGET_CHARS = 120_000   # <= this much diff text -> include full files
MAX_FILE_CHARS = 30_000            # per-file truncation cap
MAX_COMMENTS = 25
SKIP_LABEL = "skip-ollama-review"

SKIP_SUFFIXES = (
    ".lock", ".jar", ".class", ".war", ".png", ".jpg", ".jpeg", ".gif",
    ".svg", ".ico", ".pdf", ".zip", ".gz", ".min.js", ".snap", ".bin",
)
SKIP_NAMES = ("package-lock.json", "yarn.lock", "pnpm-lock.yaml",
              "gradle.lockfile", "Cargo.lock", "poetry.lock")
SKIP_DIRS = ("build/", "dist/", "out/", "node_modules/", ".gradle/",
             "vendor/", "__pycache__/", ".git/")

REVIEW_PROMPT = """You are a senior code reviewer for the {repo} repository
(Kotlin/JVM backend; LangGraph-style pipeline). Review the changed code below.

{context}

Prioritize: correctness bugs, logic errors, null-safety, concurrency/threading
issues, resource leaks, security problems (hardcoded secrets, injection, unsafe
deserialization), performance regressions, API contract breaks, and deviations
from the surrounding code's conventions. Ignore formatting (a formatter handles
it) and do not restate what the code does.

For each finding, decide whether the fix is mechanical and safe (typo, obvious
bug with exactly one right fix, API misuse with a clear correction). If so,
provide `suggestion` with the exact replacement lines. If the fix requires
judgment, omit `suggestion` and explain the concern instead.

Return JSON only, no prose outside the JSON:
{{"summary": "2-3 sentence overall assessment",
  "comments": [{{"path": "repo/relative/path",
                 "line": <new-file line number, or null for a file-level note>,
                 "body": "tight markdown finding",
                 "suggestion": "exact replacement code, or null"}}]}}
Line numbers refer to the NEW version of each file. Rank by severity; at most
25 comments.
"""


# ---------------------------------------------------------------- http helpers
def gh_api(path, method="GET", body=None):
    token = os.environ["GITHUB_TOKEN"]
    req = urllib.request.Request(
        f"https://api.github.com{path}",
        data=json.dumps(body).encode() if body is not None else None,
        method=method,
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github+json",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:500]
        raise SystemExit(f"GitHub API {method} {path} -> {e.code}: {detail}")


def ollama_chat(messages):
    key = os.environ.get("OLLAMA_CLOUD_API_KEY", "")
    if not key or key.strip() in ("", "<your-ollama-cloud-key>"):
        raise SystemExit(
            "OLLAMA_CLOUD_API_KEY is missing. Add it at repo Settings -> "
            "Secrets and variables -> Actions -> New repository secret.")
    req = urllib.request.Request(
        f"{OLLAMA_BASE}/chat/completions",
        data=json.dumps({
            "model": MODEL,
            "messages": messages,
            "temperature": 0.2,
            "max_tokens": 8000,
            "response_format": {"type": "json_object"},
        }).encode(),
        method="POST",
        headers={"Authorization": f"Bearer {key}",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"Ollama Cloud -> {e.code}: {e.read().decode()[:500]}")


# ---------------------------------------------------------------- diff utils
def reviewable(path):
    if path.split("/")[-1] in SKIP_NAMES:
        return False
    if path.endswith(SKIP_SUFFIXES):
        return False
    return not any(part in SKIP_DIRS for part in (path,))


def valid_new_lines(patch):
    """Set of new-side line numbers present in a unified diff patch."""
    lines, new = set(), None
    for ln in (patch or "").splitlines():
        m = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", ln)
        if m:
            new = int(m.group(1))
            continue
        if new is None or ln.startswith("---") or ln.startswith("+++"):
            continue
        if ln.startswith("-"):
            continue
        lines.add(new)
        new += 1
    return lines


def snap_line(want, valid):
    """Snap a model-returned line to the nearest diff line within 3, else None."""
    if want in valid:
        return want
    for d in (1, 2, 3):
        for cand in (want - d, want + d):
            if cand in valid:
                return cand
    return None


def get_file_content(repo, path, ref):
    try:
        d = gh_api(f"/repos/{repo}/contents/{path}?ref={ref}")
    except SystemExit:
        return None
    if isinstance(d, dict) and d.get("encoding") == "base64":
        try:
            return base64.b64decode(d["content"]).decode("utf-8", "replace")
        except Exception:
            return None
    return None


# ---------------------------------------------------------------- main
def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    with open(os.environ["GITHUB_EVENT_PATH"]) as f:
        event = json.load(f)

    pr_number = os.environ.get("PR_NUMBER") or event["pull_request"]["number"]
    pr = gh_api(f"/repos/{repo}/pulls/{pr_number}")
    head_sha = pr["head"]["sha"]
    if any(l["name"] == SKIP_LABEL for l in pr.get("labels", [])):
        print(f"PR has '{SKIP_LABEL}' label; skipping.")
        return 0

    files, page = [], 1
    while True:
        batch = gh_api(f"/repos/{repo}/pulls/{pr_number}/files?per_page=100&page={page}")
        if not batch:
            break
        files.extend(batch)
        page += 1

    changed = [f for f in files
               if f["status"] != "removed" and reviewable(f["filename"])]
    if not changed:
        print("No reviewable files changed; skipping.")
        return 0

    total_patch = sum(len(f.get("patch") or "") for f in changed)
    full_file_mode = total_patch <= FULL_FILE_BUDGET_CHARS
    print(f"{len(changed)} reviewable files, "
          f"{'full-file' if full_file_mode else 'diff-only'} mode "
          f"({total_patch} patch chars)")

    sections = []
    for f in changed:
        path, patch = f["filename"], f.get("patch") or ""
        if full_file_mode and f["status"] != "renamed":
            content = get_file_content(repo, path, head_sha)
        else:
            content = None
        if content and len(content) <= MAX_FILE_CHARS:
            sections.append(f"=== FILE {path} (full, new version) ===\n{content}")
        else:
            shown = patch[:MAX_FILE_CHARS]
            trunc = "\n[... truncated ...]" if len(patch) > MAX_FILE_CHARS else ""
            sections.append(f"=== DIFF {path} ===\n{shown}{trunc}")
    context = "\n\n".join(sections)

    prompt = REVIEW_PROMPT.format(repo=repo, context=context)
    resp = ollama_chat([{"role": "user", "content": prompt}])
    try:
        findings = json.loads(resp["choices"][0]["message"]["content"])
    except (KeyError, json.JSONDecodeError) as e:
        raise SystemExit(f"Could not parse model output as JSON: {e}")

    valid = {f["filename"]: valid_new_lines(f.get("patch")) for f in changed}
    inline, file_level = [], []
    for c in findings.get("comments", [])[:MAX_COMMENTS]:
        path, line = c.get("path"), c.get("line")
        body = (c.get("body") or "").strip()
        if c.get("suggestion"):
            body += f"\n\n```suggestion\n{c['suggestion'].strip()}\n```"
        if not path or not body:
            continue
        if path not in valid:
            file_level.append(f"**{path}**: {body}")
            continue
        snapped = snap_line(line, valid[path]) if isinstance(line, int) else None
        if snapped is None:
            file_level.append(f"**{path}**: {body}")
        else:
            inline.append({"path": path, "line": snapped,
                           "side": "RIGHT", "body": body})

    summary = findings.get("summary", "Automated review complete.")
    body_lines = [summary, "", f"<sub>Reviewed by `{MODEL}` via Ollama Cloud.</sub>"]
    if file_level:
        body_lines += ["", "**File-level notes:**"] + [f"- {n}" for n in file_level]
    review_body = "\n".join(body_lines)

    if not inline and not file_level:
        review_body = ("Ollama review: no issues found.\n\n"
                       f"<sub>Reviewed by `{MODEL}` via Ollama Cloud.</sub>")

    result = gh_api(f"/repos/{repo}/pulls/{pr_number}/reviews", "POST", {
        "commit_id": head_sha,
        "event": "COMMENT",
        "body": review_body,
        "comments": inline,
    })
    print(f"Posted review {result.get('id')} with {len(inline)} inline comments.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
