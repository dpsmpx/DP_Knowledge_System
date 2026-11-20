"""Only explicit human decisions may translate signed foreign metadata."""
from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from ksys.core.clock import FixedClock
from ksys.importer.vault import import_vault
from ksys.source.markdown import parse_document
from ksys.store.journal import Journal
from ksys.store.journal_sync import validated_entries
from ksys.store.journal_sync_manual import plan_foreign_metadata
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace
from third_party.markor.app.src.main.python.ksys_android_bridge import (
    journal_sync_metadata_preview, journal_sync_metadata_commit,
    journal_sync_review_commit, journal_sync_context_source,
    journal_sync_context_preview, journal_sync_context_commit,
)


class ManualForeignMetadataTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.workspace = Workspace(self.root / "local")
        self.workspace.init()
        Journal(self.workspace.journal_path, FixedClock()).append("defer", {
            "candidates": ["synthetic-common"]})
        self.local_text = self.workspace.journal_path.read_text(encoding="utf-8")
        self.head = validated_entries(self.local_text)[-1].id
        self.peer = "a" * 64

    def remote(self, operation: str, data: dict):
        path = self.root / "peer.jsonl"
        path.write_text(self.local_text, encoding="utf-8")
        event = Journal(path, FixedClock()).append(operation, data)
        content = path.read_text(encoding="utf-8")
        return content, event.id, hashlib.sha256(content.encode()).hexdigest()

    def preview(self, foreign: str, event_id: str, sha: str) -> dict:
        return json.loads(journal_sync_metadata_preview(
            str(self.workspace.root), foreign, self.peer, event_id, self.head, sha))

    def commit(self, foreign: str, event_id: str, sha: str, plan: dict) -> dict:
        return json.loads(journal_sync_metadata_commit(
            str(self.workspace.root), foreign, self.peer, event_id,
            self.head, sha, plan["plan_sha256"]))

    def test_alias_requires_two_confirmations_and_commits_new_local_causal_event(self):
        foreign, event_id, sha = self.remote("alias", {
            "alias": "alias_entity", "canonical": "canonical_entity"})
        before = self.workspace.journal_path.read_bytes()
        preview = self.preview(foreign, event_id, sha)
        self.assertEqual("dpksys.sync-metadata-preview.v1", preview["protocol"])
        self.assertEqual("alias", preview["operation"])
        self.assertTrue(preview["read_only"])
        self.assertTrue(preview["requires_human_confirmation"])
        self.assertEqual(before, self.workspace.journal_path.read_bytes())
        result = self.commit(foreign, event_id, sha, preview)
        self.assertEqual("dpksys.sync-metadata-commit.v1", result["protocol"])
        self.assertTrue(result["applied"])
        self.assertFalse(result["automatic_replay"])
        kb = KnowledgeBase(self.workspace, FixedClock(), use_cache=False)
        self.assertEqual([], kb.journal.verify())
        self.assertEqual("canonical_entity", kb.state.aliases["alias_entity"])
        entry = kb.journal.entries()[-1]
        self.assertEqual("alias", entry.op)
        self.assertNotEqual(event_id, entry.id)
        self.assertEqual(event_id, entry.data["foreign_causal"]["remote_event_id"])
        self.assertEqual(self.peer, entry.data["foreign_causal"]["peer_fingerprint"])

    def test_label_and_bounded_schema_are_manual_and_semantically_applied(self):
        for op, data in (("label", {"key": "entity_key", "label": "A label"}),
                         ("schema", {"schema": {"predicates": {
                             "related_to": {"arity": 2, "args": ["entity", "entity"]}}}})):
            with self.subTest(op=op):
                temp = tempfile.TemporaryDirectory()
                self.addCleanup(temp.cleanup)
                self.workspace = Workspace(Path(temp.name) / "local")
                self.workspace.init()
                self.local_text = ""
                self.head = None
                foreign, eid, sha = self.remote(op, data)
                preview = json.loads(journal_sync_metadata_preview(
                    str(self.workspace.root), foreign, self.peer, eid, "", sha))
                result = json.loads(journal_sync_metadata_commit(
                    str(self.workspace.root), foreign, self.peer, eid, "", sha,
                    preview["plan_sha256"]))
                self.assertTrue(result["applied"])
                kb = KnowledgeBase(self.workspace, FixedClock(), use_cache=False)
                if op == "label":
                    self.assertEqual("A label", kb.state.labels["entity_key"])
                else:
                    self.assertEqual(data["schema"], kb.state.schema_ext[0])
                self.assertEqual([], kb.journal.verify())

    def test_rejects_stale_heads_mismatched_remote_and_unsupported_ops(self):
        foreign, eid, sha = self.remote("alias", {
            "alias": "source", "canonical": "target"})
        preview = self.preview(foreign, eid, sha)
        with self.assertRaises(ValueError):
            journal_sync_metadata_commit(str(self.workspace.root), foreign, self.peer,
                                         eid, self.head, sha, "0" * 64)
        with self.assertRaises(ValueError):
            self.preview(foreign + "garbage", eid, sha)
        Journal(self.workspace.journal_path, FixedClock()).append("defer", {
            "candidates": ["local-changed"]})
        with self.assertRaises(ValueError):
            self.commit(foreign, eid, sha, preview)
        foreign, eid, sha = self.remote("bind", {"origin": "x", "slot": "1", "entity": "y"})
        with self.assertRaises(ValueError):
            self.preview(foreign, eid, sha)

    def test_explicit_rejection_blocks_future_metadata_adoption(self):
        foreign, eid, sha = self.remote("label", {"key": "x", "label": "X"})
        result = json.loads(journal_sync_review_commit(
            str(self.workspace.root), foreign, self.peer, eid, self.head, sha,
            "reject"))
        self.assertFalse(result["activates_knowledge"])
        with self.assertRaises(ValueError):
            self.preview(foreign, eid, sha)

    def test_invalid_schema_alias_cycles_and_overrides_fail_closed(self):
        for op, data in (
                ("schema", {"schema": {"predicates": {
                    "bad": {"arity": "not-a-number"}}}}),
                ("schema", {"schema": {"unexpected": "arbitrary"}}),
                ("alias", {"alias": "self", "canonical": "self"}),
                ("label", {"key": "x", "label": "line\nbreak"}),
                ("label", {"key": "x", "label": "hidden\u202etext"}),
        ):
            with self.subTest(op=op, data=data):
                foreign, eid, sha = self.remote(op, data)
                with self.assertRaises(ValueError):
                    self.preview(foreign, eid, sha)

    def _context_note(self, markdown: str):
        vault = self.root / "vault"
        vault.mkdir(exist_ok=True)
        path = vault / "source.md"
        path.write_text(markdown, encoding="utf-8")
        import_vault(self.workspace, vault, FixedClock(), only_extensions={".md"})
        return "source.md", markdown, parse_document("source.md", markdown).fragments[0].id

    def test_foreign_binding_requires_indexed_identical_local_fragment_and_two_approvals(self):
        path, markdown, origin = self._context_note("Он использует Python.")
        foreign, eid, sha = self.remote("bind", {
            "origin": origin, "slot": "c0.0", "entity": "алекс"})
        found = json.loads(journal_sync_context_source(
            str(self.workspace.root), foreign, self.peer, eid, self.head, sha))
        self.assertEqual(path, found["source_document"])
        self.assertTrue(found["read_only"])
        before = self.workspace.journal_path.read_bytes()
        plan = json.loads(journal_sync_context_preview(
            str(self.workspace.root), foreign, self.peer, eid, self.head, sha,
            path, markdown))
        self.assertEqual("bind", plan["operation"])
        self.assertEqual(before, self.workspace.journal_path.read_bytes())
        with self.assertRaises(ValueError):
            journal_sync_context_commit(str(self.workspace.root), foreign, self.peer,
                                        eid, self.head, sha, path, markdown + "X",
                                        plan["plan_sha256"])
        result = json.loads(journal_sync_context_commit(
            str(self.workspace.root), foreign, self.peer, eid, self.head, sha,
            path, markdown, plan["plan_sha256"]))
        self.assertFalse(result["automatic_replay"])
        kb = KnowledgeBase(self.workspace, FixedClock(), use_cache=False)
        self.assertEqual("алекс", kb.state.binding(origin, "c0.0"))
        self.assertEqual(eid, kb.journal.entries()[-1].data["foreign_causal"]["remote_event_id"])
        self.assertNotEqual(eid, kb.journal.entries()[-1].id)
        self.assertEqual([], kb.journal.verify())

    def test_foreign_reading_must_exist_in_local_ambiguous_source(self):
        path, markdown, origin = self._context_note("Алекс держит магазин.")
        kb = KnowledgeBase(self.workspace, FixedClock(), use_cache=False)
        readings = kb.preview(markdown, origin=origin, mode="import").options
        self.assertGreater(len(readings), 1)
        choice = readings[0]["reading_id"]
        foreign, eid, sha = self.remote("resolve", {
            "origin": origin, "reading_id": choice})
        plan = json.loads(journal_sync_context_preview(
            str(self.workspace.root), foreign, self.peer, eid, self.head, sha,
            path, markdown))
        self.assertEqual(choice, plan["data"]["reading_id"])
        result = json.loads(journal_sync_context_commit(
            str(self.workspace.root), foreign, self.peer, eid, self.head, sha,
            path, markdown, plan["plan_sha256"]))
        self.assertTrue(result["applied"])
        self.assertEqual(choice, KnowledgeBase(self.workspace, FixedClock(),
                          use_cache=False).state.reading_choice(origin))

    def test_foreign_context_rejects_bad_origin_reading_and_local_override(self):
        path, markdown, origin = self._context_note("Он использует Python.")
        for data in ({"origin": "fake", "slot": "c0.0", "entity": "алекс"},
                     {"origin": origin, "slot": "c0.9", "entity": "алекс"}):
            foreign, eid, sha = self.remote("bind", data)
            with self.assertRaises(ValueError):
                journal_sync_context_preview(str(self.workspace.root), foreign,
                                             self.peer, eid, self.head, sha, path, markdown)
        Journal(self.workspace.journal_path, FixedClock()).append("bind", {
            "origin": origin, "slot": "c0.0", "entity": "other"})
        updated = self.workspace.journal_path.read_text(encoding="utf-8")
        self.head = validated_entries(updated)[-1].id
        self.local_text = updated
        foreign, eid, sha = self.remote("bind", {
            "origin": origin, "slot": "c0.0", "entity": "алекс"})
        with self.assertRaises(ValueError):
            journal_sync_context_preview(str(self.workspace.root), foreign,
                                         self.peer, eid, self.head, sha, path, markdown)
