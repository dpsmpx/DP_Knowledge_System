"""Journal evidence navigation must be read-only and source-unverified until Android opens it."""
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

from ksys_android_bridge import (active_decisions, editor_record_evidence, inspect_note,
                                 preview_fragment, record_decision, preview_retraction,
                                 retract_decision, preview_revision, revise_decision)
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal


class EditorEvidenceTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.workspace = self.root / "workspace"

    def accept(self, path: str, text: str, role: str = "claim") -> str:
        before = {row["record_id"] for row in json.loads(active_decisions(
            str(self.workspace)))["records"]}
        fragment = json.loads(inspect_note(path, text))["fragments"][0]["id"]
        preview = json.loads(preview_fragment(str(self.workspace), path, text, fragment))
        json.loads(record_decision(
            str(self.workspace), path, text, fragment,
            preview["preview"]["options"][0]["reading_id"], role,
            preview["source_hash"], preview["journal_head"]))
        after = {row["record_id"] for row in json.loads(active_decisions(
            str(self.workspace)))["records"]}
        self.assertEqual(1, len(after - before))
        return (after - before).pop()

    def evidence(self, record_id: str) -> dict:
        return json.loads(editor_record_evidence(str(self.workspace), record_id))

    def test_missing_and_invalid_record_never_initialize_a_journal(self):
        for value in ("", "x" * 161, None):
            with self.assertRaises(ValueError):
                editor_record_evidence(str(self.workspace), value)
        with self.assertRaises(ValueError):
            self.evidence("C:missing")
        self.assertFalse(self.workspace.exists())

    def test_provenance_contains_full_text_checksum_not_verified_path(self):
        text = "Алекс является исследователем.\n"
        rid = self.accept("a.md", text)
        journal = self.workspace / "data/formal/journal.jsonl"
        before = journal.read_bytes()
        result = self.evidence(rid)
        self.assertTrue(result["read_only"])
        self.assertTrue(result["only_journal_records"])
        self.assertFalse(result["navigation_verified"])
        self.assertEqual(1, result["origins_total"])
        source = result["origins"][0]
        self.assertEqual("a.md", source["path"])
        self.assertEqual(hashlib.sha256(text.encode()).hexdigest(), source["document_sha"])
        self.assertEqual(hashlib.sha256(text.strip().encode()).hexdigest(),
                         source["source_text_sha"])
        self.assertFalse(source["navigation_verified"])
        self.assertEqual(1, result["events_total"])
        self.assertEqual("accept", result["events"][0]["op"])
        self.assertEqual(before, journal.read_bytes())
        self.assertEqual([], Journal(journal, FixedClock()).verify())

    def test_conflicts_are_possible_links_to_active_journal_record(self):
        affirmative = self.accept("a.md", "Алекс является разработчиком.\n")
        negative = self.accept("b.md", "Алекс не является разработчиком.\n")
        result = self.evidence(affirmative)
        self.assertTrue(result["conflicts_available"])
        self.assertTrue(any(link["id"] == negative
                            and "possible_conflict" in link["kinds"]
                            for link in result["links"]))
        self.assertEqual("b.md", self.evidence(negative)["origins"][0]["path"])

    def test_retraction_keeps_source_and_event_history_but_disables_active_link(self):
        rid = self.accept("a.md", "Алекс является разработчиком.\n")
        preview = json.loads(preview_retraction(str(self.workspace), rid))
        retract_decision(str(self.workspace), rid, preview["journal_head"])
        result = self.evidence(rid)
        self.assertFalse(result["active"])
        self.assertEqual(2, result["events_total"])
        self.assertEqual(["accept", "retract"], [e["op"] for e in result["events"]])
        self.assertEqual("a.md", result["origins"][0]["path"])

    def test_revision_links_predecessor_and_successor_without_fake_file_origin(self):
        old = self.accept("a.md", "Алекс является разработчиком.\n")
        replacement = "Алекс является исследователем."
        preview = json.loads(preview_revision(str(self.workspace), old, replacement))
        result = json.loads(revise_decision(
            str(self.workspace), old, replacement, preview["options"][0]["reading_id"],
            "Synthetic correction", preview["statement_hash"], preview["journal_head"]))
        successor = result["new_record_id"]
        self.assertTrue(any(link["id"] == successor and "successor" in link["kinds"]
                            for link in self.evidence(old)["links"]))
        next_record = self.evidence(successor)
        self.assertTrue(any(link["id"] == old and "predecessor" in link["kinds"]
                            for link in next_record["links"]))
        self.assertTrue(all(not origin["navigation_verified"]
                            for origin in next_record["origins"]))

    def test_legacy_origin_cannot_be_presented_as_verified(self):
        fake_rec = SimpleNamespace(
            id="C:fake", role="claim", status=SimpleNamespace(value="CLAIMED"),
            node=SimpleNamespace(ksf=lambda: "synthetic"),
            provenance=[{"document": "../secret.md", "source_type": "human_input",
                         "span": [0, 5], "text": None}],
            journal=[], supersedes=[], superseded_by_many=[], superseded_by=None)
        fake = SimpleNamespace(
            state=SimpleNamespace(records={fake_rec.id: fake_rec}, entries=[],
                                  active=lambda _: True),
            journal=SimpleNamespace(head=None), conflicts_for=lambda _: [])
        with patch("ksys_android_bridge._kb", return_value=fake):
            evidence = self.evidence("C:fake")
        self.assertFalse(evidence["origins"][0]["navigation_verified"])
        self.assertIsNone(evidence["origins"][0]["source_text_sha"])
        self.assertFalse(self.workspace.exists())

    def test_large_lineage_and_history_are_bounded_without_reading_any_note(self):
        original = "x" * 620
        origins = [{"document": f"note-{i}.md", "document_sha": "a" * 64,
                    "span": [0, len(original)], "text": original}
                   for i in range(15)]
        rec = SimpleNamespace(
            id="C:main", role="claim", status=SimpleNamespace(value="CLAIMED"),
            node=SimpleNamespace(ksf=lambda: "synthetic"), provenance=origins,
            journal=[f"J:{i}" for i in range(20)],
            supersedes=[f"C:{i}" for i in range(19)],
            superseded_by_many=[], superseded_by=None)
        records = {rec.id: rec}
        for i in range(19):
            records[f"C:{i}"] = SimpleNamespace(
                id=f"C:{i}", role="claim", status=SimpleNamespace(value="CLAIMED"),
                node=SimpleNamespace(ksf=lambda: f"linked {i}"))
        events = [SimpleNamespace(id=f"J:{i}", seq=i + 1,
                                  op="accept", ts=f"time-{i}") for i in range(20)]
        fake = SimpleNamespace(
            state=SimpleNamespace(records=records, entries=events,
                                  active=lambda _: True),
            journal=SimpleNamespace(head="J:19"), conflicts_for=lambda _: [])
        with patch("ksys_android_bridge._kb", return_value=fake):
            result = self.evidence(rec.id)
        self.assertEqual(15, result["origins_total"])
        self.assertEqual(12, len(result["origins"]))
        self.assertTrue(result["origins_truncated"])
        self.assertTrue(result["origins"][0]["source_text_truncated"])
        self.assertEqual(hashlib.sha256(original.encode()).hexdigest(),
                         result["origins"][0]["source_text_sha"])
        self.assertEqual(19, result["links_total"])
        self.assertEqual(16, len(result["links"]))
        self.assertTrue(result["links_truncated"])
        self.assertEqual(20, result["events_total"])
        self.assertEqual("J:4", result["events"][0]["id"])
        self.assertTrue(result["events_truncated"])
        self.assertFalse(self.workspace.exists())


if __name__ == "__main__":
    unittest.main()
