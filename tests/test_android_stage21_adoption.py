"""Synthetic source-verified manual adoption of an authenticated foreign fact.

The Android boundary authenticates peer ECDSA independently; this file tests
the Python core revalidation and local knowledge journal semantics only.
"""
from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from ksys.core.clock import FixedClock
from ksys.store.journal import Journal
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace
from third_party.markor.app.src.main.python.ksys_android_bridge import (
    inspect_note, preview_fragment, record_decision, journal_sync_adopt,
    journal_sync_review_commit)


class ManualForeignAdoptionTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.local = self.root / "local"
        self.remote = self.root / "remote"
        self.path = "synthetic.md"
        self.markdown = "# Demo\nСтресс приводит к бессоннице.\n"
        self.peer = "d" * 64
        self.fragment = json.loads(inspect_note(self.path, self.markdown))["fragments"][1]["id"]
        preview = json.loads(preview_fragment(str(self.remote), self.path,
                                              self.markdown, self.fragment))
        self.reading = preview["preview"]["options"][0]["reading_id"]

    def foreign(self, role="claim"):
        preview = json.loads(preview_fragment(str(self.remote), self.path,
                                              self.markdown, self.fragment))
        record_decision(str(self.remote), self.path, self.markdown, self.fragment,
                        self.reading, role, preview["source_hash"], preview["journal_head"])
        content = Workspace(self.remote).journal_path.read_text()
        entry = Journal(Workspace(self.remote).journal_path, FixedClock()).entries()[-1]
        return content, entry.id, hashlib.sha256(content.encode()).hexdigest()

    def adopt(self, foreign, event_id, checksum, text=None, head=""):
        return json.loads(journal_sync_adopt(
            str(self.local), foreign, self.peer, event_id, head, checksum,
            self.path, self.markdown if text is None else text))

    def test_explicit_adoption_commits_one_local_hash_chained_claim(self):
        foreign, event_id, checksum = self.foreign("claim")
        source = self.markdown
        result = self.adopt(foreign, event_id, checksum)
        self.assertEqual("dpksys.sync-adopt.v1", result["protocol"])
        self.assertTrue(result["applied"])
        kb = KnowledgeBase(Workspace(self.local), FixedClock(), use_cache=False)
        self.assertEqual([], kb.journal.verify())
        self.assertEqual(1, len(kb.state.claims()))
        self.assertEqual(1, len(kb.journal.entries()))
        adopted = kb.journal.entries()[0]
        self.assertEqual("accept", adopted.op)
        self.assertEqual(event_id, adopted.data["provenance"]["foreign_event"]["remote_event_id"])
        self.assertEqual(self.peer, adopted.data["provenance"]["foreign_event"]["peer_fingerprint"])
        self.assertEqual(source, self.markdown)  # Original note was not edited.

        duplicate = self.adopt(foreign, event_id, checksum,
                               head=result["journal_head"])
        self.assertTrue(duplicate["duplicate"])
        self.assertEqual(result["journal_id"], duplicate["journal_id"])
        self.assertEqual(1, len(Journal(Workspace(self.local).journal_path,
                                        FixedClock()).entries()))

    def test_fact_requires_explicit_recheck_and_can_be_recorded_as_fact(self):
        foreign, event_id, checksum = self.foreign("fact")
        result = self.adopt(foreign, event_id, checksum)
        self.assertTrue(result["applied"])
        kb = KnowledgeBase(Workspace(self.local), FixedClock(), use_cache=False)
        self.assertEqual(1, len(kb.state.authoritative()))

    def test_stale_source_head_remote_hash_and_unrecognized_event_are_rejected(self):
        foreign, event_id, checksum = self.foreign()
        for args in [
            (foreign, event_id, checksum, self.markdown + " extra", ""),
            (foreign, event_id, "0" * 64, self.markdown, ""),
            (foreign, "J:missing", checksum, self.markdown, ""),
            (foreign, event_id, checksum, self.markdown, "J:stale"),
        ]:
            with self.assertRaises(ValueError):
                self.adopt(*args)
        self.assertFalse(Workspace(self.local).journal_path.exists())

    def test_rejected_peer_review_cannot_be_adopted(self):
        foreign, event_id, checksum = self.foreign()
        response = json.loads(journal_sync_review_commit(
            str(self.local), foreign, self.peer, event_id, "", checksum, "reject"))
        with self.assertRaises(ValueError):
            self.adopt(foreign, event_id, checksum, head=response["journal_head"])
        kb = KnowledgeBase(Workspace(self.local), FixedClock(), use_cache=False)
        self.assertEqual(0, len(kb.state.records))
        self.assertEqual(1, len(kb.journal.entries()))

    def test_unverified_human_reformulation_is_not_auto_imported(self):
        foreign, event_id, checksum = self.foreign()
        # Even if the foreign hash chain is valid, a modified source proof
        # cannot be silently substituted. Re-signing is a separate Java gate.
        lines = foreign.splitlines()
        record = json.loads(lines[0])
        record["data"]["provenance"]["source_type"] = "human_input"
        from ksys.store.journal import entry_id
        record["id"] = entry_id(record["prev"], record["seq"], record["ts"],
                                record["actor"], record["op"], record["data"], record.get("note"))
        replaced = json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        with self.assertRaises(ValueError):
            self.adopt(replaced, record["id"], hashlib.sha256(replaced.encode()).hexdigest())


if __name__ == "__main__":
    unittest.main()
