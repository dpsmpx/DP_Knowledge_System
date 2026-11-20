"""Signed foreign revision proposals never bypass local manual target checks."""
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
    journal_sync_structural_preview, journal_sync_structural_commit,
)


class ManualForeignStructuralTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.ws = Workspace(self.root / "local")
        self.ws.init()
        kb = KnowledgeBase(self.ws, FixedClock(), use_cache=False)
        old = kb.accept_text("Стресс приводит к бессоннице.").entries[0]
        other = kb.accept_text("Кофе вызывает усталость.").entries[0]
        self.old = old.data["node"]["id"]
        self.other = other.data["node"]["id"]
        self.head = kb.journal.head
        self.local = self.ws.journal_path.read_text(encoding="utf-8")
        self.fingerprint = "b" * 64

    def node(self, sentence):
        return KnowledgeBase(self.ws, FixedClock(), use_cache=False).preview(
            sentence).options[0]["nodes"][0].to_dict()

    def remote(self, op, data):
        path = self.root / "peer.jsonl"
        path.write_text(self.local, encoding="utf-8")
        event = Journal(path, FixedClock()).append(op, data)
        text = path.read_text(encoding="utf-8")
        return text, event.id, hashlib.sha256(text.encode()).hexdigest()

    def preview(self, remote):
        source, event_id, sha = remote
        return json.loads(journal_sync_structural_preview(
            str(self.ws.root), source, self.fingerprint, event_id, self.head, sha))

    def commit(self, remote, preview):
        source, event_id, sha = remote
        return json.loads(journal_sync_structural_commit(
            str(self.ws.root), source, self.fingerprint, event_id, self.head,
            sha, preview["plan_sha256"]))

    def test_revise_requires_exact_active_local_target_and_produces_new_event(self):
        replacement = self.node("Шум вызывает бессонницу.")
        remote = self.remote("revise", {
            "target": self.old, "node": replacement, "reason": "correction",
            "provenance": {"source_type": "foreign_claim", "password": "not trusted"}})
        original = self.ws.journal_path.read_bytes()
        preview = self.preview(remote)
        self.assertTrue(preview["read_only"])
        self.assertEqual(self.old, preview["existing_local"][0]["id"])
        self.assertEqual(original, self.ws.journal_path.read_bytes())
        result = self.commit(remote, preview)
        self.assertTrue(result["applied"])
        self.assertFalse(result["automatic_replay"])
        kb = KnowledgeBase(self.ws, FixedClock(), use_cache=False)
        self.assertFalse(kb.state.active(self.old))
        self.assertTrue(kb.state.active(replacement["id"]))
        saved = kb.journal.entries()[-1]
        self.assertNotEqual(remote[1], saved.id)
        self.assertEqual(remote[1], saved.data["foreign_causal"]["remote_event_id"])
        self.assertEqual("human_reviewed_foreign_proposal", saved.data["provenance"]["source_type"])
        self.assertNotIn("password", json.dumps(saved.data))
        self.assertEqual([], kb.journal.verify())

    def test_split_two_new_nodes_and_merge_two_old_nodes_are_atomic(self):
        first = self.node("Шум вызывает бессонницу.")
        second = self.node("Солнце вызывает тепло.")
        with self.subTest("split"):
            remote = self.remote("split", {"target": self.old,
                                            "nodes": [first, second], "reason": "review"})
            result = self.commit(remote, self.preview(remote))
            self.assertTrue(result["applied"])
            kb = KnowledgeBase(self.ws, FixedClock(), use_cache=False)
            self.assertFalse(kb.state.active(self.old))
            self.assertTrue(kb.state.active(first["id"]))
            self.assertTrue(kb.state.active(second["id"]))
        with self.subTest("merge"):
            # A new independent local workspace is needed for an unchanged source head.
            new_ws = Workspace(self.root / "other")
            new_ws.init()
            new_ws.journal_path.write_text(self.local, encoding="utf-8")
            self.ws = new_ws
            remote = self.remote("merge", {"targets": [self.old, self.other],
                                            "node": first, "reason": "review"})
            result = self.commit(remote, self.preview(remote))
            self.assertTrue(result["applied"])
            kb = KnowledgeBase(self.ws, FixedClock(), use_cache=False)
            self.assertFalse(kb.state.active(self.old))
            self.assertFalse(kb.state.active(self.other))
            self.assertTrue(kb.state.active(first["id"]))
            self.assertEqual([], kb.journal.verify())

    def test_invalid_or_stale_revisions_never_append(self):
        replacement = self.node("Шум вызывает бессонницу.")
        for op, data in (
                ("revise", {"target": "F:unknown", "node": replacement}),
                ("revise", {"target": self.old, "node": dict(replacement, id="F:fake")}),
                ("split", {"target": self.old, "nodes": [replacement, replacement]}),
                ("merge", {"targets": [self.old, self.old], "node": replacement}),
                ("merge", {"targets": [self.old, "F:unknown"], "node": replacement}),
        ):
            with self.subTest(op=op, data=data):
                remote = self.remote(op, data)
                before = self.ws.journal_path.read_bytes()
                with self.assertRaises(ValueError):
                    self.preview(remote)
                self.assertEqual(before, self.ws.journal_path.read_bytes())
        remote = self.remote("revise", {"target": self.old, "node": replacement})
        approved = self.preview(remote)
        Journal(self.ws.journal_path, FixedClock()).append("defer", {
            "candidates": ["synthetic-local-change"]})
        with self.assertRaises(ValueError):
            self.commit(remote, approved)
