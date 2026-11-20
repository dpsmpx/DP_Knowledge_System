"""Real Android entity decision lifecycle against synthetic private notes."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import (analyze_vault, entity_alias_workspace,
                                 entity_alias_decisions, preview_entity_alias,
                                 record_entity_alias, preview_entity_unalias,
                                 record_entity_unalias, note_relationships)
from ksys.core.clock import FixedClock
from ksys.importer.aliases import verified_owner_aliases
from ksys.store.journal import Journal
from ksys.store.state import KnowledgeState
from ksys.store.workspace import Workspace


class EntityReviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.vault = base / "notebook"
        self.vault.mkdir()
        self.ws = base / "private"
        self.left = self.vault / "boris.md"
        self.right = self.vault / "alex.md"
        self.left.write_text("# Люди\nБорис является разработчиком.\n", encoding="utf-8")
        self.right.write_text("# Люди\nАлекс является разработчиком.\n", encoding="utf-8")
        analyze_vault(str(self.ws), str(self.vault))

    def _sources(self):
        result = json.loads(entity_alias_workspace(str(self.ws), str(self.vault),
                                                   "борис", "алекс"))
        self.assertEqual("dpksys.entities.v1", result["protocol"])
        self.assertTrue(result["read_only"])
        return result

    def _preview(self, reason="Observed identity in two source fragments"):
        choices = self._sources()
        a, c = choices["alias_sources"][0], choices["canonical_sources"][0]
        return json.loads(preview_entity_alias(str(self.ws), str(self.vault),
                          "борис", "алекс", a["path"], a["fragment_id"],
                          c["path"], c["fragment_id"], reason))

    def _approve(self, packet):
        a, c = packet["evidence"]
        return json.loads(record_entity_alias(str(self.ws), str(self.vault),
                          "борис", "алекс", a["path"], a["fragment_id"],
                          c["path"], c["fragment_id"], packet["reason"],
                          packet["preview_token"], packet["journal_head"]))

    def _journal(self):
        return Journal(Workspace(self.ws).journal_path, FixedClock())

    def test_full_source_review_approve_revoke_and_new_approval(self):
        original = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in (self.left, self.right)}
        self.assertEqual([], json.loads(note_relationships(str(self.ws), "boris.md"))["matches"])
        sources = self._sources()
        self.assertTrue(all(s["current"] for s in sources["alias_sources"]
                            + sources["canonical_sources"]))
        self.assertIn("Борис", sources["alias_sources"][0]["excerpt"])
        self.assertFalse(sources["sources_limited"])
        preview = self._preview()
        self.assertEqual(2, len(preview["evidence"]))
        self.assertEqual(["борис", "алекс"], [s["symbol"] for s in preview["evidence"]])
        self.assertFalse(preview["changes_original_markdown"])
        confirmed = self._approve(preview)
        first_id = confirmed["journal_ids"][0]
        self.assertEqual("alias", confirmed["decision"])
        self.assertTrue(json.loads(note_relationships(str(self.ws), "boris.md"))["matches"])
        active = json.loads(entity_alias_decisions(str(self.ws)))["active"]
        self.assertEqual(first_id, active[0]["journal_id"])
        self.assertEqual(2, len(active[0]["sources"]))

        revoked_preview = json.loads(preview_entity_unalias(str(self.ws), "борис"))
        revoked = json.loads(record_entity_unalias(str(self.ws), "борис",
                         revoked_preview["preview_token"], revoked_preview["journal_head"]))
        self.assertEqual("unalias", revoked["decision"])
        self.assertEqual([], json.loads(entity_alias_decisions(str(self.ws)))["active"])
        self.assertEqual([], json.loads(note_relationships(str(self.ws), "boris.md"))["matches"])
        journal = self._journal()
        self.assertEqual([], journal.verify())
        self.assertEqual(["alias", "unalias"], [e.op for e in journal.entries()])
        self.assertEqual({}, KnowledgeState.replay(journal.entries()).aliases)
        self.assertEqual({}, verified_owner_aliases(journal.entries(), "human:me").by_symbol)

        again = self._approve(self._preview("Reconfirmed manually"))
        self.assertNotEqual(first_id, again["journal_ids"][0])
        self.assertTrue(json.loads(note_relationships(str(self.ws), "boris.md"))["matches"])
        self.assertEqual(original, {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                    for p in (self.left, self.right)})

    def test_changed_live_markdown_blocks_preview_and_stale_commit(self):
        preview = self._preview()
        self.left.write_text("# Люди\nБорис является инженером.\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            self._approve(preview)
        choices = self._sources()
        self.assertFalse(choices["alias_sources"][0]["current"])
        with self.assertRaises(ValueError):
            self._preview()
        self.assertFalse(Workspace(self.ws).journal_path.exists())

    def test_journal_head_and_revocation_token_are_stale_safe(self):
        preview = self._preview()
        j = self._journal()
        j.append("label", {"key": "unrelated", "label": "human"})
        with self.assertRaises(ValueError):
            self._approve(preview)
        approved = self._approve(self._preview())
        revoke = json.loads(preview_entity_unalias(str(self.ws), "борис"))
        j = self._journal()
        j.append("label", {"key": "other", "label": "human"})
        with self.assertRaises(ValueError):
            record_entity_unalias(str(self.ws), "борис", revoke["preview_token"],
                                  revoke["journal_head"])
        self.assertIn("борис", {e["alias"] for e in json.loads(
            entity_alias_decisions(str(self.ws)))["active"]})

    def test_nonowner_revocation_does_not_cancel_approved_alias(self):
        decision = self._approve(self._preview())
        j = self._journal()
        j.append("unalias", {"alias": "борис", "canonical": "алекс",
                             "decision_id": decision["journal_ids"][0]},
                 actor="peer:external")
        self.assertEqual("алекс", KnowledgeState.replay(j.entries()).aliases["борис"])
        self.assertIn("борис", {e["alias"] for e in json.loads(
            entity_alias_decisions(str(self.ws)))["active"]})
        j.append("unalias", {"alias": "борис", "canonical": "алекс",
                             "decision_id": "J:wrong"})
        self.assertTrue(verified_owner_aliases(j.entries(), "human:me").by_symbol)
        self.assertEqual("алекс", KnowledgeState.replay(j.entries()).aliases["борис"])

    def test_invalid_chained_or_duplicate_mappings_are_rejected(self):
        with self.assertRaises(ValueError):
            entity_alias_workspace(str(self.ws), str(self.vault), "BAD SPACE", "алекс")
        self._approve(self._preview())
        with self.assertRaises(ValueError):
            self._preview()
        choices = self._sources()
        with self.assertRaises(ValueError):
            preview_entity_alias(str(self.ws), str(self.vault), "алекс", "борис",
                                 choices["canonical_sources"][0]["path"],
                                 choices["canonical_sources"][0]["fragment_id"],
                                 choices["alias_sources"][0]["path"],
                                 choices["alias_sources"][0]["fragment_id"], "cycle")

    def test_wrong_vault_and_path_traversal_cannot_be_evidence(self):
        other = self.vault.parent / "other"
        other.mkdir()
        with self.assertRaises(ValueError):
            entity_alias_workspace(str(self.ws), str(other), "борис", "алекс")
        choices = self._sources()
        c = choices["canonical_sources"][0]
        with self.assertRaises(ValueError):
            preview_entity_alias(str(self.ws), str(self.vault), "борис", "алекс",
                                 "../boris.md", choices["alias_sources"][0]["fragment_id"],
                                 c["path"], c["fragment_id"], "bad")


if __name__ == "__main__":
    unittest.main()
