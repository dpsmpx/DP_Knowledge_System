"""Stage 21: independent offline branches are never silently flattened."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from ksys.core.clock import FixedClock
from ksys.store.journal import Journal
from ksys.store.journal_sync import (preview_journals, validated_entries,
                                     review_foreign_events, inspect_foreign_event)


def journal_string(path: Path, operations: tuple[tuple[str, dict], ...]) -> str:
    j = Journal(path, FixedClock())
    for operation, data in operations:
        j.append(operation, data)
    return path.read_text(encoding="utf-8") if path.exists() else ""


class JournalSyncPreviewTests(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.base = journal_string(self.root / "base.jsonl", (
            ("defer", {"candidates": ["sample-a"]}),
        ))
        self.local = self.base + journal_string(
            self.root / "temp-local.jsonl", ())  # explicit common prefix

    def test_diverged_offline_branches_remain_both_visible(self):
        # Derive separate continuations from the same trusted prefix.
        left_path = self.root / "left.jsonl"
        right_path = self.root / "right.jsonl"
        left_path.write_text(self.base, encoding="utf-8")
        right_path.write_text(self.base, encoding="utf-8")
        left = Journal(left_path, FixedClock())
        right = Journal(right_path, FixedClock())
        left.append("defer", {"candidates": ["local-only"]})
        right.append("reject", {"candidates": ["remote-only"]})
        result = preview_journals(
            left_path.read_text(encoding="utf-8"),
            right_path.read_text(encoding="utf-8"))
        self.assertEqual("diverged", result.relation)
        self.assertEqual(1, result.common_events)
        self.assertEqual(1, result.local_only)
        self.assertEqual(1, result.remote_only)
        self.assertTrue(result.has_unclassified_operations)
        self.assertFalse(result.summary()["automatic_merge"])
        self.assertEqual(self.base, (self.root / "base.jsonl").read_text(encoding="utf-8"))

    def test_identical_and_prefix_cases(self):
        self.assertEqual("identical", preview_journals(self.base, self.base).relation)
        path = self.root / "longer.jsonl"
        path.write_text(self.base, encoding="utf-8")
        j = Journal(path, FixedClock())
        j.append("label", {"key": "synthetic", "label": "Synthetic"})
        longer = path.read_text(encoding="utf-8")
        self.assertEqual("local_prefix", preview_journals(self.base, longer).relation)
        self.assertEqual("remote_prefix", preview_journals(longer, self.base).relation)
        self.assertEqual("local_prefix", preview_journals("", self.base).relation)

    def test_modified_chain_and_unknown_op_are_rejected(self):
        tampered = self.base.replace('"actor":"human:me"', '"actor":"attacker"')
        with self.assertRaises(ValueError):
            validated_entries(tampered)
        bogus = json.loads(self.base)
        bogus["op"] = "delete_all"
        with self.assertRaises(ValueError):
            preview_journals(self.base, json.dumps(bogus))

    def test_unrelated_branch_is_not_fast_forward(self):
        other = journal_string(self.root / "other.jsonl", (
            ("reject", {"candidates": ["sample-b"]}),
        ))
        self.assertEqual("unrelated", preview_journals(self.base, other).relation)

    def test_privacy_summary_only_contains_count_and_hash(self):
        preview = preview_journals(self.base, "").summary()
        rendered = json.dumps(preview)
        self.assertNotIn("sample-a", rendered)
        self.assertTrue(preview["read_only"])

    def test_review_page_exposes_operations_not_private_payload(self):
        path = self.root / "review.jsonl"
        path.write_text(self.base, encoding="utf-8")
        journal = Journal(path, FixedClock())
        journal.append("reject", {"candidates": ["secret-private-candidate"]})
        journal.append("defer", {"candidates": ["another-candidate"]})
        foreign = path.read_text(encoding="utf-8")
        first = review_foreign_events(self.base, foreign, 0, 1)
        self.assertTrue(first["has_more"])
        self.assertEqual(2, first["foreign_total"])
        self.assertEqual("reject", first["events"][0]["operation"])
        self.assertTrue(first["events"][0]["review_required"])
        self.assertNotIn("secret-private-candidate", json.dumps(first))
        second = review_foreign_events(self.base, foreign, 1, 1)
        self.assertEqual(1, len(second["events"]))
        self.assertFalse(second["has_more"])
        with self.assertRaises(ValueError):
            review_foreign_events(self.base, foreign, 0, 1000)

    def test_explicit_detail_rechecks_exact_signed_branch_commitment(self):
        content = journal_string(self.root / "detail.jsonl", (
            ("defer", {"candidates": ["private-candidate"]}),
        ))
        import hashlib
        commitment = hashlib.sha256(content.encode("utf-8")).hexdigest()
        entry = validated_entries(content)[0]
        detail = inspect_foreign_event(content, entry.id, commitment)
        self.assertEqual("private-candidate", detail["data"]["candidates"][0])
        self.assertTrue(detail["read_only"])
        with self.assertRaises(ValueError):
            inspect_foreign_event(content, entry.id, "0" * 64)


if __name__ == "__main__":
    unittest.main()
