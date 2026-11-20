"""A signed foreign claim batch can only be adopted from every matching local note."""
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
    inspect_note, preview_fragment, preview_claim_batch, accept_claim_batch,
    journal_sync_batch_sources, journal_sync_batch_preview, journal_sync_batch_commit,
)


class ForeignSourcedBatchTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.local = Workspace(self.root / "local")
        self.remote = Workspace(self.root / "remote")
        self.local.init()
        self.remote.init()
        self.notes = [
            ("a.md", "# One\nСтресс приводит к бессоннице.\n"),
            ("b.md", "# Two\nКофе вызывает усталость.\n"),
        ]
        self.fingerprint = "e" * 64
        selected = []
        for path, markdown in self.notes:
            frag = json.loads(inspect_note(path, markdown))["fragments"][1]["id"]
            preview = json.loads(preview_fragment(str(self.remote.root), path, markdown, frag))
            selected.append({"path": path, "markdown": markdown,
                             "fragment_id": frag,
                             "reading_id": preview["preview"]["options"][0]["reading_id"]})
        packet = json.loads(preview_claim_batch(str(self.remote.root),
                                                json.dumps(selected, ensure_ascii=False)))
        accept_claim_batch(str(self.remote.root), json.dumps(selected, ensure_ascii=False),
                           packet["batch_hash"], packet["journal_head"])
        self.foreign = self.remote.journal_path.read_text(encoding="utf-8")
        self.eid = Journal(self.remote.journal_path, FixedClock()).entries()[-1].id
        self.sha = hashlib.sha256(self.foreign.encode()).hexdigest()
        self.originals = json.dumps([{"path": p, "markdown": m} for p, m in self.notes],
                                    ensure_ascii=False)

    def preview(self, originals=None):
        return json.loads(journal_sync_batch_preview(
            str(self.local.root), self.foreign, self.fingerprint, self.eid,
            "", self.sha, originals or self.originals))

    def test_each_source_is_verified_and_one_new_local_atomic_batch_is_written(self):
        before = self.local.journal_path.read_bytes()
        found = json.loads(journal_sync_batch_sources(
            str(self.local.root), self.foreign, self.fingerprint, self.eid, "", self.sha))
        self.assertEqual("dpksys.sync-batch-sources.v1", found["protocol"])
        self.assertEqual([p for p, _ in self.notes], [i["path"] for i in found["sources"]])
        self.assertTrue(found["requires_local_originals"])
        plan = self.preview()
        self.assertTrue(plan["read_only"])
        self.assertTrue(plan["requires_human_confirmation"])
        self.assertEqual(2, plan["count"])
        self.assertEqual(before, self.local.journal_path.read_bytes())
        result = json.loads(journal_sync_batch_commit(
            str(self.local.root), self.foreign, self.fingerprint,
            self.eid, "", self.sha, self.originals, plan["plan_sha256"]))
        self.assertTrue(result["applied"])
        self.assertFalse(result["automatic_replay"])
        kb = KnowledgeBase(self.local, FixedClock(), use_cache=False)
        self.assertEqual(1, len(kb.journal.entries()))
        self.assertEqual("batch_accept", kb.journal.entries()[0].op)
        self.assertEqual(2, len(kb.state.records))
        self.assertNotEqual(self.eid, kb.journal.entries()[0].id)
        self.assertEqual(self.eid, kb.journal.entries()[0].data["foreign_causal"]["remote_event_id"])
        self.assertEqual([], kb.journal.verify())

    def test_changed_or_missing_local_original_and_stale_head_fail_closed(self):
        plan = self.preview()
        for bad in ([{"path": "a.md", "markdown": "different"},
                     {"path": "b.md", "markdown": self.notes[1][1]}],
                    [{"path": "a.md", "markdown": self.notes[0][1]}]):
            with self.assertRaises(ValueError):
                self.preview(json.dumps(bad, ensure_ascii=False))
        with self.assertRaises(ValueError):
            journal_sync_batch_commit(str(self.local.root), self.foreign,
                                      self.fingerprint, self.eid, "", self.sha,
                                      self.originals, "0" * 64)
        Journal(self.local.journal_path, FixedClock()).append("defer", {"candidates": ["local-change"]})
        with self.assertRaises(ValueError):
            journal_sync_batch_commit(str(self.local.root), self.foreign,
                                      self.fingerprint, self.eid, "", self.sha,
                                      self.originals, plan["plan_sha256"])
        self.assertEqual(1, len(Journal(self.local.journal_path, FixedClock()).entries()))

    def test_foreign_claim_node_cannot_be_substituted_without_a_new_signature(self):
        injected = self.foreign.replace('"actor":"human:me"', '"actor":"forged"')
        with self.assertRaises(ValueError):
            journal_sync_batch_sources(str(self.local.root), injected,
                                       self.fingerprint, self.eid, "", self.sha)
