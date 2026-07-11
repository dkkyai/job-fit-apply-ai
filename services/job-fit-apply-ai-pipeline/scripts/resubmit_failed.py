#!/usr/bin/env python3
"""Resubmit saved job descriptions that never made it to the bridge."""

import json
import os
import re
import sys
from pathlib import Path

BRIDGE_URL = "http://127.0.0.1:8765"
OUTPUT_DIR = Path(__file__).parent.parent / "output"
DATE_PREFIX = "20260608"

# Words that typically start a role title rather than a company name.
# Used to split the directory slug into company + role.
ROLE_START_WORDS = {
    "senior", "sr", "principal", "staff", "lead", "junior", "jr",
    "software", "embedded", "test", "qa", "quality", "sdet", "sde",
    "developer", "engineer", "architect", "manager", "analyst",
    "infrastructure", "platform", "automation", "backend", "frontend",
    "fullstack", "full", "application", "applications", "mobile",
    "devops", "reliability", "site", "data", "ml", "ai", "cloud",
    "remote", "contract", "manual", "navigation", "product",
}


def split_slug(slug: str) -> tuple[str, str]:
    """Best-effort split of a company_role slug into (company, role)."""
    words = slug.split("_")
    for i in range(1, len(words)):
        if words[i].lower() in ROLE_START_WORDS:
            company = " ".join(words[:i]).title()
            role = " ".join(words[i:]).title()
            return company, role
    # Fallback: first word is company, rest is role
    company = words[0].title()
    role = " ".join(words[1:]).title() if len(words) > 1 else slug.title()
    return company, role


def load_dirs():
    dirs = sorted(OUTPUT_DIR.glob(f"{DATE_PREFIX}_*"))
    return [d for d in dirs if d.is_dir() and (d / "job_description.txt").exists()]


def parse_slug(dir_name: str) -> str:
    """Strip the timestamp prefix YYYYMMDD_HHMMSS_ and return the rest."""
    return re.sub(r"^\d{8}_\d{6}_", "", dir_name)


def submit_batch(batch: list[dict]) -> list[dict]:
    import urllib.request
    data = json.dumps(batch).encode()
    req = urllib.request.Request(
        f"{BRIDGE_URL}/api/jobs/batch",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def main():
    dirs = load_dirs()
    print(f"Found {len(dirs)} output dirs from {DATE_PREFIX}")

    submissions = []
    skipped = 0
    for d in dirs:
        jd_text = (d / "job_description.txt").read_text(encoding="utf-8").strip()
        if len(jd_text) < 150:
            print(f"  SKIP (too short): {d.name}")
            skipped += 1
            continue
        slug = parse_slug(d.name)
        company, role = split_slug(slug)
        # Idempotency key includes company+role so each unique posting is distinct.
        ikey = f"{DATE_PREFIX}_{slug}"
        submissions.append({
            "jd_text": jd_text,
            "company": company,
            "role_title": role,
            "idempotency_key": ikey,
            "source": "EMAIL",
        })

    print(f"Submitting {len(submissions)} jobs ({skipped} skipped as too short)...")

    CHUNK = 20
    total_new = 0
    total_deduped = 0
    for i in range(0, len(submissions), CHUNK):
        chunk = submissions[i : i + CHUNK]
        try:
            results = submit_batch(chunk)
            new = sum(1 for r in results if not r.get("deduped"))
            duped = sum(1 for r in results if r.get("deduped"))
            total_new += new
            total_deduped += duped
            print(f"  Chunk {i//CHUNK + 1}: {new} enqueued, {duped} deduped")
        except Exception as e:
            print(f"  ERROR on chunk {i//CHUNK + 1}: {e}", file=sys.stderr)

    print(f"\nDone — {total_new} new jobs enqueued, {total_deduped} deduplicated")


if __name__ == "__main__":
    main()
