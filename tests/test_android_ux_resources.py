"""Static wiring contract for the first Material 3 knowledge workspace shell.

This does not stand in for a device accessibility, layout or lifecycle test.
"""
from __future__ import annotations

import unittest
from pathlib import Path
from xml.etree import ElementTree as ET


ROOT = Path(__file__).resolve().parents[1] / "third_party/markor/app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class KnowledgeWorkspaceShellTests(unittest.TestCase):
    def test_all_existing_actions_remain_available_and_unique(self):
        layout = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        observed = [element.get(ANDROID + "id", "") for element in layout.iter()]
        required = (
            "knowledge_root", "knowledge_status", "knowledge_refresh", "knowledge_filter",
            "knowledge_list", "knowledge_progress", "knowledge_ask", "knowledge_llm_ask",
            "knowledge_qa_quick", "knowledge_qa_stress", "knowledge_qa_progress",
            "knowledge_qa_status", "knowledge_embedding_build", "knowledge_semantic_search",
            "knowledge_full_audit", "knowledge_bulk_analyze", "knowledge_bulk_review",
            "knowledge_active_decisions", "knowledge_bulk_status", "knowledge_bulk_auto",
            "knowledge_background_auto", "knowledge_background_now",
            "knowledge_background_notifications", "knowledge_background_status",
            "knowledge_backup_export", "knowledge_backup_import", "knowledge_diagnostics",
        )
        for name in required:
            self.assertEqual(1, observed.count("@+id/" + name), name)

    def test_navigation_separates_library_processes_and_sensitive_actions(self):
        layout = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        menu = ET.parse(ROOT / "res/menu/knowledge_navigation.xml").getroot()
        expected = ["knowledge_nav_library", "knowledge_nav_processes", "knowledge_nav_manage"]
        actual = [e.get(ANDROID + "id", "").removeprefix("@+id/") for e in menu]
        self.assertEqual(expected, actual)
        expected_ownership = {
            "knowledge_page_library": ("knowledge_list", "knowledge_filter"),
            "knowledge_page_processes": ("knowledge_qa_quick", "knowledge_bulk_analyze",
                                         "knowledge_embedding_build"),
            "knowledge_page_manage": ("knowledge_active_decisions", "knowledge_backup_import",
                                      "knowledge_full_audit"),
        }
        for panel_id, children in expected_ownership.items():
            panel = next(element for element in layout.iter()
                         if element.get(ANDROID + "id") == "@+id/" + panel_id)
            ids = {element.get(ANDROID + "id", "") for element in panel.iter()}
            for child_id in children:
                self.assertIn("@+id/" + child_id, ids)

    def test_dedicated_material_theme_and_localized_navigation(self):
        manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
        activity = next(a for a in manifest.iter("activity")
                        if a.get(ANDROID + "name", "").endswith("KnowledgeHubActivity"))
        self.assertEqual("@style/Theme.Dpks.Knowledge", activity.get(ANDROID + "theme"))
        themes = ET.parse(ROOT / "res/values/knowledge_styles.xml").getroot()
        self.assertTrue(any(e.get("name") == "Theme.Dpks.Knowledge"
                            and "Material3" in e.get("parent", "") for e in themes))
        for folder in ("values", "values-ru"):
            strings = ET.parse(ROOT / f"res/{folder}/knowledge_strings.xml").getroot()
            names = {element.get("name") for element in strings}
            for name in ("knowledge_page_library", "knowledge_page_processes",
                         "knowledge_page_manage", "knowledge_manage_backup_warning"):
                self.assertIn(name, names)


if __name__ == "__main__":
    unittest.main()
