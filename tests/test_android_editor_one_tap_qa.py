"""One-tap Stage 22 editor QA uses only removable synthetic cache fixtures."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import stage22_editor_selftest


class EditorOneTapQaTests(unittest.TestCase):
    def test_all_synthetic_checks_pass_without_leaking_artifacts_or_text(self):
        with tempfile.TemporaryDirectory() as private_cache:
            report = stage22_editor_selftest(private_cache)
            result = json.loads(report)
            self.assertEqual("dpksys.stage22_editor_selftest.v1", result["protocol"])
            self.assertEqual(10, result["passed"], report)
            self.assertEqual(0, result["failed"], report)
            self.assertEqual(10, len(result["checks"]))
            self.assertTrue(all(row["passed"] for row in result["checks"]))
            self.assertTrue(result["synthetic_only"])
            self.assertFalse(result["used_selected_vault"])
            self.assertTrue(result["requires_manual_ui_qa"])
            self.assertTrue(result["read_only_user_data"])
            self.assertEqual([], list(Path(private_cache).iterdir()))
            self.assertNotIn(private_cache, report)
            self.assertNotIn("Алекс", report)
            self.assertNotIn("разработчиком", report)
            self.assertNotIn(".md", report)

    def test_cache_must_preexist_and_be_absolute(self):
        with self.assertRaises(ValueError):
            stage22_editor_selftest("relative/cache")
        with tempfile.TemporaryDirectory() as private_cache:
            with self.assertRaises(ValueError):
                stage22_editor_selftest(str(Path(private_cache) / "missing"))


if __name__ == "__main__":
    unittest.main()
