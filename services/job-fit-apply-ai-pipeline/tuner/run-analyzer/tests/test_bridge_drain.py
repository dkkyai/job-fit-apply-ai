"""BridgeClient.drain paging — hermetic (fetch_completed is stubbed, no HTTP).

drain() is how analyze.py consumes the completed-event feed; these pin its paging rules:
advance to the max completed_seq of each page (the feed is sparse), stop on an empty or
short page, and return every record once in feed order.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from analyzer.bridge import BridgeClient  # noqa: E402


class _PagedBridge(BridgeClient):
    """Serves a fixed feed through fetch_completed, recording each call."""

    def __init__(self, seqs):
        super().__init__("http://bridge.invalid")
        self.feed = [{"job_id": f"j{s}", "completed_seq": s} for s in seqs]
        self.calls = []

    def fetch_completed(self, since, limit=200, all=True):
        self.calls.append((since, limit, all))
        return [r for r in self.feed if r["completed_seq"] > since][:limit]


class DrainTest(unittest.TestCase):
    def test_pages_until_a_short_page_and_returns_all_records(self):
        b = _PagedBridge([2, 3, 5, 8, 13])           # sparse seqs
        records, last = b.drain(0, page_size=2)
        self.assertEqual([r["completed_seq"] for r in records], [2, 3, 5, 8, 13])
        self.assertEqual(last, 13)
        self.assertEqual(b.calls, [(0, 2, True), (3, 2, True), (8, 2, True)])

    def test_exact_multiple_of_page_size_stops_on_the_empty_page(self):
        b = _PagedBridge([1, 2, 3, 4])
        records, last = b.drain(0, page_size=2)
        self.assertEqual(len(records), 4)
        self.assertEqual(last, 4)
        self.assertEqual([c[0] for c in b.calls], [0, 2, 4])

    def test_nothing_new_returns_the_cursor_unchanged(self):
        b = _PagedBridge([1, 2])
        self.assertEqual(b.drain(2), ([], 2))
        self.assertEqual(len(b.calls), 1)

    def test_resumes_strictly_after_since(self):
        b = _PagedBridge([4, 5, 6])
        records, last = b.drain(4)
        self.assertEqual([r["completed_seq"] for r in records], [5, 6])
        self.assertEqual(last, 6)


if __name__ == "__main__":
    unittest.main()
