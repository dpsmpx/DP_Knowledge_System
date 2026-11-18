"""Synthetic whole-vault semantic import: privacy, incremental safety, cancellation."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party" / "markor" / "app" / "src" / "main" / "python"))

from ksys_android_bridge import analyze_vault, review_queue, MAX_IMPORT_BYTES
from ksys.core.clock import FixedClock
from ksys.importer.language_analyzer import LanguageAnalyzer
from ksys.importer.vault import import_vault, MARKDOWN_EXT
from ksys.store.workspace import Workspace


class Counter:
    def __init__(self, stop_at=None):
        self.steps = []
        self.stop_at = stop_at

    def onProgress(self, count):
        self.steps.append(count)
        return self.stop_at is None or count < self.stop_at


class WholeVaultAndroidTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.vault = self.base / "user_notes"
        self.vault.mkdir()
        (self.vault / "nested").mkdir()
        (self.vault / ".obsidian").mkdir()
        (self.vault / "first.md").write_text(
            "# Hypothesis\nСтресс приводит к бессоннице.\n", encoding="utf-8")
        (self.vault / "nested" / "second.markdown").write_text(
            "# Evidence\nНедостаток сна приводит к усталости.\n", encoding="utf-8")
        (self.vault / "other.txt").write_text("Not part of the Markdown vault", encoding="utf-8")
        (self.vault / ".obsidian" / "hidden.md").write_text(
            "Secret app data stays excluded.", encoding="utf-8")
        self.workspace = self.base / "private_workspace"

    def _analyze(self, callback=None):
        return json.loads(analyze_vault(str(self.workspace), str(self.vault), callback))

    def _paths(self):
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            return {x[0] for x in con.execute("SELECT path FROM documents")}

    def _counts(self):
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            return (con.execute("SELECT COUNT(*) FROM fragments").fetchone()[0],
                    con.execute("SELECT COUNT(*) FROM candidates").fetchone()[0])

    def test_complete_markdown_analysis_does_not_touch_notes_or_journal(self):
        before = {p.relative_to(self.vault).as_posix():
                  hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in self.vault.rglob("*") if p.is_file()}
        progress = Counter()
        result = self._analyze(progress)
        self.assertEqual("dpksys.bulk.v1", result["protocol"])
        self.assertEqual(2, result["totals"]["files"])
        self.assertEqual({0, 1, 2}, set(progress.steps))
        self.assertEqual({"first.md", "nested/second.markdown"}, self._paths())
        self.assertGreater(result["totals"]["fragments"], 0)
        self.assertGreater(result["totals"]["candidates"], 0)
        self.assertTrue(result["requires_human_review"])
        self.assertFalse((self.workspace / "data" / "formal" / "journal.jsonl").exists())
        self.assertEqual(before, {p.relative_to(self.vault).as_posix():
                         hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in self.vault.rglob("*") if p.is_file()})

    def test_candidate_queue_pagination_and_incremental_rerun(self):
        first = self._analyze()
        before = self._counts()
        second = self._analyze()
        self.assertEqual(before, self._counts())
        self.assertEqual(first["totals"], second["totals"])
        queue = json.loads(review_queue(str(self.workspace), 0))
        self.assertEqual("dpksys.bulk.v1", queue["protocol"])
        self.assertEqual(before[1], queue["total"])
        self.assertTrue(all(row["path"] in self._paths() for row in queue["candidates"]))
        self.assertEqual([], json.loads(review_queue(str(self.workspace), queue["total"]))["candidates"])
        self.assertFalse((self.workspace / "data").exists())

    def test_missing_file_is_not_falsely_deleted(self):
        self._analyze()
        (self.vault / "nested" / "second.markdown").unlink()
        result = self._analyze()
        self.assertEqual(1, result["totals"]["files"])
        self.assertIn("nested/second.markdown", self._paths())

    def test_oversized_and_external_symlink_are_not_read(self):
        (self.vault / "huge.md").write_bytes(b"x" * (MAX_IMPORT_BYTES + 1))
        external = self.base / "not_in_vault.md"
        external.write_text("External private material", encoding="utf-8")
        (self.vault / "escape.md").symlink_to(external)
        result = self._analyze()
        self.assertEqual(4, result["totals"]["files"])
        self.assertEqual(1, result["totals"]["by_status"]["SKIPPED_SIZE_LIMIT"])
        self.assertEqual(1, result["totals"]["by_status"]["SKIPPED_OUTSIDE_VAULT"])
        self.assertFalse((self.workspace / "data").exists())

    def test_cancel_rolls_back_complete_batch(self):
        self._analyze()
        baseline = self._counts()
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            original_hash = con.execute(
                "SELECT sha256 FROM documents WHERE path='first.md'").fetchone()[0]
        (self.vault / "first.md").write_text(
            "# Changed\nБессонница приводит к усталости.\n", encoding="utf-8")
        with self.assertRaises(InterruptedError):
            self._analyze(Counter(stop_at=2))
        self.assertEqual(baseline, self._counts())
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            self.assertEqual(original_hash, con.execute(
                "SELECT sha256 FROM documents WHERE path='first.md'").fetchone()[0])
        self.assertEqual(2, len(self._paths()))

    def test_partial_include_never_reconciles_unseen_documents(self):
        ws = Workspace(self.workspace)
        import_vault(ws, self.vault, FixedClock(), LanguageAnalyzer(),
                     only_extensions=MARKDOWN_EXT, reconcile_missing=False)
        import_vault(ws, self.vault, FixedClock(), LanguageAnalyzer(),
                     include={"first.md"})
        self.assertIn("nested/second.markdown", self._paths())

    def test_missing_index_queue_is_empty(self):
        result = json.loads(review_queue(str(self.workspace), 0))
        self.assertEqual(0, result["total"])
        self.assertFalse(self.workspace.exists())


if __name__ == "__main__":
    unittest.main()
