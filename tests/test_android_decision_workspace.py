"""Stage 22 decision workspace: read-only pages and honest evidence boundaries.

Every input is synthetic. The tests never access a user's Markdown vault.
"""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import (analyze_vault, decision_overview, decision_detail,
                                 preview_fragment, record_decision, retract_decision,
                                 preview_retraction)


class DecisionWorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.vault = self.root / "synthetic_notes"
        self.vault.mkdir()
        self.note = "# Demo\nСтресс приводит к бессоннице.\n"
        (self.vault / "test.md").write_text(self.note, encoding="utf-8")
        self.workspace = self.root / "private_knowledge"

    def overview(self, category="pending", offset=0, search=""):
        return json.loads(decision_overview(str(self.workspace), category, offset, search))

    def detail(self, item):
        return json.loads(decision_detail(str(self.workspace), item["kind"], item["id"]))

    def test_empty_workspace_is_read_only_and_all_categories_are_navigable(self):
        for category in ("pending", "active", "facts", "hypotheses", "rejected",
                         "deferred", "obsolete", "history"):
            page = self.overview(category)
            self.assertEqual(category, page["category"])
            self.assertEqual(0, page["total"])
            self.assertEqual([], page["items"])
            self.assertTrue(page["read_only"])
        self.assertFalse(self.workspace.exists())
        for category, offset, search in (("unknown", 0, ""), ("pending", -1, ""),
                                         ("pending", 0, "x" * 121)):
            with self.assertRaises(ValueError):
                self.overview(category, offset, search)
        with self.assertRaises(ValueError):
            decision_detail(str(self.workspace), "file", "id")

    def test_candidate_is_not_fact_and_review_moves_to_journal(self):
        before_hash = hashlib.sha256((self.vault / "test.md").read_bytes()).hexdigest()
        json.loads(analyze_vault(str(self.workspace), str(self.vault)))
        proposals = self.overview()
        self.assertGreater(proposals["total"], 0)
        self.assertEqual(0, proposals["counts"]["facts"])
        candidate = proposals["items"][0]
        self.assertEqual("candidate", candidate["kind"])
        detail = self.detail(candidate)
        self.assertEqual("interpretation", detail["role"])
        self.assertEqual("PENDING", detail["status"])
        self.assertTrue(detail["source_snapshot"])
        self.assertEqual("test.md", detail["path"])
        self.assertIn("Стресс", detail["source_text"])
        self.assertTrue(detail["formalization"])
        self.assertIsInstance(detail["diagnostics"], list)
        self.assertIsInstance(detail["conflicts_available"], bool)

        preview = json.loads(preview_fragment(str(self.workspace), "test.md", self.note,
                                              detail["fragment_id"]))
        saved = json.loads(record_decision(str(self.workspace), "test.md", self.note,
                                           detail["fragment_id"], detail["reading_id"],
                                           "fact", preview["source_hash"], preview["journal_head"]))
        self.assertTrue(saved["journal_ids"])
        self.assertLess(self.overview("pending")["total"], proposals["total"])
        facts = self.overview("facts")
        self.assertEqual(1, facts["total"])
        record = self.detail(facts["items"][0])
        self.assertEqual("fact", record["role"])
        self.assertEqual("HUMAN_ACCEPTED", record["status"])
        self.assertEqual("test.md", record["path"])
        self.assertEqual(before_hash, record["source_hash"])
        self.assertEqual(1, len(record["history"]))
        self.assertEqual(1, self.overview("history")["total"])

        reviewed = json.loads(preview_retraction(str(self.workspace), record["id"]))
        json.loads(retract_decision(str(self.workspace), record["id"],
                                    reviewed["journal_head"]))
        self.assertEqual(0, self.overview("facts")["total"])
        self.assertEqual(1, self.overview("obsolete")["total"])
        self.assertEqual(2, self.overview("history")["total"])
        self.assertEqual(before_hash,
                         hashlib.sha256((self.vault / "test.md").read_bytes()).hexdigest())

    def test_rejected_and_deferred_are_events_not_authoritative_records(self):
        json.loads(analyze_vault(str(self.workspace), str(self.vault)))
        candidate = self.detail(self.overview()["items"][0])
        preview = json.loads(preview_fragment(str(self.workspace), "test.md", self.note,
                                              candidate["fragment_id"]))
        json.loads(record_decision(str(self.workspace), "test.md", self.note,
                                   candidate["fragment_id"], candidate["reading_id"],
                                   "reject", preview["source_hash"], preview["journal_head"]))
        rejections = self.overview("rejected")
        self.assertEqual(1, rejections["total"])
        self.assertEqual("journal", rejections["items"][0]["kind"])
        self.assertEqual("reject", self.detail(rejections["items"][0])["role"])
        self.assertEqual(0, self.overview("active")["total"])
        self.assertEqual(0, self.overview("facts")["total"])
        self.assertTrue(self.overview("history")["read_only"])

        # Defer on a second fragment from a second synthetic source.
        second = "# Another\nКофе вызывает усталость.\n"
        (self.vault / "second.md").write_text(second, encoding="utf-8")
        json.loads(analyze_vault(str(self.workspace), str(self.vault)))
        page = self.overview(search="second.md")
        next_candidate = self.detail(page["items"][0])
        p = json.loads(preview_fragment(str(self.workspace), "second.md", second,
                                             next_candidate["fragment_id"]))
        json.loads(record_decision(str(self.workspace), "second.md", second,
                                   next_candidate["fragment_id"], next_candidate["reading_id"],
                                   "defer", p["source_hash"], p["journal_head"]))
        self.assertEqual(1, self.overview("deferred")["total"])
        self.assertEqual(0, self.overview("active")["total"])
        self.assertEqual(2, self.overview("history")["total"])


if __name__ == "__main__":
    unittest.main()
