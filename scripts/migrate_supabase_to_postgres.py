#!/usr/bin/env python3
"""
One-off data migration: Supabase (PostgREST) -> local Postgres container.

Pulls every row of `tracks` through the Supabase REST API (service-role key) and
loads it into the `jobfit-db` container, preserving ids. Uses jsonb_populate_recordset
so array / numeric / timestamp columns keep their types with no hand-rolled coercion.

Idempotent: TRUNCATEs `tracks` first, so re-running gives a clean copy.
`resume_tailoring` is intentionally skipped — it does not exist in the source DB.

Env required:
  SUPABASE_PROJECT_URL, SUPABASE_SERVICE_ROLE_KEY

Usage:
  export $(grep -E '^SUPABASE_(PROJECT_URL|SERVICE_ROLE_KEY)=' services/job-fit-apply-ai-pipeline/.env | xargs)
  python3 scripts/migrate_supabase_to_postgres.py
"""
import json
import os
import subprocess
import sys
import urllib.request

BASE = os.environ["SUPABASE_PROJECT_URL"].rstrip("/")
KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
TABLE = "tracks"
PAGE = 1000
CONTAINER = "jobfit-db"
PG = ["docker", "exec", "-i", CONTAINER, "psql", "-U", "jobfit", "-d", "jobfit",
      "-v", "ON_ERROR_STOP=1", "-q"]


def psql(script: str) -> None:
    r = subprocess.run(PG, input=script, text=True, capture_output=True)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit(f"psql failed (exit {r.returncode})")


def fetch_page(offset: int) -> list:
    url = f"{BASE}/rest/v1/{TABLE}?select=*&order=id.asc&limit={PAGE}&offset={offset}"
    req = urllib.request.Request(url, headers={
        "apikey": KEY,
        "Authorization": f"Bearer {KEY}",
    })
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.load(resp)


def load_page(rows: list) -> None:
    # Compact JSON is a single physical line (json.dumps escapes newlines/tabs inside
    # strings). Doubling backslashes makes it a safe COPY text-format field value.
    payload = json.dumps(rows, ensure_ascii=True).replace("\\", "\\\\")
    script = (
        "CREATE TEMP TABLE _imp (data jsonb);\n"
        "COPY _imp (data) FROM STDIN;\n"
        f"{payload}\n"
        "\\.\n"
        f"INSERT INTO {TABLE} "
        f"SELECT * FROM jsonb_populate_recordset(null::{TABLE}, (SELECT data FROM _imp));\n"
        "DROP TABLE _imp;\n"
    )
    psql(script)


def main() -> None:
    print(f"Truncating local {TABLE} ...")
    psql(f"TRUNCATE {TABLE} RESTART IDENTITY CASCADE;")

    offset, total = 0, 0
    while True:
        rows = fetch_page(offset)
        if not rows:
            break
        load_page(rows)
        total += len(rows)
        print(f"  loaded {total} rows (offset {offset})")
        if len(rows) < PAGE:
            break
        offset += PAGE

    # nextval should resume after the highest imported id
    psql(
        f"SELECT setval(pg_get_serial_sequence('{TABLE}', 'id'), "
        f"COALESCE((SELECT MAX(id) FROM {TABLE}), 1), "
        f"(SELECT COUNT(*) > 0 FROM {TABLE}));"
    )
    print(f"Done. Imported {total} rows into {TABLE}.")


if __name__ == "__main__":
    main()
