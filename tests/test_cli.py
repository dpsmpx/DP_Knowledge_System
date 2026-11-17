"""Smoke-тест CLI: все основные команды во временном рабочем пространстве."""
import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path

from ksys.cli.main import main
from ksys.demo.scenarios import DEMO_VAULT

HOME = Path(__file__).resolve().parents[1]


class CliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "home"
        os.environ["KS_NOW"] = "2026-01-01T00:00:00Z"

    def tearDown(self):
        os.environ.pop("KS_NOW", None)
        self.tmp.cleanup()

    def run_cli(self, *argv, expect=0):
        buf, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
            code = main(["--home", str(self.home), *argv])
        self.assertEqual(code, expect, (argv, buf.getvalue()[-500:], err.getvalue()[-500:]))
        return buf.getvalue()

    def test_end_to_end(self):
        self.run_cli("init")
        out = self.run_cli("import", str(DEMO_VAULT))
        self.assertIn("import_id IMP:", out)
        self.assertTrue((self.home / "reports" / "COVERAGE_REPORT.md").exists())
        self.assertIn("PREVIEW", self.run_cli("assert", "Алекс является разработчиком."))
        self.run_cli("assert", "Алекс является разработчиком.", "--yes")
        self.run_cli("assert", "Алекс работает с Python.", "--yes", "--confidence", "high")
        self.run_cli("assert", "Если X является разработчиком и X работает с Python, то X использует Python.", "--yes")
        self.assertIn("STATUS:    PROVEN", self.run_cli("query", "Использует ли Алекс Python?"))
        data = json.loads(self.run_cli("--json", "query", "Кто использует Python?"))
        self.assertEqual(data["answer"], "WHO=алекс")
        self.assertIn("UNKNOWN", self.run_cli("hypothesis", "Python лучше Java"))
        lst = json.loads(self.run_cli("--json", "review", "list"))
        self.assertTrue(lst)
        cand = lst[0]["id"]
        self.assertIn("ORIGINAL", self.run_cli("review", "show", cand))
        self.run_cli("review", "defer", cand)
        hist = json.loads(self.run_cli("--json", "history"))
        self.assertGreaterEqual(len(hist), 4)
        self.assertIn("records_added", self.run_cli("diff", hist[0]["id"], hist[-1]["id"]))
        self.run_cli("validate")
        self.run_cli("stats")
        self.run_cli("unknowns")
        self.run_cli("contradictions")
        self.run_cli("export", "--format", "ttl")
        manifest = self.home / "data" / "formal" / "export" / "jsonl" / "manifest.json"
        self.run_cli("export", "--format", "jsonl")
        self.assertIn("UP-TO-DATE", self.run_cli("stale", str(manifest)))
        self.run_cli("assert", "Чай полезен.", "--yes")
        self.assertIn("STALE", self.run_cli("stale", str(manifest), expect=1))
        self.run_cli("--json", "assert", "Кофе полезен.", "--yes")
        self.assertIn("SIMULATED", self.run_cli("what-if", "Кофе полезен."))
        self.run_cli("retract", "Кофе полезен.", "--yes")
        self.run_cli("rules", "load", str(HOME / "rules" / "core.ks"))
        self.assertIn("FORMAL", self.run_cli("formalize", "Стресс приводит к бессоннице."))
        self.assertIn("coverage_ok=True", self.run_cli("parse", str(DEMO_VAULT / "people" / "alex.md")))
        self.run_cli("reports")
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = main(["--home", str(self.home), "assert", "Алекс держит магазин.", "--yes", "--as", "fact"])
        self.assertEqual(code, 2)
        self.assertIn("SEMANTIC_AMBIGUITY", err.getvalue())


if __name__ == "__main__":
    unittest.main()
