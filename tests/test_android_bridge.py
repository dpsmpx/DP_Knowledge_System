"""Parity tests for the source-level Android bridge and desktop Python ksys."""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party" / "markor" / "app" / "src" / "main" / "python"))

from ksys_android_bridge import inspect_note, MAX_CHARACTERS
from ksys.source.markdown import parse_document
from ksys.source.coverage import check_coverage


class AndroidBridgeTest(unittest.TestCase):
    def test_is_real_core_and_lossless(self):
        source = "---\ntags: [synthetic]\n---\n# Project\n"
        source += "Иван любит прогулки. Иван не любит шум.\n"
        source += "- [ ] Сделать проверку\n[[Тест]]\n"
        doc = parse_document("folder/test.md", source)
        payload = json.loads(inspect_note("folder/test.md", source))
        self.assertEqual("dpksys.inspect.v1", payload["protocol"])
        self.assertEqual(len(doc.blocks), payload["block_count"])
        self.assertEqual(len(doc.fragments), payload["fragment_count"])
        self.assertEqual(check_coverage(source, doc.fragments).ok, payload["coverage_ok"])
        self.assertEqual([f.id for f in doc.fragments],
                         [f["id"] for f in payload["fragments"]])
        self.assertTrue(payload["read_only"])

    def test_deterministic_serialization(self):
        note = "# Demo\nЭто полностью искусственная заметка.\n"
        self.assertEqual(inspect_note("demo.md", note), inspect_note("demo.md", note))

    def test_rejects_unsafe_paths_and_oversized_payloads(self):
        for path in ("/tmp/note.md", "../note.md", "folder/../note.md",
                     "folder\\note.md", "unrelated.txt"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                inspect_note(path, "Test")
        with self.assertRaises(ValueError):
            inspect_note("note.md", "x" * (MAX_CHARACTERS + 1))


if __name__ == "__main__":
    unittest.main()
