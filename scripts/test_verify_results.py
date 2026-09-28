#!/usr/bin/env python3
"""Tests for scripts/verify_results.py — the comparator `make verify` relies on to call a
refactor behaviour-preserving. Run: python3 scripts/test_verify_results.py"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "verify_results.py"


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)


def write_run(root: Path, name: str, rows: list[tuple[str, str, str, str]],
              steps: list[tuple[str, str, str]] = ()) -> Path:
    d = root / name
    d.mkdir()
    (d / "results.tsv").write_text("".join("\t".join(r) + "\n" for r in rows))
    (d / "steps.tsv").write_text("".join(f"{lv}\t{st}\t{code}\t1\n" for lv, st, code in steps))
    return d


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def compare(self, base_rows, cand_rows, base_steps=(), cand_steps=(), expect=None):
        root = Path(tempfile.mkdtemp(dir=self.tmp))   # fresh dirs: a test may compare twice
        b = write_run(root, "b", base_rows, base_steps)
        c = write_run(root, "c", cand_rows, cand_steps)
        args = ["compare", str(b), str(c)]
        if expect is not None:
            f = root / "expect.txt"
            f.write_text(expect)
            args += ["--expect-removed", str(f)]
        return run(*args)

    def test_identical_runs_are_ok(self):
        rows = [("unit", "m", "t1", "passed"), ("unit", "m", "t2", "skipped")]
        self.assertEqual(self.compare(rows, rows).returncode, 0)

    def test_passed_to_skipped_is_a_regression(self):
        r = self.compare([("unit", "m", "t", "passed")], [("unit", "m", "t", "skipped")])
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("Status REGRESSED", r.stdout)

    def test_failed_to_skipped_is_a_regression(self):
        r = self.compare([("unit", "m", "t", "failed")], [("unit", "m", "t", "skipped")])
        self.assertEqual(r.returncode, 1)

    def test_move_to_passed_is_an_improvement(self):
        r = self.compare([("unit", "m", "t", "failed")], [("unit", "m", "t", "passed")])
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn("Status improved (now passed): 1", r.stdout)

    def test_added_failing_test_fails_the_comparison(self):
        r = self.compare([], [("unit", "m", "new", "failed")])
        self.assertEqual(r.returncode, 1)
        self.assertIn("Added tests that FAIL: 1", r.stdout)

    def test_added_passing_test_is_ok(self):
        self.assertEqual(self.compare([], [("unit", "m", "new", "passed")]).returncode, 0)

    def test_unlisted_removal_fails_listed_removal_passes(self):
        base = [("unit", "m", "gone", "passed")]
        self.assertEqual(self.compare(base, []).returncode, 1)
        self.assertEqual(self.compare(base, [], expect="m::gone\n").returncode, 0)

    def test_expect_pattern_matching_nothing_fails(self):
        rows = [("unit", "m", "t", "passed")]
        self.assertEqual(self.compare(rows, rows, expect="m::nope*\n").returncode, 1)

    def test_step_getting_worse_fails_step_improving_is_ok(self):
        rows = [("unit", "m", "t", "passed")]
        worse = self.compare(rows, rows, [("static", "tsc", "0")], [("static", "tsc", "2")])
        better = self.compare(rows, rows, [("static", "tsc", "2")], [("static", "tsc", "0")])
        self.assertEqual(worse.returncode, 1)
        self.assertEqual(better.returncode, 0)


class ConvertersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def rows(self, proc: subprocess.CompletedProcess) -> list[list[str]]:
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return [l.split("\t") for l in proc.stdout.splitlines()]

    def test_junit_duplicate_names_are_suffixed_in_run_order(self):
        # Same display name: case 1 fails, case 2 passes. Suffixes must follow run order, so
        # swapping which case fails changes the rows.
        def xml(first, second):
            return ('<testsuite><testcase classname="C" name="p"><failure/></testcase>'
                    '<testcase classname="C" name="p"/></testsuite>') if first == "failed" else (
                    '<testsuite><testcase classname="C" name="p"/>'
                    '<testcase classname="C" name="p"><failure/></testcase></testsuite>')
        a, b = self.tmp / "a.xml", self.tmp / "b.xml"
        a.write_text(xml("failed", "passed"))
        b.write_text(xml("passed", "failed"))
        ra = self.rows(run("junit", "--level", "u", "--module", "m", str(a)))
        rb = self.rows(run("junit", "--level", "u", "--module", "m", str(b)))
        self.assertEqual({r[2]: r[3] for r in ra}, {"C::p": "failed", "C::p#2": "passed"})
        self.assertEqual({r[2]: r[3] for r in rb}, {"C::p": "passed", "C::p#2": "failed"})

    def test_passlines_ignores_multi_word_summary(self):
        log = self.tmp / "log"
        log.write_text("PASS test_a\nFAIL test_b\nPASS 2 Compose data-root contract tests\n")
        rows = self.rows(run("passlines", "--level", "u", "--module", "compose", str(log)))
        self.assertEqual({r[2]: r[3] for r in rows}, {"test_a": "passed", "test_b": "failed"})

    def test_jest_empty_results_do_not_crash(self):
        f = self.tmp / "jest.json"
        f.write_text(json.dumps({"testResults": []}))
        self.assertEqual(self.rows(run("jest", "--level", "u", "--module", "ext", str(f))), [])

    def test_pyunit_records_a_test_whose_subtest_failed(self):
        pkg = self.tmp / "proj"
        (pkg / "tests").mkdir(parents=True)
        (pkg / "tests" / "test_sub.py").write_text(
            "import unittest\n"
            "class T(unittest.TestCase):\n"
            "    def test_sub(self):\n"
            "        for i in range(2):\n"
            "            with self.subTest(i=i):\n"
            "                self.assertEqual(i, 0)\n"
            "    def test_ok(self):\n"
            "        pass\n")
        out = self.tmp / "py.tsv"
        proc = run("pyunit", "--level", "u", "--module", "py", "--out", str(out), str(pkg))
        self.assertEqual(proc.returncode, 1)
        rows = {l.split("\t")[2]: l.split("\t")[3] for l in out.read_text().splitlines()}
        self.assertEqual(rows, {"test_sub.T.test_ok": "passed", "test_sub.T.test_sub": "failed"})


if __name__ == "__main__":
    unittest.main()
