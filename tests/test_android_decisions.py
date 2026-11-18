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

from ksys_android_bridge import inspect_note, preview_fragment, record_decision
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


if __name__ == "__main__":
    unittest.main()
