"""Unit tests for the deterministic detectors (Phase B)."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from analyzer import detectors  # noqa: E402
from analyzer.findings import fingerprint  # noqa: E402


def _ids(findings):
    return sorted(f["id"] for f in findings)


class TestDetectors(unittest.TestCase):
    def test_oom_high(self):
        recs = [{"jobId": "j1", "error": "model runner has unexpectedly stopped"}]
        f = detectors.detect(recs)
        self.assertIn("ollama-oom", _ids(f))
        self.assertEqual(f[0]["severity"], "high")

    def test_timeouts(self):
        recs = [{"jobId": f"j{i}", "error": "request timed out"} for i in range(2)]
        self.assertIn("model-timeouts", _ids(detectors.detect(recs)))

    def test_run_log_missing_needs_share(self):
        # 1 of 8 missing -> below threshold -> no finding
        recs = [{"jobId": f"j{i}", "run_log_missing": i == 0} for i in range(8)]
        self.assertNotIn("run-log-missing", _ids(detectors.detect(recs)))
        # 4 of 8 missing -> fires
        recs = [{"jobId": f"j{i}", "run_log_missing": i < 4} for i in range(8)]
        self.assertIn("run-log-missing", _ids(detectors.detect(recs)))

    def test_tailor_after_error(self):
        recs = [{"jobId": "j1", "action": "TAILOR", "score": 70, "error": "render failed"}]
        self.assertIn("tailor-after-error", _ids(detectors.detect(recs)))

    def test_rich_jd_scored_zero(self):
        recs = [{"jobId": "j1", "score": 0, "jdTextLen": 2000},
                {"jobId": "j2", "score": 0, "jdTextLen": 50}]      # thin -> not this finding
        f = [x for x in detectors.detect(recs) if x["id"] == "rich-jd-scored-zero"]
        self.assertEqual(len(f), 1)
        self.assertEqual(f[0]["affected_jobs"], ["j1"])

    def test_zero_score_duplicate_excluded(self):
        recs = [{"jobId": "j1", "score": 0, "jdTextLen": 2000, "isDuplicate": True}]
        self.assertNotIn("rich-jd-scored-zero", _ids(detectors.detect(recs)))

    def test_per_board_scrape_blocked_grouped(self):
        recs = [{"jobId": "j1", "board": "glassdoor.com", "error": "bot-blocked captcha"},
                {"jobId": "j2", "board": "glassdoor.com", "error": "403 forbidden"},
                {"jobId": "j3", "board": "linkedin.com", "error": "captcha"}]
        ids = _ids(detectors.detect(recs))
        self.assertIn("scrape-blocked-glassdoor-com", ids)
        self.assertIn("scrape-blocked-linkedin-com", ids)   # one finding per board

    def test_per_board_thin_digest_needs_two(self):
        one = [{"jobId": "j1", "board": "indeed.com", "isDigest": True, "jdTextLen": 100}]
        self.assertNotIn("thin-digest-indeed-com", _ids(detectors.detect(one)))
        two = one + [{"jobId": "j2", "board": "indeed.com", "isDigest": True, "jdTextLen": 200}]
        self.assertIn("thin-digest-indeed-com", _ids(detectors.detect(two)))

    def test_clean_window_no_findings(self):
        recs = [{"jobId": "j1", "action": "SKIP", "score": 45, "jdTextLen": 1500}]
        self.assertEqual(detectors.detect(recs), [])

    def test_findings_are_fingerprint_stable(self):
        recs = [{"jobId": "j1", "error": "model runner has unexpectedly stopped"}]
        f1 = detectors.detect(recs)[0]
        f2 = detectors.detect(recs)[0]
        self.assertEqual(fingerprint(f1), fingerprint(f2))
        # every detector finding carries the fields the ledger/write need
        for key in ("id", "title", "severity", "category", "affected_jobs", "files", "agent_prompt"):
            self.assertIn(key, f1)


if __name__ == "__main__":
    unittest.main()
