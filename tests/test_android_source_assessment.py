"""Explicit descriptive provenance categories never certify source reliability."""
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
    analyze_vault, preview_source_assessment, record_source_assessment,
    preview_source_unassessment, record_source_unassessment,
    source_assessment_decisions,
)
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace


class SourceAssessmentTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.vault = self.base / "notes"
        self.vault.mkdir()
        self.note = "# Article\nАлекс является разработчиком.\n"
        (self.vault / "article.md").write_text(self.note, encoding="utf-8")
        self.workspace = self.base / "private"
        analyze_vault(str(self.workspace), str(self.vault))

    def preview(self, path="article.md", kind="secondary_summary",
                reason="Пересказ по первоисточнику"):
        return json.loads(preview_source_assessment(
            str(self.workspace), str(self.vault), path, kind, reason))

    def confirm(self, preview):
        return json.loads(record_source_assessment(str(self.workspace), str(self.vault),
                          preview["path"], preview["kind"], preview["reason"],
                          preview["preview_token"], preview["journal_head"]))

    def decisions(self):
        return json.loads(source_assessment_decisions(str(self.workspace)))

    def test_manual_category_is_journal_metadata_and_offline_reversible(self):
        original = hashlib.sha256((self.vault / "article.md").read_bytes()).hexdigest()
        preview = self.preview()
        self.assertEqual("secondary_summary", preview["kind"])
        self.assertTrue(preview["source"]["excerpt"])
        self.assertTrue(preview["source"]["locator"])
        self.assertEqual(original, preview["source"]["source_sha256"])
        self.assertIsNone(preview["reliability_score"])
        self.assertFalse(preview["changes_claim_authority"])
        self.assertFalse(preview["changes_original_markdown"])
        self.assertFalse((self.workspace / "data/formal/journal.jsonl").exists())
        committed = self.confirm(preview)
        self.assertEqual("source_assess", committed["decision"])
        state = self.decisions()
        self.assertEqual(1, len(state["active"]))
        self.assertEqual("secondary_summary", state["active"][0]["kind"])
        self.assertFalse(state["changes_claim_authority"])
        self.assertIsNone(state["reliability_score"])
        self.assertEqual({}, KnowledgeBase(Workspace(self.workspace), FixedClock()).state.records)
        with self.assertRaises(ValueError):
            self.preview(kind="primary_record", reason="Другой тип")
        self.assertEqual(original, hashlib.sha256((self.vault / "article.md").read_bytes()).hexdigest())

        revoke = json.loads(preview_source_unassessment(
            str(self.workspace), committed["journal_ids"][0]))
        (self.vault / "article.md").unlink()  # An offline/history-only action.
        revoked = json.loads(record_source_unassessment(
            str(self.workspace), revoke["decision_id"],
            revoke["preview_token"], revoke["journal_head"]))
        self.assertEqual("source_unassess", revoked["decision"])
        self.assertEqual([], self.decisions()["active"])
        self.assertEqual(["source_unassess", "source_assess"],
                         [item["op"] for item in self.decisions()["recent"]])
        self.assertEqual([], Journal(Workspace(self.workspace).journal_path, FixedClock()).verify())

    def test_repreview_if_source_or_journal_head_changed(self):
        preview = self.preview()
        (self.vault / "article.md").write_text("# Edited\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.confirm(preview)
        with self.assertRaises(ValueError):
            self.preview()
        self.assertFalse((self.workspace / "data/formal/journal.jsonl").exists())
        (self.vault / "article.md").write_text(self.note, encoding="utf-8")
        preview = self.preview()
        Journal(Workspace(self.workspace).journal_path, FixedClock()).append(
            "label", {"key": "k", "label": "v"})
        with self.assertRaises(ValueError):
            self.confirm(preview)

    def test_invalid_type_path_and_reason_never_append(self):
        for args in (("article.md", "trusted_fact", "reason"),
                     ("../article.md", "primary_record", "reason"),
                     ("article.md", "republication", ""),
                     ("article.md", "unclassified", "x" * 501)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                self.preview(*args)
        with tempfile.TemporaryDirectory() as other:
            with self.assertRaises(ValueError):
                preview_source_assessment(str(self.workspace), other, "article.md",
                                          "primary_record", "reason")
        self.assertFalse((self.workspace / "data/formal/journal.jsonl").exists())

    def test_revocation_targets_exact_owner_decision_and_allows_new_review(self):
        first = self.confirm(self.preview())
        with self.assertRaises(ValueError):
            preview_source_unassessment(str(self.workspace), "unknown-journal-id")
        revoke = json.loads(preview_source_unassessment(
            str(self.workspace), first["journal_ids"][0]))
        Journal(Workspace(self.workspace).journal_path, FixedClock()).append(
            "source_unassess", {"path": "article.md",
                                "decision_id": first["journal_ids"][0]}, actor="other:person")
        self.assertEqual(1, len(self.decisions()["active"]))
        with self.assertRaises(ValueError):
            record_source_unassessment(str(self.workspace), revoke["decision_id"],
                                       revoke["preview_token"], revoke["journal_head"])
        revoke = json.loads(preview_source_unassessment(
            str(self.workspace), first["journal_ids"][0]))
        record_source_unassessment(str(self.workspace), revoke["decision_id"],
                                   revoke["preview_token"], revoke["journal_head"])
        second = self.confirm(self.preview(kind="primary_record", reason="Новая оценка"))
        self.assertNotEqual(first["journal_ids"], second["journal_ids"])
        self.assertEqual("primary_record", self.decisions()["active"][0]["kind"])


if __name__ == "__main__":
    unittest.main()
