"""Cursor-linked, read-only knowledge journal inspection in Markdown editor."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import (analyze_vault, editor_source_records,
                                 inspect_note, preview_fragment, record_decision,
                                 retract_decision, preview_retraction)
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal


class EditorSourceInspectorTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.workspace = self.base / "private_workspace"
        self.vault = self.base / "synthetic_vault"
        self.vault.mkdir()
        self.markdown = ("# 😀\nАлекс является разработчиком.\n"
                         "Алекс является разработчиком.\n"
                         "Борис является писателем.\n")
        (self.vault / "a.md").write_text(self.markdown, encoding="utf-8")

    def inspect(self, start, end=None, markdown=None):
        return json.loads(editor_source_records(
            str(self.workspace), "a.md", self.markdown if markdown is None else markdown,
            start, start if end is None else end))

    def accept_second(self):
        fragment = json.loads(inspect_note("a.md", self.markdown))["fragments"][2]["id"]
        packet = json.loads(preview_fragment(str(self.workspace), "a.md",
                                             self.markdown, fragment))
        json.loads(record_decision(str(self.workspace), "a.md", self.markdown, fragment,
                                   packet["preview"]["options"][0]["reading_id"],
                                   "claim", packet["source_hash"], packet["journal_head"]))

    def test_absent_journal_and_unapproved_index_do_not_create_knowledge(self):
        pos = self.markdown.rindex("Алекс является")
        result = self.inspect(pos)
        self.assertEqual("dpksys.editor_source_records.v1", result["protocol"])
        self.assertTrue(result["only_verified_journal_sources"])
        self.assertEqual([], result["records"])
        self.assertFalse(self.workspace.exists())
        analyze_vault(str(self.workspace), str(self.vault))
        self.assertEqual([], self.inspect(pos)["records"])

    def test_caret_and_contained_selection_match_second_duplicate_only(self):
        self.accept_second()
        journal = self.workspace / "data/formal/journal.jsonl"
        journal_hash = hashlib.sha256(journal.read_bytes()).hexdigest()
        source_hash = hashlib.sha256((self.vault / "a.md").read_bytes()).hexdigest()
        second = self.markdown.rindex("Алекс является")
        first = self.markdown.index("Алекс является")
        self.assertEqual([], self.inspect(first)["records"])
        result = self.inspect(second + 5)
        self.assertEqual(1, result["record_total"])
        self.assertEqual(1, result["active_total"])
        self.assertEqual(0, result["historical_total"])
        row = result["records"][0]
        self.assertTrue(row["active"])
        self.assertEqual("verified", row["source_span_state"])
        self.assertEqual(second, row["source_span"][0])
        self.assertEqual(3, row["source_line"])
        self.assertEqual(1, row["source_column"])
        self.assertEqual(1, row["history_total"])
        self.assertEqual("accept", row["history"][0]["op"])
        self.assertEqual(1, self.inspect(second, second + 10)["record_total"])
        self.assertEqual(0, self.inspect(first, second + 10)["record_total"])
        self.assertEqual(0, self.inspect(row["source_span"][1])["record_total"])
        self.assertNotEqual(len(self.markdown[:second].encode("utf-16-le")) // 2,
                            second, "Editor converts UTF-16 cursor positions")
        self.assertEqual(journal_hash, hashlib.sha256(journal.read_bytes()).hexdigest())
        self.assertEqual(source_hash,
                         hashlib.sha256((self.vault / "a.md").read_bytes()).hexdigest())
        self.assertEqual([], Journal(journal, FixedClock()).verify())

    def test_obsolete_record_history_remains_visible_while_source_current(self):
        self.accept_second()
        position = self.markdown.rindex("Алекс является")
        active = self.inspect(position)["records"][0]
        preview = json.loads(preview_retraction(str(self.workspace), active["id"]))
        retract_decision(str(self.workspace), active["id"], preview["journal_head"])
        old = self.inspect(position)
        self.assertEqual(0, old["active_total"])
        self.assertEqual(1, old["historical_total"])
        record = old["records"][0]
        self.assertFalse(record["active"])
        self.assertGreaterEqual(record["history_total"], 1)
        self.assertEqual("verified", record["source_span_state"])
        stale = self.inspect(position, markdown=self.markdown + "Добавлено.")
        self.assertEqual([], stale["records"])
        self.assertGreater(stale["unverified_origins"], 0)

    def test_opposing_accepted_statement_is_labeled_as_possible_conflict(self):
        self.accept_second()
        other_note = "Алекс не является разработчиком.\n"
        fragment = json.loads(inspect_note("b.md", other_note))["fragments"][0]["id"]
        packet = json.loads(preview_fragment(str(self.workspace), "b.md", other_note,
                                             fragment))
        record_decision(str(self.workspace), "b.md", other_note, fragment,
                        packet["preview"]["options"][0]["reading_id"], "claim",
                        packet["source_hash"], packet["journal_head"])
        result = self.inspect(self.markdown.rindex("Алекс является"))
        record = result["records"][0]
        self.assertTrue(record["conflicts_available"])
        self.assertGreater(record["conflict_count"], 0)
        self.assertTrue(any(opponent["statement"] and opponent["id"] != record["id"]
                            for opponent in record["conflict_records"]))

    def test_invalid_selection_and_path_are_rejected(self):
        for a, b in ((-1, 2), (5, 4), (0, 99999), (True, 3), (1, False)):
            with self.assertRaises(ValueError):
                editor_source_records(str(self.workspace), "a.md", self.markdown, a, b)
        with self.assertRaises(ValueError):
            editor_source_records(str(self.workspace), "../outside.md", self.markdown, 0, 0)
        self.assertFalse(self.workspace.exists())

    def test_bounded_records_and_history_without_index_or_mutation(self):
        note, path = "synthetic", "a.md"
        sha = hashlib.sha256(note.encode("utf-8")).hexdigest()
        record_map = {}
        for i in range(20):
            rid = str(i)
            record_map[rid] = SimpleNamespace(
                id=rid, role="claim", status=SimpleNamespace(value="HUMAN_ACCEPTED"),
                updated_at="2026-11-19T00:00:00Z", journal=[],
                node=SimpleNamespace(ksf=lambda: "test"), provenance=[{
                    "document": path, "document_sha": sha, "span": [0, 9],
                    "text": note, "source_type": "vault_note"}])
        fake = SimpleNamespace(state=SimpleNamespace(records=record_map, entries=[],
                                                      active=lambda _: True),
                               journal=SimpleNamespace(head=None), conflicts_for=lambda _: [])
        with patch("ksys_android_bridge._kb", return_value=fake):
            result = json.loads(editor_source_records(str(self.workspace), path, note, 3, 3))
        self.assertEqual(20, result["record_total"])
        self.assertTrue(result["truncated"])
        self.assertEqual(16, len(result["records"]))
        self.assertFalse(self.workspace.exists())


if __name__ == "__main__":
    unittest.main()
