"""Synthetic note and human decision parity with the canonical ksys journal."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party" / "markor" / "app" / "src" / "main" / "python"))

from ksys_android_bridge import (active_decisions, inspect_note, preview_fragment,
                                 preview_retraction, preview_revision, record_decision,
                                 retract_decision, revise_decision)
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal


class MobileDecisionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.note = "# Demo\nСтресс приводит к бессоннице.\n"
        self.path = "synthetic.md"
        self.fragment = json.loads(inspect_note(self.path, self.note))["fragments"][1]["id"]

    def preview(self):
        return json.loads(preview_fragment(str(self.root / "workspace"),
                                          self.path, self.note, self.fragment))

    def test_preview_does_not_initialize_journal(self):
        p = self.preview()
        self.assertEqual("dpksys.decision.v1", p["protocol"])
        self.assertEqual("FORMALIZED", p["preview"]["status"])
        self.assertTrue(p["preview"]["options"])
        self.assertFalse((self.root / "workspace").exists())

    def test_explicit_claim_commit_is_provenanced_and_chain_verified(self):
        p = self.preview()
        rid = p["preview"]["options"][0]["reading_id"]
        result = json.loads(record_decision(
            str(self.root / "workspace"), self.path, self.note, self.fragment, rid,
            "claim", p["source_hash"], p["journal_head"]))
        self.assertEqual("claim", result["decision"])
        self.assertTrue(result["journal_ids"])
        journal = Journal(self.root / "workspace/data/formal/journal.jsonl", FixedClock())
        self.assertEqual([], journal.verify())
        entry = journal.entries()[0]
        self.assertEqual(self.path, entry.data["provenance"]["document"])
        self.assertEqual(self.fragment, entry.data["provenance"]["fragment_id"])
        self.assertEqual("claim", entry.data["role"])

    def test_stale_source_or_journal_prevents_writes(self):
        p = self.preview()
        rid = p["preview"]["options"][0]["reading_id"]
        with self.assertRaises(ValueError):
            record_decision(str(self.root / "workspace"), self.path, self.note + "\nextra",
                            self.fragment, rid, "fact", p["source_hash"], p["journal_head"])
        self.assertFalse((self.root / "workspace").exists())
        record_decision(str(self.root / "workspace"), self.path, self.note, self.fragment,
                        rid, "claim", p["source_hash"], p["journal_head"])
        with self.assertRaises(ValueError):
            record_decision(str(self.root / "workspace"), self.path, self.note, self.fragment,
                            rid, "fact", p["source_hash"], p["journal_head"])

    def test_rejection_is_explicit_not_a_fact(self):
        p = self.preview()
        rid = p["preview"]["options"][0]["reading_id"]
        result = json.loads(record_decision(
            str(self.root / "workspace"), self.path, self.note, self.fragment,
            rid, "reject", p["source_hash"], p["journal_head"]))
        self.assertEqual("reject", result["decision"])
        journal = Journal(self.root / "workspace/data/formal/journal.jsonl", FixedClock())
        self.assertEqual("reject", journal.entries()[0].op)

    def test_fact_requires_explicit_role_and_keeps_source_provenance(self):
        p = self.preview()
        rid = p["preview"]["options"][0]["reading_id"]
        record_decision(str(self.root / "workspace"), self.path, self.note,
                        self.fragment, rid, "fact", p["source_hash"], p["journal_head"])
        journal = Journal(self.root / "workspace/data/formal/journal.jsonl", FixedClock())
        record = journal.entries()[0]
        self.assertEqual("fact", record.data["role"])
        self.assertEqual(hashlib.sha256(self.note.encode()).hexdigest(),
                         record.data["provenance"]["document_sha"])

    def test_tampered_journal_refuses_further_decisions(self):
        p = self.preview()
        rid = p["preview"]["options"][0]["reading_id"]
        record_decision(str(self.root / "workspace"), self.path, self.note,
                        self.fragment, rid, "claim", p["source_hash"], p["journal_head"])
        path = self.root / "workspace/data/formal/journal.jsonl"
        original = path.read_text(encoding="utf-8")
        path.write_text(original.replace('"actor":"human:me"', '"actor":"other"'),
                        encoding="utf-8")
        with self.assertRaises(ValueError):
            preview_fragment(str(self.root / "workspace"),
                             self.path, self.note, self.fragment)

    def _accept_synthetic(self):
        preview = self.preview()
        rid = preview["preview"]["options"][0]["reading_id"]
        result = json.loads(record_decision(
            str(self.root / "workspace"), self.path, self.note, self.fragment,
            rid, "fact", preview["source_hash"], preview["journal_head"]))
        return result

    def test_retraction_requires_explicit_head_and_preserves_history(self):
        accepted = self._accept_synthetic()
        workspace = str(self.root / "workspace")
        listing = json.loads(active_decisions(workspace))
        self.assertEqual(1, listing["total"])
        self.assertTrue(listing["read_only"])
        target = listing["records"][0]["record_id"]
        preview = json.loads(preview_retraction(workspace, target))
        self.assertEqual("fact", preview["role"])
        self.assertTrue(preview["read_only"])
        journal = Journal(self.root / "workspace/data/formal/journal.jsonl", FixedClock())
        self.assertEqual(1, len(journal.entries()))
        self.assertEqual(accepted["journal_head"], journal.head)
        with self.assertRaises(ValueError):
            retract_decision(workspace, target, "J:stale")
        self.assertEqual(1, len(journal.entries()))
        result = json.loads(retract_decision(workspace, target, preview["journal_head"]))
        self.assertEqual("retract", result["decision"])
        self.assertEqual(0, json.loads(active_decisions(workspace))["total"])
        with self.assertRaises(ValueError):
            retract_decision(workspace, target, result["journal_head"])
        with self.assertRaises(ValueError):
            preview_retraction(workspace, target)
        journal = Journal(self.root / "workspace/data/formal/journal.jsonl", FixedClock())
        self.assertEqual(["accept", "retract"], [e.op for e in journal.entries()])
        self.assertEqual([], journal.verify())
        self.assertEqual(self.note, "# Demo\nСтресс приводит к бессоннице.\n")

    def test_retraction_browser_is_bounded_read_only_and_rejects_invalid_pages(self):
        workspace = str(self.root / "workspace")
        self.assertFalse((self.root / "workspace").exists())
        listing = json.loads(active_decisions(workspace))
        self.assertEqual([], listing["records"])
        self.assertFalse((self.root / "workspace").exists())
        for offset in (-1, 1_000_001, "0"):
            with self.assertRaises(ValueError):
                active_decisions(workspace, offset)
        with self.assertRaises(ValueError):
            preview_retraction(workspace, "F:missing")
        with self.assertRaises(ValueError):
            retract_decision(workspace, "F:missing", None)
        self.assertFalse((self.root / "workspace").exists())

    def test_retraction_rejects_tampered_journal_without_append(self):
        self._accept_synthetic()
        workspace = str(self.root / "workspace")
        target = json.loads(active_decisions(workspace))["records"][0]["record_id"]
        preview = json.loads(preview_retraction(workspace, target))
        path = self.root / "workspace/data/formal/journal.jsonl"
        original = path.read_text(encoding="utf-8")
        path.write_text(original.replace('"actor":"human:me"', '"actor":"other"'),
                        encoding="utf-8")
        with self.assertRaises(ValueError):
            retract_decision(workspace, target, preview["journal_head"])
        self.assertEqual(1, len(path.read_text(encoding="utf-8").splitlines()))

    def test_manual_revision_replaces_single_record_append_only(self):
        self._accept_synthetic()
        workspace = str(self.root / "workspace")
        old = json.loads(active_decisions(workspace))["records"][0]["record_id"]
        statement = "Стресс вызывает усталость."
        preview = json.loads(preview_revision(workspace, old, statement))
        self.assertEqual("fact", preview["role"])
        self.assertTrue(preview["read_only"])
        self.assertTrue(preview["requires_human_confirmation"])
        self.assertEqual(1, len(preview["options"]))
        journal_path = self.root / "workspace/data/formal/journal.jsonl"
        self.assertEqual(1, len(journal_path.read_text().splitlines()))
        choice = preview["options"][0]["reading_id"]
        with self.assertRaises(ValueError):
            revise_decision(workspace, old, statement, choice, "Correction",
                            preview["statement_hash"], "J:stale")
        with self.assertRaises(ValueError):
            revise_decision(workspace, old, statement + "extra", choice, "Correction",
                            preview["statement_hash"], preview["journal_head"])
        self.assertEqual(1, len(journal_path.read_text().splitlines()))
        result = json.loads(revise_decision(workspace, old, statement, choice,
                                            "Explicit correction",
                                            preview["statement_hash"],
                                            preview["journal_head"]))
        self.assertEqual("revise", result["decision"])
        self.assertNotEqual(old, result["new_record_id"])
        active = json.loads(active_decisions(workspace))
        self.assertEqual(1, active["total"])
        self.assertEqual(result["new_record_id"], active["records"][0]["record_id"])
        journal = Journal(journal_path, FixedClock())
        self.assertEqual(["accept", "revise"], [e.op for e in journal.entries()])
        self.assertEqual([], journal.verify())
        entry = journal.entries()[-1]
        self.assertEqual(old, entry.data["target"])
        self.assertEqual("human_input", entry.data["provenance"]["source_type"])
        self.assertEqual("manual_revision", entry.data["provenance"]["method"])
        self.assertEqual(self.note, "# Demo\nСтресс приводит к бессоннице.\n")
        with self.assertRaises(ValueError):
            revise_decision(workspace, old, statement, choice, "repeat",
                            preview["statement_hash"], result["journal_head"])

    def test_revision_preview_rejects_invalid_roles_duplicates_and_tampering(self):
        self._accept_synthetic()
        workspace = str(self.root / "workspace")
        old = json.loads(active_decisions(workspace))["records"][0]["record_id"]
        for statement in ("", "nonsense not formalisable", "Стресс приводит к бессоннице.",
                          "x" * 2001):
            with self.subTest(statement=statement[:20]), self.assertRaises(ValueError):
                preview_revision(workspace, old, statement)
        statement = "Стресс вызывает усталость."
        preview = json.loads(preview_revision(workspace, old, statement))
        path = self.root / "workspace/data/formal/journal.jsonl"
        original = path.read_text(encoding="utf-8")
        path.write_text(original.replace('"actor":"human:me"', '"actor":"other"'),
                        encoding="utf-8")
        with self.assertRaises(ValueError):
            revise_decision(workspace, old, statement,
                            preview["options"][0]["reading_id"], "tampered",
                            preview["statement_hash"], preview["journal_head"])
        self.assertEqual(1, len(path.read_text(encoding="utf-8").splitlines()))


if __name__ == "__main__":
    unittest.main()
