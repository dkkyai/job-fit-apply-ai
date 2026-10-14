"""Unit tests for gen_employers.py — run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import csv
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_employers  # noqa: E402


def row(**kw):
    base = {"company": "Acme", "category": "Seattle Software / Internet", "careers_url": "https://acme.example/careers",
            "ats": "", "ats_id": "", "api_endpoint": "", "api_status": "200", "qe_relevance": "high"}
    base.update(kw)
    return base


class EntryTest(unittest.TestCase):
    def test_board_platforms_take_the_token_from_the_api_endpoint(self):
        cases = {
            "greenhouse": "https://boards-api.greenhouse.io/v1/boards/impinjexternal/jobs",
            "lever": "https://api.lever.co/v0/postings/palantir?mode=json",
            "ashby": "https://api.ashbyhq.com/posting-api/job-board/MotherDuck",
        }
        expected = {"greenhouse": "impinjexternal", "lever": "palantir", "ashby": "MotherDuck"}
        for ats, url in cases.items():
            e = gen_employers.entry(row(ats=ats, api_endpoint=url))
            self.assertEqual(expected[ats], e["board"], ats)
            self.assertNotIn("enabled", e, ats)

    def test_workday_parses_host_tenant_and_site_from_the_post_endpoint(self):
        e = gen_employers.entry(row(ats="workday", api_endpoint="POST https://boeing.wd1.myworkdayjobs.com/wday/cxs/boeing/EXTERNAL_CAREERS/jobs"))
        self.assertEqual(("boeing.wd1.myworkdayjobs.com", "boeing", "EXTERNAL_CAREERS"), (e["host"], e["tenant"], e["site"]))

    def test_rss_platforms_become_the_rss_adapter_and_avature_gets_a_detail_selector(self):
        e = gen_employers.entry(row(ats="avature", api_endpoint="https://careers.lululemon.com/en_US/careers/SearchCareer/feed/"))
        self.assertEqual(("rss", "avature"), (e["ats"], e["platform"]))
        self.assertEqual(".article__content--rich-text", e["detail_selector"])
        e = gen_employers.entry(row(ats="taleo_tbe", api_endpoint="https://phf.tbe.taleo.net/phf02/ats/servlet/Rss?org=COSTCO&cws=41"))
        self.assertEqual("rss", e["ats"])
        self.assertNotIn("detail_selector", e)

    def test_unverified_or_unparseable_feeds_are_disabled(self):
        self.assertFalse(gen_employers.entry(row(ats="greenhouse", api_endpoint="https://boards-api.greenhouse.io/v1/boards/x/jobs", api_status="403"))["enabled"])
        self.assertFalse(gen_employers.entry(row(ats="workday", api_endpoint=""))["enabled"])

    def test_platforms_without_an_adapter_stay_listed_and_enabled(self):
        e = gen_employers.entry(row(ats="icims", api_endpoint="https://careers.example.com/api/jobs"))
        self.assertEqual("icims", e["ats"])
        self.assertNotIn("enabled", e)


class MainTest(unittest.TestCase):
    def test_writes_every_employer_sorted_with_quoted_scalars(self):
        rows = [row(company='Zeta "Z" Co', ats="custom"), row(company="alpha", ats="lever", api_endpoint="https://api.lever.co/v0/postings/alpha?mode=json")]
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        try:
            out = io.StringIO()
            with redirect_stdout(out):
                gen_employers.main(fh.name)
        finally:
            os.unlink(fh.name)
        text = out.getvalue()
        self.assertLess(text.index('- company: "alpha"'), text.index('- company: "Zeta \\"Z\\" Co"'))
        self.assertIn('  board: "alpha"', text)


if __name__ == "__main__":
    unittest.main()
