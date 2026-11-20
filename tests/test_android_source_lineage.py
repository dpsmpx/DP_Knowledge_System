"""Human-reviewed common-upstream source links are not evidence of truth."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import (
    analyze_vault, preview_source_link, record_source_link, source_lineage_decisions,
    preview_source_unlink, record_source_unlink, note_relationships, editor_note_context,
)
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace


class SourceLineageTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.vault = self.base / "notes"
        self.vault.mkdir()
        self.workspace = self.base / "private"
        self.notes = {"a.md": "# A\nАлекс является разработчиком.\n",
                      "b.md": "# B\nАлекс является разработчиком.\n",
                      "c.md": "# C\nБорис является разработчиком.\n"}
        for path, content in self.notes.items():
            (self.vault / path).write_text(content, encoding="utf-8")
        analyze_vault(str(self.workspace), str(self.vault))

    def preview(self, first="a.md", second="b.md", reason="Один первоисточник"):
        return json.loads(preview_source_link(str(self.workspace), str(self.vault),
                                              first, second, reason))

    def confirm(self, preview):
        return json.loads(record_source_link(str(self.workspace), str(self.vault),
                          preview["paths"][0], preview["paths"][1], preview["reason"],
                          preview["preview_token"], preview["journal_head"]))

    def decisions(self):
        return json.loads(source_lineage_decisions(str(self.workspace)))

    def test_explicit_review_then_revoke_preserves_sources_and_no_claim_authority(self):
        originals = {path: hashlib.sha256((self.vault / path).read_bytes()).hexdigest()
                     for path in self.notes}
        preview = self.preview("b.md", "a.md")
        self.assertEqual(["a.md", "b.md"], preview["paths"])
        self.assertEqual(2, len(preview["sources"]))
        self.assertTrue(all(s["source_sha256"] and s["excerpt"] and s["locator"]
                            for s in preview["sources"]))
        self.assertTrue(preview["read_only"])
        self.assertFalse(preview["changes_original_markdown"])
        self.assertTrue(preview["does_not_establish_independence"])
        self.assertFalse((self.workspace / "data/formal/journal.jsonl").exists())

        committed = self.confirm(preview)
        self.assertEqual("source_link", committed["decision"])
        active = self.decisions()
        self.assertEqual(["a.md", "b.md"], active["active"][0]["paths"])
        self.assertEqual("Один первоисточник", active["active"][0]["reason"])
        self.assertEqual(committed["journal_ids"][0], active["active"][0]["journal_id"])
        self.assertEqual(["source_link"], [e.op for e in Journal(
            Workspace(self.workspace).journal_path, FixedClock()).entries()])
        kb = KnowledgeBase(Workspace(self.workspace), FixedClock())
        self.assertEqual({}, kb.state.aliases)
        self.assertEqual({}, kb.state.records)
        self.assertEqual([], kb.journal.verify())
        with self.assertRaises(ValueError):
            self.preview()

        revoke = json.loads(preview_source_unlink(str(self.workspace), committed["journal_ids"][0]))
        self.assertTrue(revoke["requires_human_confirmation"])
        # Revocation needs no selected notebook, source hashes or fresh index.
        (self.vault / "a.md").unlink()
        result = json.loads(record_source_unlink(str(self.workspace), revoke["decision_id"],
                                                revoke["preview_token"], revoke["journal_head"]))
        self.assertEqual("source_unlink", result["decision"])
        self.assertEqual([], self.decisions()["active"])
        self.assertEqual(["source_unlink", "source_link"],
                         [x["op"] for x in self.decisions()["recent"]])
        self.assertEqual([], Journal(Workspace(self.workspace).journal_path,
                                     FixedClock()).verify())
        self.assertEqual({k: value for k, value in originals.items() if k != "a.md"},
                         {k: hashlib.sha256((self.vault / k).read_bytes()).hexdigest()
                          for k in ("b.md", "c.md")})

    def test_stale_source_wrong_root_and_invalid_pair_fail_closed(self):
        preview = self.preview()
        (self.vault / "b.md").write_text("# changed\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.confirm(preview)
        with self.assertRaises(ValueError):
            self.preview()
        self.assertFalse((self.workspace / "data/formal/journal.jsonl").exists())
        for first, second in (("a.md", "a.md"), ("../a.md", "b.md"),
                              ("/a.md", "b.md"), ("x.txt", "b.md")):
            with self.subTest(paths=(first, second)), self.assertRaises(ValueError):
                self.preview(first, second)
        with tempfile.TemporaryDirectory() as wrong:
            with self.assertRaises(ValueError):
                preview_source_link(str(self.workspace), wrong, "a.md", "b.md", "reason")

    def test_journal_head_mismatch_and_foreign_actor_cannot_revoke(self):
        initial = self.preview()
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("alias", {"alias": "в", "canonical": "а"})
        with self.assertRaises(ValueError):
            self.confirm(initial)
        preview = self.preview()
        committed = self.confirm(preview)
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("source_unlink", {"paths": ["a.md", "b.md"],
                                         "decision_id": committed["journal_ids"][0]},
                       actor="foreign:author")
        self.assertEqual(1, len(self.decisions()["active"]))
        revoke = json.loads(preview_source_unlink(str(self.workspace), committed["journal_ids"][0]))
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("label", {"key": "k", "label": "v"})
        with self.assertRaises(ValueError):
            record_source_unlink(str(self.workspace), revoke["decision_id"],
                                 revoke["preview_token"], revoke["journal_head"])
        self.assertEqual(1, len(self.decisions()["active"]))

    def test_fresh_review_after_explicit_revocation_requires_new_journal_event(self):
        original = self.confirm(self.preview())
        revoke = json.loads(preview_source_unlink(str(self.workspace), original["journal_ids"][0]))
        record_source_unlink(str(self.workspace), revoke["decision_id"],
                             revoke["preview_token"], revoke["journal_head"])
        next_decision = self.confirm(self.preview(reason="Повторная проверка"))
        self.assertNotEqual(original["journal_ids"], next_decision["journal_ids"])
        self.assertEqual("Повторная проверка", self.decisions()["active"][0]["reason"])

    def test_manual_link_is_visible_in_relations_without_becoming_a_truth_vote(self):
        first = self.confirm(self.preview())
        journal = Workspace(self.workspace).journal_path
        before = hashlib.sha256(journal.read_bytes()).hexdigest()
        result = json.loads(note_relationships(str(self.workspace), "a.md", str(self.vault)))
        group = result["evidence_groups"][0]
        by_path = {source["path"]: source for source in group["other_sources"]}
        self.assertFalse(result["source_lineage_limited"])
        self.assertEqual((1, 0), (group["reviewed_source_links_current"],
                                  group["reviewed_source_links_historical"]))
        self.assertEqual("current", by_path["b.md"]["reviewed_source_link_state"])
        self.assertEqual(first["journal_ids"][0], by_path["b.md"]["reviewed_source_link_id"])
        self.assertEqual("Один первоисточник", by_path["b.md"]["reviewed_source_link_reason"])
        self.assertEqual("unapproved_candidates", group["authority"])
        self.assertEqual("not_assessed", group["confidence"])
        editor = json.loads(editor_note_context(str(self.workspace), "a.md",
                            self.notes["a.md"], str(self.vault)))
        self.assertEqual("current", editor["evidence_groups"][0]
                         ["other_sources"][0]["reviewed_source_link_state"])
        self.assertEqual(before, hashlib.sha256(journal.read_bytes()).hexdigest())

        # A changed note retains the historical decision, never a live verdict.
        (self.vault / "b.md").write_text("# B changed\nАлекс является разработчиком.\n",
                                           encoding="utf-8")
        historical = json.loads(note_relationships(str(self.workspace), "a.md",
                                                   str(self.vault)))["evidence_groups"][0]
        self.assertEqual("historical", historical["other_sources"][0]
                         ["reviewed_source_link_state"])
        analyze_vault(str(self.workspace), str(self.vault))
        reindexed = json.loads(note_relationships(str(self.workspace), "a.md",
                                                  str(self.vault)))["evidence_groups"][0]
        self.assertEqual("current", reindexed["other_sources"][0]["verification"])
        self.assertEqual("historical", reindexed["other_sources"][0]
                         ["reviewed_source_link_state"])

    def test_only_direct_owner_link_is_shown_and_revocation_removes_it(self):
        (self.vault / "d.md").write_text(self.notes["a.md"], encoding="utf-8")
        analyze_vault(str(self.workspace), str(self.vault))
        a_to_b = self.confirm(self.preview("a.md", "b.md"))
        self.confirm(self.preview("b.md", "d.md"))
        result = json.loads(note_relationships(str(self.workspace), "a.md", str(self.vault)))
        group = result["evidence_groups"][0]
        self.assertEqual(1, group["reviewed_source_links_current"])
        by_path = {source["path"]: source for source in group["other_sources"]}
        self.assertEqual("current", by_path["b.md"]["reviewed_source_link_state"])
        self.assertNotIn("reviewed_source_link_state", by_path["d.md"])

        revocation = json.loads(preview_source_unlink(
            str(self.workspace), a_to_b["journal_ids"][0]))
        record_source_unlink(str(self.workspace), revocation["decision_id"],
                             revocation["preview_token"], revocation["journal_head"])
        after = json.loads(note_relationships(str(self.workspace), "a.md", str(self.vault)))
        self.assertEqual(0, after["evidence_groups"][0]["reviewed_source_links_current"])
        self.assertTrue(all("reviewed_source_link_state" not in source
                            for source in after["evidence_groups"][0]["other_sources"]))


if __name__ == "__main__":
    unittest.main()
