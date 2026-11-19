"""Read-only, approved-only source decorations for the DPKS Markdown editor."""
from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import (analyze_vault, editor_note_highlights,
                                 inspect_note, preview_fragment, record_decision)
from ksys.store.journal import Journal
from ksys.core.clock import FixedClock


class EditorHighlightTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.workspace = self.base / "knowledge_workspace"
        self.vault = self.base / "synthetic_vault"
        self.vault.mkdir()
        self.markdown = ("# 😀\nАлекс является разработчиком.\n"
                         "Алекс является разработчиком.\n"
                         "Борис является писателем.\n")
        (self.vault / "a.md").write_text(self.markdown, encoding="utf-8")

    def highlights(self, markdown=None):
        return json.loads(editor_note_highlights(
            str(self.workspace), "a.md", self.markdown if markdown is None else markdown))

    def accept(self, ordinal, role="claim"):
        fragment = json.loads(inspect_note("a.md", self.markdown))["fragments"][ordinal]["id"]
        packet = json.loads(preview_fragment(str(self.workspace), "a.md",
                                             self.markdown, fragment))
        record_decision(str(self.workspace), "a.md", self.markdown, fragment,
                        packet["preview"]["options"][0]["reading_id"], role,
                        packet["source_hash"], packet["journal_head"])

    def test_missing_journal_does_not_create_files_or_guess_indexed_claims(self):
        before = self.highlights()
        self.assertEqual("dpksys.editor_highlights.v1", before["protocol"])
        self.assertTrue(before["read_only"])
        self.assertTrue(before["only_approved_journal_sources"])
        self.assertEqual([], before["highlights"])
        self.assertFalse(self.workspace.exists())

        analyze_vault(str(self.workspace), str(self.vault))
        indexed = self.highlights()
        self.assertEqual([], indexed["highlights"],
                         "Index candidates are not accepted journal records")

    def test_exact_duplicate_span_and_non_bmp_unicode_are_read_only(self):
        self.accept(2)
        journal = self.workspace / "data/formal/journal.jsonl"
        hash_before = hashlib.sha256(journal.read_bytes()).hexdigest()
        source_before = hashlib.sha256((self.vault / "a.md").read_bytes()).hexdigest()
        result = self.highlights()
        self.assertEqual(1, result["highlight_total"])
        self.assertEqual(0, result["unverified_origins"])
        self.assertEqual("saved_markdown", result["source_snapshot"])
        span = result["highlights"][0]
        self.assertEqual("claim", span["role"])
        self.assertEqual(self.markdown.rindex("Алекс является"), span["start"])
        self.assertEqual("Алекс является разработчиком.",
                         self.markdown[span["start"]:span["end"]])
        self.assertNotEqual(len(self.markdown[:span["start"]].encode("utf-16-le")) // 2,
                            span["start"], "Android must convert code-point positions")
        self.assertEqual(hash_before, hashlib.sha256(journal.read_bytes()).hexdigest())
        self.assertEqual(source_before,
                         hashlib.sha256((self.vault / "a.md").read_bytes()).hexdigest())
        self.assertEqual([], Journal(journal, FixedClock()).verify())

        changed = self.highlights(self.markdown + "Ещё одна строка.")
        self.assertEqual([], changed["highlights"])
        self.assertEqual(1, changed["unverified_origins"])

    def test_conflicting_roles_on_overlapping_source_are_neutral(self):
        markdown = "abcdefghij"
        sha = hashlib.sha256(markdown.encode("utf-8")).hexdigest()

        def rec(key, role, start, end):
            return SimpleNamespace(id=key, role=role, provenance=[{
                "document": "a.md", "document_sha": sha, "span": [start, end],
                "text": markdown[start:end],
            }])

        records = {r.id: r for r in [rec("1", "claim", 0, 5),
                                     rec("2", "hypothesis", 3, 8),
                                     rec("3", "fact", 9, 10)]}
        fake = SimpleNamespace(state=SimpleNamespace(records=records,
                                                      active=lambda key: True))
        with patch("ksys_android_bridge._kb", return_value=fake):
            result = json.loads(editor_note_highlights(str(self.workspace), "a.md", markdown))
        self.assertEqual(2, result["highlight_total"])
        self.assertEqual([{"start": 0, "end": 8, "role": "mixed", "record_count": 2},
                          {"start": 9, "end": 10, "role": "fact", "record_count": 1}],
                         result["highlights"])

    def test_invalid_provenance_is_excluded_and_bounded(self):
        markdown = "x" * 150
        sha = hashlib.sha256(markdown.encode("utf-8")).hexdigest()
        records = {}
        for i in range(140):
            records[str(i)] = SimpleNamespace(id=str(i), role="claim", provenance=[{
                "document": "a.md", "document_sha": sha,
                "span": [i, i + 1], "text": "x",
            }])
        records["bad"] = SimpleNamespace(id="bad", role="fact", provenance=[{
            "document": "a.md", "document_sha": sha, "span": [0, 2], "text": "wrong",
        }])
        fake = SimpleNamespace(state=SimpleNamespace(records=records,
                                                      active=lambda key: True))
        with patch("ksys_android_bridge._kb", return_value=fake):
            result = json.loads(editor_note_highlights(str(self.workspace), "a.md", markdown))
        self.assertTrue(result["truncated"])
        self.assertEqual(140, result["highlight_total"])
        self.assertEqual(128, len(result["highlights"]))
        self.assertEqual(1, result["unverified_origins"])
        self.assertFalse(self.workspace.exists())

    def test_invalid_paths_are_refused(self):
        for path in ("../a.md", "/absolute.md", "not-markdown.txt"):
            with self.assertRaises(ValueError):
                editor_note_highlights(str(self.workspace), path, self.markdown)


if __name__ == "__main__":
    unittest.main()
