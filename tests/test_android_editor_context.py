"""Editor context: approved provenance is separate from tentative relations."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import (analyze_vault, editor_note_context, inspect_note,
                                 preview_fragment, record_decision, _verified_source_span)
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal
from ksys.store.workspace import Workspace


class EditorContextTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.workspace = self.base / "private_workspace"
        self.vault = self.base / "synthetic_notes"
        self.vault.mkdir()
        self.notes = {
            "a.md": "# A\nАлекс является разработчиком.\n",
            "b.md": "# B\nАлекс является разработчиком.\n",
            "c.md": "# C\nАлекс не является разработчиком.\n",
        }
        for path, content in self.notes.items():
            (self.vault / path).write_text(content, encoding="utf-8")

    def context(self, markdown=None):
        return json.loads(editor_note_context(str(self.workspace), "a.md",
                                              self.notes["a.md"] if markdown is None
                                              else markdown))

    def test_absent_index_and_journal_do_not_create_files(self):
        result = self.context()
        self.assertEqual("dpksys.editor_context.v1", result["protocol"])
        self.assertTrue(result["read_only"])
        self.assertTrue(result["relations_are_unapproved"])
        self.assertEqual("saved_markdown", result["source_snapshot"])
        self.assertEqual("absent", result["relation_index_state"])
        self.assertEqual(0, result["accepted_total"])
        self.assertEqual([], result["candidate_relations"])
        self.assertFalse(self.workspace.exists())

    def test_accepted_journal_provenance_is_not_candidate_index(self):
        note = self.notes["a.md"]
        frag = json.loads(inspect_note("a.md", note))["fragments"][1]["id"]
        packet = json.loads(preview_fragment(str(self.workspace), "a.md", note, frag))
        reading = packet["preview"]["options"][0]["reading_id"]
        record_decision(str(self.workspace), "a.md", note, frag, reading, "claim",
                        packet["source_hash"], packet["journal_head"])
        state = self.context()
        self.assertEqual("absent", state["relation_index_state"])
        self.assertEqual(1, state["accepted_total"])
        rec = state["accepted_records"][0]
        self.assertEqual("claim", rec["role"])
        self.assertEqual("vault_note", rec["provenance_kind"])
        self.assertTrue(rec["source_current"])
        self.assertTrue(rec["locator"])
        self.assertEqual(frag, rec["fragment_id"])
        self.assertIn("Алекс", rec["source_excerpt"])
        self.assertEqual("verified", rec["source_span_state"])
        begin, finish = rec["source_span"]
        self.assertEqual(note[begin:finish], rec["source_excerpt"])
        self.assertEqual([2, 1], [rec["source_line"], rec["source_column"]])
        self.assertFalse(state["accepted_truncated"])
        self.assertEqual(0, state["stale_provenance_count"])

        changed = self.context(note + "Вторая строка.")
        self.assertEqual(1, changed["stale_provenance_count"])
        self.assertFalse(changed["accepted_records"][0]["source_current"])
        self.assertEqual("stale_source", changed["accepted_records"][0]["source_span_state"])
        self.assertNotIn("source_span", changed["accepted_records"][0])
        self.assertEqual([], Journal(self.workspace / "data/formal/journal.jsonl",
                                     FixedClock()).verify())

    def test_relations_require_exact_current_source_index_and_are_read_only(self):
        analyze_vault(str(self.workspace), str(self.vault))
        db = Workspace(self.workspace).db_path
        before = hashlib.sha256(db.read_bytes()).hexdigest()
        original = {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                    for p in self.notes}
        context = self.context()
        self.assertEqual("current", context["relation_index_state"])
        self.assertEqual(0, context["accepted_total"])
        related = {m["path"]: m for m in context["candidate_relations"]}
        self.assertEqual({"b.md", "c.md"}, set(related))
        self.assertEqual("possible_agreement", related["b.md"]["kind"])
        self.assertEqual("possible_conflict", related["c.md"]["kind"])
        self.assertTrue(all(item["source_locator"] and item["other_locator"]
                            for item in related.values()))
        self.assertEqual(before, hashlib.sha256(db.read_bytes()).hexdigest())
        self.assertEqual(original, {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                                    for p in self.notes})

        changed = self.context(self.notes["a.md"] + "\nДругой текст.")
        self.assertEqual("stale", changed["relation_index_state"])
        self.assertEqual([], changed["candidate_relations"])
        self.assertEqual(before, hashlib.sha256(db.read_bytes()).hexdigest())

    def test_accepted_opposites_show_possible_journal_conflict(self):
        for path in ("a.md", "c.md"):
            note = self.notes[path]
            fragment = json.loads(inspect_note(path, note))["fragments"][1]["id"]
            packet = json.loads(preview_fragment(str(self.workspace), path, note, fragment))
            record_decision(str(self.workspace), path, note, fragment,
                            packet["preview"]["options"][0]["reading_id"], "claim",
                            packet["source_hash"], packet["journal_head"])
        state = self.context()
        self.assertEqual(1, state["accepted_total"])
        record = state["accepted_records"][0]
        self.assertTrue(record["conflicts_available"])
        self.assertGreater(record["conflict_count"], 0)
        self.assertEqual("absent", state["relation_index_state"],
                         "Journal conflict must not be mistaken for an index relation")

    def test_invalid_source_and_excess_size_are_rejected(self):
        for path in ("../outside.md", "/abs.md", "file.txt"):
            with self.assertRaises(ValueError):
                editor_note_context(str(self.workspace), path, self.notes["a.md"])
        with self.assertRaises(ValueError):
            editor_note_context(str(self.workspace), "a.md", "x" * 2_000_001)
        self.assertFalse(self.workspace.exists())

    def test_duplicate_excerpt_uses_approved_span_not_first_text_match(self):
        note = "# 😀\nАлекс является разработчиком.\nАлекс является разработчиком.\n"
        fragments = json.loads(inspect_note("a.md", note))["fragments"]
        target = fragments[2]["id"]
        packet = json.loads(preview_fragment(str(self.workspace), "a.md", note, target))
        record_decision(str(self.workspace), "a.md", note, target,
                        packet["preview"]["options"][0]["reading_id"], "claim",
                        packet["source_hash"], packet["journal_head"])
        rec = self.context(note)["accepted_records"][0]
        self.assertEqual(target, rec["fragment_id"])
        self.assertEqual("verified", rec["source_span_state"])
        self.assertEqual(note.rindex("Алекс является"), rec["source_span"][0])
        self.assertEqual(3, rec["source_line"])
        self.assertEqual(1, rec["source_column"])
        # Python spans are Unicode code points, not Android UTF-16 indices.
        self.assertNotEqual(len(note[:rec["source_span"][0]].encode("utf-16-le")) // 2,
                            rec["source_span"][0])

    def test_legacy_and_corrupt_provenance_never_guess_source_offset(self):
        note = "Повтор. Повтор."
        sha = hashlib.sha256(note.encode("utf-8")).hexdigest()
        metadata = {"document_sha": sha, "text": "Повтор."}
        self.assertEqual("missing", _verified_source_span(metadata, note, sha)["source_span_state"])
        self.assertEqual("mismatch", _verified_source_span(
            dict(metadata, span=[3, 10]), note, sha)["source_span_state"])
        self.assertEqual("missing", _verified_source_span(
            dict(metadata, span=[True, 7]), note, sha)["source_span_state"])
        self.assertEqual("mismatch", _verified_source_span(
            dict(metadata, span=[0, 999]), note, sha)["source_span_state"])
        self.assertEqual("stale_source", _verified_source_span(
            dict(metadata, span=[0, 7]), note, "0" * 64)["source_span_state"])
        aligned = _verified_source_span(dict(metadata, span=[8, 15]), note, sha)
        self.assertEqual({"source_span_state": "verified", "source_span": [8, 15],
                          "source_line": 1, "source_column": 9}, aligned)


if __name__ == "__main__":
    unittest.main()
