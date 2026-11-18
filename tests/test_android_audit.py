"""End-to-end synthetic audit of an entire Markdown library and journal."""
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

from ksys_android_bridge import (
    full_library_audit, full_audit_page, inspect_note,
    preview_fragment, record_decision,
)


class FullAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.vault = self.base / "synthetic_notes"
        self.vault.mkdir()
        self.workspace = self.base / "private"
        self.positive = "# Statement\nАлекс является разработчиком.\n"
        self.negative = "# Counterstatement\nАлекс не является разработчиком.\n"
        (self.vault / "a.md").write_text(self.positive, encoding="utf-8")
        (self.vault / "b.markdown").write_text(self.negative, encoding="utf-8")

    def _audit(self, callback=None):
        return json.loads(full_library_audit(str(self.workspace), str(self.vault), callback))["summary"]

    def _page(self, category, offset=0):
        return json.loads(full_audit_page(str(self.workspace), category, offset))

    def _approve_fact(self, path, source):
        inspected = json.loads(inspect_note(path, source))
        fragment_id = inspected["fragments"][1]["id"]
        packet = json.loads(preview_fragment(str(self.workspace), path, source, fragment_id))
        reading = packet["preview"]["options"][0]["reading_id"]
        record_decision(str(self.workspace), path, source, fragment_id,
                        reading, "fact", packet["source_hash"], packet["journal_head"])

    def test_source_conflicts_are_not_authoritative_and_notes_unchanged(self):
        before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in self.vault.iterdir() if p.is_file()}
        summary = self._audit()
        self.assertTrue(summary["scan_complete"])
        self.assertEqual("VERIFIED", summary["journal_integrity"])
        self.assertEqual(1, summary["counts"]["source_conflicts"])
        self.assertEqual(0, summary["counts"]["authoritative_conflicts"])
        self.assertEqual(2, summary["indexed_documents"])
        self.assertTrue(summary["no_findings_does_not_mean_consistent"])
        self.assertFalse((self.workspace / "data").exists())
        conflicts = self._page("source_conflicts")
        self.assertEqual(1, len(conflicts["findings"]))
        item = conflicts["findings"][0]
        self.assertEqual("possible_source_conflict", item["type"])
        self.assertEqual({"a.md"}, {x["path"] for x in item["positive_sources"]})
        self.assertEqual({"b.markdown"}, {x["path"] for x in item["negative_sources"]})
        self.assertFalse(conflicts["has_more"])
        self.assertEqual(before, {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in self.vault.iterdir() if p.is_file()})

    def test_confirmed_contradiction_is_separately_audited(self):
        self._approve_fact("a.md", self.positive)
        self._approve_fact("b.markdown", self.negative)
        summary = self._audit()
        self.assertEqual(1, summary["counts"]["source_conflicts"])
        self.assertGreaterEqual(summary["counts"]["authoritative_conflicts"], 1)
        page = self._page("authoritative_conflicts")
        self.assertTrue(page["findings"])
        self.assertEqual("authoritative_conflict", page["findings"][0]["type"])
        self.assertTrue(page["findings"][0]["positive"])
        self.assertTrue(page["findings"][0]["negative"])

    def test_unresolved_ambiguous_and_unparsed_are_distinct(self):
        (self.vault / "c.md").write_text(
            "# Review\nАлекс держит магазин.\nОн владеет магазином.\n"
            "Неповторимая абстракция об искусственном примере и мысленном опыте.\n",
            encoding="utf-8")
        summary = self._audit()
        self.assertGreaterEqual(summary["counts"]["ambiguous"], 1)
        self.assertGreaterEqual(summary["counts"]["unresolved"], 1)
        self.assertGreaterEqual(summary["counts"]["unparsed"], 1)
        for name in ("ambiguous", "unresolved", "unparsed"):
            page = self._page(name)
            self.assertTrue(page["findings"])
            self.assertTrue(all(row["path"] == "c.md" for row in page["findings"]))
            self.assertTrue(all(row["fragment_id"] and row["locator"]
                                for row in page["findings"]))

    def test_skipped_and_stale_sources_prevent_completeness_claim(self):
        self._audit()
        (self.vault / "b.markdown").unlink()
        (self.vault / "large.md").write_bytes(b"x" * 2_000_001)
        summary = self._audit()
        self.assertFalse(summary["scan_complete"])
        self.assertEqual(1, summary["not_seen_in_latest_scan"])
        self.assertEqual(1, summary["skipped_this_scan"])
        self.assertEqual("SKIPPED_SIZE_LIMIT", summary["skipped_examples"][0]["status"])
        self.assertTrue(summary["no_findings_does_not_mean_consistent"])

    def test_invalid_private_journal_does_not_hide_source_findings(self):
        self._approve_fact("a.md", self.positive)
        journal = self.workspace / "data" / "formal" / "journal.jsonl"
        original = journal.read_text(encoding="utf-8")
        journal.write_text(original.replace('"actor":"human:me"', '"actor":"changed"'),
                           encoding="utf-8")
        summary = self._audit()
        self.assertEqual("INVALID_OR_UNREADABLE", summary["journal_integrity"])
        self.assertIsNone(summary["counts"]["authoritative_conflicts"])
        self.assertEqual(1, summary["counts"]["source_conflicts"])
        with self.assertRaises(ValueError):
            self._page("authoritative_conflicts")
        self.assertEqual(original.replace('"actor":"human:me"', '"actor":"changed"'),
                         journal.read_text(encoding="utf-8"))

    def test_changing_vault_clears_only_disposable_source_index(self):
        self._approve_fact("a.md", self.positive)
        self._audit()
        journal = self.workspace / "data" / "formal" / "journal.jsonl"
        original = journal.read_bytes()
        other = self.base / "other_vault"
        other.mkdir()
        (other / "fresh.md").write_text("# Other\nБорис является дизайнером.\n",
                                        encoding="utf-8")
        switched = json.loads(full_library_audit(str(self.workspace), str(other)))["summary"]
        self.assertEqual(1, switched["indexed_documents"])
        self.assertEqual(0, switched["counts"]["source_conflicts"])
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            self.assertEqual([("fresh.md",)], con.execute(
                "SELECT path FROM documents").fetchall())
        self.assertEqual(original, journal.read_bytes())

    def test_cancellation_preserves_previous_audit_projection(self):
        self._audit()
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            before = con.execute("SELECT path, sha256 FROM documents ORDER BY path").fetchall()
        (self.vault / "a.md").write_text("# Updated\nБорис является дизайнером.\n",
                                         encoding="utf-8")

        class Cancel:
            def onProgress(self, count):
                return count < 2

        with self.assertRaises(InterruptedError):
            self._audit(Cancel())
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            self.assertEqual(before, con.execute(
                "SELECT path, sha256 FROM documents ORDER BY path").fetchall())

    def test_empty_library_pages_and_invalid_page_args(self):
        for source in self.vault.iterdir():
            source.unlink()
        summary = self._audit()
        self.assertTrue(summary["scan_complete"])
        self.assertEqual(0, summary["indexed_fragments"])
        self.assertEqual([], self._page("source_conflicts")["findings"])
        self.assertEqual([], self._page("knowledge_gaps")["findings"])
        for category, offset in (("unknown", 0), ("ambiguous", -1),
                                 ("source_conflicts", 1_000_001)):
            with self.assertRaises(ValueError):
                full_audit_page(str(self.workspace), category, offset)


if __name__ == "__main__":
    unittest.main()
