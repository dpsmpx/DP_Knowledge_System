"""Editor Stage 22: user-authored readings retain source provenance and guards."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import (inspect_note, preview_fragment, preview_manual_fragment,
                                 record_manual_decision)
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal


class EditorManualInterpretationTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.work = Path(temp.name) / "workspace"
        self.path = "synthetic.md"
        self.source = "# Марс\nМарс (планета) — большой; в сравнении с Землёй.\n"
        self.manual = "Марс является планетой."
        self.fragment_id = json.loads(inspect_note(self.path, self.source))["fragments"][1]["id"]

    def preview(self):
        return json.loads(preview_manual_fragment(str(self.work), self.path,
                                                  self.source, self.fragment_id, self.manual))

    def commit(self, packet, *, source=None, statement=None, manual_hash=None, head=None,
               reading_id=None, role="claim"):
        return json.loads(record_manual_decision(
            str(self.work), self.path, self.source if source is None else source,
            self.fragment_id, self.manual if statement is None else statement,
            packet["manual_text_hash"] if manual_hash is None else manual_hash,
            packet["preview"]["options"][0]["reading_id"] if reading_id is None else reading_id,
            role, packet["source_hash"], packet["journal_head"] if head is None else head))

    def test_strict_parser_refuses_punctuation_but_human_can_rephrase(self):
        raw = json.loads(preview_fragment(str(self.work), self.path,
                                          self.source, self.fragment_id))
        self.assertEqual([], raw["preview"]["options"])
        self.assertIn("unsupported punctuation", raw["preview"]["reason"])
        packet = self.preview()
        self.assertTrue(packet["manual_interpretation"])
        self.assertTrue(packet["read_only"])
        self.assertEqual(self.source, "# Марс\nМарс (планета) — большой; в сравнении с Землёй.\n")
        self.assertEqual(self.manual, packet["manual_text"])
        self.assertEqual("FORMALIZED", packet["preview"]["status"])
        self.assertTrue(packet["preview"]["options"])
        self.assertFalse(self.work.exists(), "Preview may not initialize a journal")

    def test_manual_commit_is_provenanced_and_source_unchanged(self):
        packet = self.preview()
        accepted = self.commit(packet)
        self.assertEqual("claim", accepted["decision"])
        journal = Journal(self.work / "data/formal/journal.jsonl", FixedClock())
        self.assertEqual([], journal.verify())
        entry = journal.entries()[0]
        prov = entry.data["provenance"]
        self.assertEqual("human_interpretation", prov["source_type"])
        self.assertEqual("manual_reformulation", prov["extraction"]["method"])
        self.assertEqual(self.source.splitlines()[1], prov["text"])
        self.assertEqual(self.manual, prov["human_statement"])
        self.assertEqual(hashlib.sha256(self.manual.encode()).hexdigest(),
                         prov["human_statement_hash"])
        self.assertEqual(hashlib.sha256(self.source.encode()).hexdigest(), prov["document_sha"])
        self.assertEqual(self.fragment_id, prov["fragment_id"])

    def test_stale_source_manual_text_and_journal_are_rejected(self):
        p = self.preview()
        with self.assertRaises(ValueError):
            self.commit(p, source=self.source + "changed")
        with self.assertRaises(ValueError):
            self.commit(p, statement="Марс является звездой.")
        with self.assertRaises(ValueError):
            self.commit(p, manual_hash="0" * 64)
        with self.assertRaises(ValueError):
            self.commit(p, reading_id="wrong-reading")
        self.assertFalse(self.work.exists())
        self.commit(p)
        with self.assertRaises(ValueError):
            self.commit(p)
        self.assertEqual(1, len(Journal(self.work / "data/formal/journal.jsonl",
                                        FixedClock()).entries()))

    def test_manual_statement_boundaries_and_refusal_are_read_only(self):
        for text in ("", "   ", "Текст\nДругой.", "а" * 4001):
            with self.assertRaises(ValueError):
                preview_manual_fragment(str(self.work), self.path, self.source,
                                        self.fragment_id, text)
        with self.assertRaises(ValueError):
            preview_manual_fragment(str(self.work), "../private.md", self.source,
                                    self.fragment_id, self.manual)
        # An unsuccessful reformulation remains an explicit NO_PARSE, not an
        # invented candidate or a write to the private knowledge workspace.
        still_unparsed = json.loads(preview_manual_fragment(
            str(self.work), self.path, self.source, self.fragment_id,
            "Марс (планета) является большой; факт."))
        self.assertEqual([], still_unparsed["preview"]["options"])
        self.assertTrue(still_unparsed["read_only"])
        self.assertFalse(self.work.exists())


if __name__ == "__main__":
    unittest.main()
