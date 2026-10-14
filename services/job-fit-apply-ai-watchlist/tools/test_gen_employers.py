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

    def test_eightfold_is_recognised_by_url_whatever_the_ats_column_says(self):
        e = gen_employers.entry(row(ats="icims", api_endpoint="https://searchjobs.libertymutualgroup.com/api/apply/v2/jobs?domain=libertymutual.com&location=Seattle%2C%20Washington&start=0&num=50"))
        self.assertEqual({"ats": "eightfold", "api": "v2", "host": "searchjobs.libertymutualgroup.com", "domain": "libertymutual.com", "location": "Seattle, Washington"},
                         {k: e[k] for k in ("ats", "api", "host", "domain", "location")})
        e = gen_employers.entry(row(ats="oracle_hcm", api_endpoint="https://fortive.eightfold.ai/api/pcsx/search?domain=fortive.com&query=test&location=Everett%2C%20WA&start=0"))
        self.assertEqual(("eightfold", "pcsx", "Everett, WA"), (e["ats"], e["api"], e["location"]))
        self.assertNotIn("query", str(e), "search terms come from config, not the research URL")

    def test_oracle_takes_host_site_and_area_cities_from_the_location_column(self):
        e = gen_employers.entry(row(ats="oracle_hcm", seattle_area_location="Renton (HQ) / Redmond / Seattle",
                                    api_endpoint="https://evac.fa.us2.oraclecloud.com/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true&finder=findReqs;siteNumber=CX_1,keyword=%22test%22"))
        self.assertEqual(("oracle_hcm", "evac.fa.us2.oraclecloud.com", "CX_1", ["Seattle", "Redmond", "Renton"]), (e["ats"], e["host"], e["site"], e["search"]))
        e = gen_employers.entry(row(ats="oracle_hcm", seattle_area_location="IT remote",
                                    api_endpoint="https://x.fa.us2.oraclecloud.com/hcmRestApi/resources/latest/recruitingCEJobRequisitions?finder=findReqs;siteNumber=CX_1"))
        self.assertNotIn("search", e, "no area city named → adapter default list")

    def test_smartrecruiters_jibe_and_amazon(self):
        e = gen_employers.entry(row(ats="smartrecruiters", api_endpoint="https://api.smartrecruiters.com/v1/companies/ServiceNow/postings?q=Kirkland"))
        self.assertEqual(("smartrecruiters", "ServiceNow"), (e["ats"], e["board"]))
        e = gen_employers.entry(row(ats="icims", api_endpoint="https://careers.amd.com/api/jobs?location=Bellevue,%20WA&stretch=30&stretchUnit=MILES&page=1&keywords=x&limit=5"))
        self.assertEqual("jibe", e["ats"])
        self.assertEqual("https://careers.amd.com/api/jobs?location=Bellevue%2C+WA&stretch=30&stretchUnit=MILES", e["feed"], "only location filters survive")
        e = gen_employers.entry(row(ats="custom", api_endpoint="https://www.amazon.jobs/en/search.json?base_query=test&normalized_state_name[]=Washington"))
        self.assertEqual("amazon", e["ats"])
        self.assertNotIn("enabled", e)

    def test_rate_limited_during_research_still_counts_as_reachable(self):
        e = gen_employers.entry(row(ats="eightfold", api_status="429", api_endpoint="https://apply.careers.microsoft.com/api/pcsx/search?domain=microsoft.com&location=Redmond%2C%20WA"))
        self.assertNotIn("enabled", e)

    def test_unverified_or_unparseable_feeds_are_disabled(self):
        self.assertFalse(gen_employers.entry(row(ats="greenhouse", api_endpoint="https://boards-api.greenhouse.io/v1/boards/x/jobs", api_status="403"))["enabled"])
        self.assertFalse(gen_employers.entry(row(ats="workday", api_endpoint=""))["enabled"])

    def test_platforms_without_an_adapter_stay_listed_and_enabled(self):
        e = gen_employers.entry(row(ats="icims", api_endpoint="https://careers-x.icims.com/jobs/search?ss=1&in_iframe=1"))
        self.assertEqual("icims", e["ats"])
        self.assertNotIn("enabled", e)


class CommittedYamlTest(unittest.TestCase):
    def test_committed_employers_yaml_matches_the_generator_output(self):
        here = os.path.dirname(os.path.abspath(__file__))
        out = io.StringIO()
        with redirect_stdout(out):
            gen_employers.main(os.path.join(here, "employers.csv"))
        with open(os.path.join(here, "..", "src", "main", "resources", "employers.yaml"), encoding="utf-8") as fh:
            self.assertEqual(fh.read(), out.getvalue(),
                             "employers.yaml is stale: run python3 -I tools/gen_employers.py tools/employers.csv > src/main/resources/employers.yaml")


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
