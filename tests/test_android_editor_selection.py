"""Read-only Markdown selection mapping for the Stage 22 editor toolbar."""
from __future__ import annotations

import hashlib
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import inspect_note, inspect_selection


class EditorSelectionTests(unittest.TestCase):
    def setUp(self):
        self.path = "synthetic.md"
        self.markdown = "# Заголовок\nТест 😀 с эмодзи.\nСтресс приводит к бессоннице.\n"

    def test_unicode_selection_maps_to_one_exact_fragment(self):
        start = self.markdown.index("Стресс")
        end = start + len("Стресс приводит к бессоннице.")
        result = json.loads(inspect_selection(self.path, self.markdown, start, end))
        self.assertTrue(result["matched"])
        self.assertTrue(result["read_only"])
        self.assertEqual("dpksys.inspect.v1", result["protocol"])
        self.assertEqual(hashlib.sha256(self.markdown.encode()).hexdigest(),
                         result["source_hash"])
        self.assertEqual("Стресс приводит к бессоннице.", result["source_text"])
        self.assertGreater(result["candidate_count"], 0)
        inspection = json.loads(inspect_note(self.path, self.markdown))
        self.assertIn(result["fragment_id"], [f["id"] for f in inspection["fragments"]])

    def test_short_selection_within_one_fragment_is_accepted(self):
        start = self.markdown.index("с эмодзи")
        result = json.loads(inspect_selection(self.path, self.markdown,
                                              start, start + len("с эмодзи")))
        self.assertTrue(result["matched"])
        self.assertIn("эмодзи", result["source_text"])

    def test_cross_fragment_selection_is_not_silently_assigned(self):
        start = self.markdown.index("Тест")
        end = self.markdown.index("бессоннице") + len("бессоннице")
        result = json.loads(inspect_selection(self.path, self.markdown, start, end))
        self.assertFalse(result["matched"])
        self.assertNotIn("fragment_id", result)

    def test_invalid_ranges_and_paths_are_rejected(self):
        for start, end in ((-1, 1), (0, 0), (5, 3), (0, len(self.markdown) + 1),
                           (False, 1), (0, True)):
            with self.assertRaises(ValueError):
                inspect_selection(self.path, self.markdown, start, end)
        with self.assertRaises(ValueError):
            inspect_selection("../secret.md", self.markdown, 1, 3)


if __name__ == "__main__":
    unittest.main()
