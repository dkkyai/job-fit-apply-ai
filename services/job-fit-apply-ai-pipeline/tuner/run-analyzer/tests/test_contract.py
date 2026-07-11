"""Contract tests against LIVE services — guard the analyzer's assumptions vs. real schemas.

These catch **drift** between what the analyzer reads and what the bridge feed / Postgres
`tracks` table actually provide (e.g. a renamed `fit_score`, a dropped `output_path`). They are
NOT hermetic: they **skip cleanly** when the services aren't reachable, so CI's
`unittest discover` run passes, and they run locally / on the host where `jd-bridge` and
`jobfit-db` are up. Run them there before trusting a schema change:

    python3 -m unittest discover -s tests -p 'test_*.py'
"""

import os
import subprocess
import sys
import unittest
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from analyzer.bridge import BridgeClient  # noqa: E402
from analyzer.sources import join_window  # noqa: E402

BRIDGE_URL = os.environ.get("JD_BRIDGE_URL", "http://127.0.0.1:8765")
PG_CONTAINER = os.environ.get("JD_DB_CONTAINER", "jobfit-db")
PG_USER = os.environ.get("POSTGRES_USER", "jobfit")
PG_DB = os.environ.get("POSTGRES_DB", "jobfit")


def _bridge_up():
    try:
        urllib.request.urlopen(f"{BRIDGE_URL}/api/jobs/completed/head", timeout=3)
        return True
    except Exception:  # noqa: BLE001
        return False


def _psql(sql):
    return subprocess.run(
        ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB, "-tAX", "-c", sql],
        capture_output=True, text=True, timeout=15)


def _db_up():
    try:
        p = _psql("SELECT 1")
        return p.returncode == 0 and p.stdout.strip() == "1"
    except Exception:  # noqa: BLE001
        return False


@unittest.skipUnless(_bridge_up(), "jd-bridge not reachable")
class BridgeFeedContract(unittest.TestCase):
    """The bridge completed-feed must still carry the fields sources.join_window reads."""

    SPINE = {"job_id", "completed_seq", "status"}
    # Downstream (metrics/detectors/audit) read these from the JOINED record.
    JOINED_KEYS = {"jobId", "score", "action", "error", "jdTextLen", "board",
                   "isDigest", "company", "job_url", "run_log_missing"}

    def setUp(self):
        self.bridge = BridgeClient(BRIDGE_URL)
        self.sample = self.bridge.fetch_last(50)

    def test_head_returns_int_seq(self):
        self.assertIsInstance(self.bridge.head_seq(), int)

    def test_spine_keys_present_on_every_record(self):
        if not self.sample:
            self.skipTest("no completed jobs on the feed yet")
        for r in self.sample:
            self.assertLessEqual(self.SPINE, set(r), f"feed record missing spine keys: {r}")

    def test_join_preserves_mapped_fields(self):
        # join_window uses .get() with defaults, so a RENAME would silently yield a default.
        # Assert that when a field IS present on the raw record, the join maps it through —
        # this is what catches fit_score/pipeline_action/company/job_url being renamed.
        if not self.sample:
            self.skipTest("no completed jobs on the feed yet")
        joined = {j["jobId"]: j for j in join_window(self.sample, {})}
        checked = 0
        for raw in self.sample:
            j = joined[raw["job_id"]]
            if "fit_score" in raw:
                self.assertEqual(j["score"], raw["fit_score"]); checked += 1
            if "pipeline_action" in raw:
                self.assertEqual(j["action"], raw["pipeline_action"]); checked += 1
            if "company" in raw:
                self.assertEqual(j["company"], raw["company"]); checked += 1
            if "job_url" in raw:
                self.assertEqual(j["job_url"], raw["job_url"]); checked += 1
        self.assertGreater(checked, 0, "no mappable fields on any record — feed shape suspicious")

    def test_joined_record_has_downstream_keys(self):
        if not self.sample:
            self.skipTest("no completed jobs on the feed yet")
        for j in join_window(self.sample, {}):
            self.assertLessEqual(self.JOINED_KEYS, set(j))


@unittest.skipUnless(_db_up(), "jobfit-db not reachable")
class TracksSchemaContract(unittest.TestCase):
    """Postgres `tracks` must keep the columns the analyzer's audit depends on."""

    # Columns the analyzer actually relies on: fetch_track filters on job_url/email_id,
    # orders by created_at, and reads output_path. A rename here silently breaks the audit.
    REQUIRED = {"job_url", "email_id", "output_path", "created_at"}

    def test_required_columns_present(self):
        p = _psql("SELECT column_name FROM information_schema.columns WHERE table_name='tracks'")
        cols = {c.strip() for c in p.stdout.splitlines() if c.strip()}
        self.assertTrue(cols, "could not read tracks columns")
        missing = self.REQUIRED - cols
        self.assertFalse(missing, f"tracks is missing analyzer-required columns: {missing}")

    def test_audit_fetch_roundtrips(self):
        # The audit's dollar-quoted fetch_track must still return a row for a real job_url.
        p = _psql("SELECT job_url FROM tracks WHERE job_url IS NOT NULL AND job_url <> '' "
                  "ORDER BY created_at DESC LIMIT 1")
        url = p.stdout.strip()
        if not url:
            self.skipTest("no tracks rows with a job_url")
        from analyzer.audit import fetch_track
        self.assertIsNotNone(fetch_track(job_url=url), "fetch_track no longer round-trips a real job_url")


if __name__ == "__main__":
    unittest.main()
