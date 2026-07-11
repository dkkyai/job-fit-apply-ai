"""Unit tests for the cross-run findings ledger (Phase C dedup/delta)."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from analyzer import findings_ledger as FL  # noqa: E402


def _f(fid, sev, aff=1, cat="scoring"):
    return {"id": fid, "title": fid, "severity": sev, "category": cat,
            "affected_jobs": ["j"] * aff, "files": ["X.kt"]}


class TestClassify(unittest.TestCase):
    def setUp(self):
        self.led = Path(tempfile.mkdtemp()) / "fl.jsonl"

    def _ids(self, bucket):
        return sorted(f["id"] for f in bucket)

    def test_first_run_all_new(self):
        d = FL.classify([_f("a", "medium"), _f("b", "low")], self.led, "r1")
        self.assertEqual(self._ids(d["new"]), ["a", "b"])
        self.assertEqual(d["worsening"], [])
        self.assertEqual(d["unchanged"], [])
        self.assertEqual(len(self.led.read_text().splitlines()), 2)

    def test_unchanged_suppressed_second_run(self):
        FL.classify([_f("a", "medium")], self.led, "r1")
        d = FL.classify([_f("a", "medium")], self.led, "r2")
        self.assertEqual(d["new"], [])
        self.assertEqual(self._ids(d["unchanged"]), ["a"])

    def test_worsening_on_severity_increase(self):
        FL.classify([_f("b", "low")], self.led, "r1")
        d = FL.classify([_f("b", "high")], self.led, "r2")
        self.assertEqual(self._ids(d["worsening"]), ["b"])
        self.assertEqual(d["unchanged"], [])

    def test_worsening_on_more_affected_jobs(self):
        FL.classify([_f("c", "medium", aff=2)], self.led, "r1")
        d = FL.classify([_f("c", "medium", aff=5)], self.led, "r2")
        self.assertEqual(self._ids(d["worsening"]), ["c"])

    def test_not_worsening_when_fewer_affected(self):
        FL.classify([_f("c", "medium", aff=5)], self.led, "r1")
        d = FL.classify([_f("c", "medium", aff=2)], self.led, "r2")
        self.assertEqual(self._ids(d["unchanged"]), ["c"])

    def test_seen_count_and_first_seen_persist(self):
        FL.classify([_f("a", "medium")], self.led, "r1")
        FL.classify([_f("a", "medium")], self.led, "r2")
        FL.classify([_f("a", "medium")], self.led, "r3")
        latest = FL._load(self.led)[FL.fingerprint(_f("a", "medium"))]
        self.assertEqual(latest["seen_count"], 3)
        self.assertEqual(latest["first_seen_ts"], "r1")
        self.assertEqual(latest["last_seen_ts"], "r3")

    def test_load_tolerates_missing_and_garbled(self):
        self.assertEqual(FL._load(self.led), {})          # missing
        self.led.write_text("not json\n" + '{"fingerprint":"x","severity":"low"}\n')
        self.assertIn("x", FL._load(self.led))            # skips the bad line


if __name__ == "__main__":
    unittest.main()
