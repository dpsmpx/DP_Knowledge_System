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

    def test_navigation_separates_library_decisions_processes_and_settings(self):
        layout = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        menu = ET.parse(ROOT / "res/menu/knowledge_navigation.xml").getroot()
        expected = ["knowledge_nav_library", "knowledge_nav_decisions",
                    "knowledge_nav_processes", "knowledge_nav_manage"]
        actual = [e.get(ANDROID + "id", "").removeprefix("@+id/") for e in menu]
        self.assertEqual(expected, actual)
        expected_ownership = {
            "knowledge_page_library": ("knowledge_list", "knowledge_filter"),
            "knowledge_page_decisions": ("knowledge_decision_categories",
                                         "knowledge_decisions_list", "knowledge_decisions_filter",
                                         "knowledge_decisions_batch"),
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
                         "knowledge_page_decisions", "knowledge_page_manage",
                         "knowledge_manage_backup_warning"):
                self.assertIn(name, names)

    def test_full_screen_decision_review_has_separate_source_and_knowledge_sections(self):
        root = ET.parse(ROOT / "res/layout/knowledge__decision_detail.xml").getroot()
        ids = [e.get(ANDROID + "id", "") for e in root.iter()]
        for name in ("knowledge_decision_detail_screen", "knowledge_decision_detail_source",
                     "knowledge_decision_detail_interpretation", "knowledge_decision_detail_formal",
                     "knowledge_decision_detail_provenance", "knowledge_decision_detail_conflicts",
                     "knowledge_decision_detail_history", "knowledge_decision_review_action",
                     "knowledge_decision_manage_action", "knowledge_decision_open_source"):
            self.assertEqual(1, ids.count("@+id/" + name), name)
        bridge = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgePythonBridge.java").read_text()
        for method in ("decisionOverview", "decisionDetail"):
            self.assertIn("JSONObject " + method + "(", bridge)
        hub = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        self.assertIn("navigation.setVisibility(View.GONE)", hub)
        self.assertIn("private void closeDecisionDetail()", hub)
        for folder in ("values", "values-ru"):
            root = ET.parse(ROOT / f"res/{folder}/knowledge_strings.xml").getroot()
            names = [e.get("name") for e in root]
            self.assertEqual(len(names), len(set(names)), folder)
            for name in ("knowledge_decisions_pending", "knowledge_decisions_rejected",
                         "knowledge_decisions_history", "knowledge_decision_source_warning"):
                self.assertIn(name, names)

    def test_dpks_launcher_is_primary_only_for_its_flavor(self):
        manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
        aliases = {a.get(ANDROID + "name"): a for a in manifest.iter("activity-alias")}
        self.assertTrue({".activity.MarkorLauncher", ".knowledge.KnowledgeLauncher"}
                        .issubset(aliases))
        for name, target, flag in (
            (".activity.MarkorLauncher", ".activity.MainActivity",
             "knowledge_markor_launcher_enabled"),
            (".knowledge.KnowledgeLauncher", ".knowledge.KnowledgeHubActivity",
             "knowledge_dpks_launcher_enabled"),
        ):
            alias = aliases[name]
            self.assertEqual(target, alias.get(ANDROID + "targetActivity"))
            self.assertEqual("@bool/" + flag, alias.get(ANDROID + "enabled"))
            self.assertEqual("true", alias.get(ANDROID + "exported"))
            self.assertTrue(any(x.get(ANDROID + "name") == "android.intent.action.MAIN"
                                for x in alias.iter("action")))
        for source, expected in (("main", {"knowledge_markor_launcher_enabled": "true",
                                           "knowledge_dpks_launcher_enabled": "false"}),
                                 ("flavorAtest", {"knowledge_markor_launcher_enabled": "false",
                                                  "knowledge_dpks_launcher_enabled": "true"})):
            folder = ROOT.parent / source / "res/values/knowledge_launcher.xml"
            values = {v.get("name"): v.text for v in ET.parse(folder).getroot()}
            self.assertEqual(expected, values)

    def test_note_editor_has_separate_workspace_chrome(self):
        manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
        activity = next(a for a in manifest.iter("activity")
                        if a.get(ANDROID + "name") == ".knowledge.KnowledgeDocumentActivity")
        self.assertEqual("false", activity.get(ANDROID + "exported"))
        self.assertEqual("@style/Theme.Dpks.Knowledge", activity.get(ANDROID + "theme"))
        shell = ET.parse(ROOT / "res/layout/knowledge__document_activity.xml").getroot()
        ids = {x.get(ANDROID + "id") for x in shell.iter()}
        self.assertIn("@+id/toolbar", ids)
        self.assertIn("@+id/document__placeholder_fragment", ids)
        editor = (ROOT / "java/net/gsantner/markor/activity/DocumentActivity.java").read_text()
        self.assertIn("this instanceof KnowledgeDocumentActivity", editor)
        self.assertIn("R.layout.knowledge__document_activity", editor)
        bridge = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        self.assertIn("KnowledgeDocumentActivity.open(this, file)", bridge)

    def test_editor_review_and_link_controls_are_explicit_and_guarded(self):
        shell = ET.parse(ROOT / "res/layout/knowledge__document_activity.xml").getroot()
        ids = [element.get(ANDROID + "id", "") for element in shell.iter()]
        for name in ("knowledge_editor_modes", "knowledge_editor_edit_button",
                     "knowledge_editor_preview_button", "knowledge_editor_inspect",
                     "knowledge_editor_save", "knowledge_editor_review_selection",
                     "knowledge_editor_backlinks",
                     "knowledge_editor_status"):
            self.assertEqual(1, ids.count("@+id/" + name), name)
        editor = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        for required in ("setViewModeVisibility(preview)", "saveDocument(false)",
                         "inspectSelection(this,",
                         "markdown.codePointCount(0, start)", "cutsSurrogate(markdown,",
                         "reviewSavedFragment(", "readSavedNote(file.getCanonicalFile())",
                         "saved.equals(editorSnapshot)", "relativePath(file)",
                         "verifiedPossibleBacklinksReadOnly(", "onStop()"):
            self.assertIn(required, editor)
        bridge = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgePythonBridge.java").read_text()
        self.assertIn("JSONObject inspectSelection(", bridge)
        index = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeLocalIndex.java").read_text()
        self.assertIn("SQLiteDatabase.OPEN_READONLY", index)
        self.assertIn("last_scan_incomplete", index)
        hub = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        self.assertIn("reviewFromEditor(", hub)
        self.assertIn("expectedSourceHash.equals(", hub)
        self.assertIn("previewFragment(path, fragmentId, sourceHash)", hub)
        for folder in ("values", "values-ru"):
            strings = ET.parse(ROOT / f"res/{folder}/knowledge_strings.xml").getroot()
            names = {element.get("name") for element in strings}
            for name in ("knowledge_editor_edit", "knowledge_editor_preview",
                         "knowledge_editor_save", "knowledge_editor_saved",
                         "knowledge_editor_review_selection", "knowledge_editor_backlinks",
                         "knowledge_editor_save_first", "knowledge_editor_selection_multiple",
                         "knowledge_editor_source_invalid"):
                self.assertIn(name, names)

    def test_manual_interpretation_does_not_masquerade_as_source_extraction(self):
        hub = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        for item in ("knowledge_manual_punctuation_help",
                     "promptManualInterpretation(", "previewManualInterpretation(",
                     "packet.optBoolean(\"manual_interpretation\")",
                     "KnowledgePythonBridge.decideManual", "knowledge_manual_source_label",
                     "knowledge_manual_statement_label"):
            self.assertIn(item, hub)
        bridge = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgePythonBridge.java").read_text()
        self.assertIn("preview_manual_fragment", bridge)
        self.assertIn("record_manual_decision", bridge)
        for folder in ("values", "values-ru"):
            strings = ET.parse(ROOT / f"res/{folder}/knowledge_strings.xml").getroot()
            names = [element.get("name") for element in strings]
            self.assertEqual(len(names), len(set(names)), folder)
            for name in ("knowledge_manual_punctuation_help", "knowledge_manual_rephrase",
                         "knowledge_manual_source_label", "knowledge_manual_statement_label",
                         "knowledge_manual_separate_warning", "knowledge_manual_preview_failed"):
                self.assertIn(name, names)

    def test_decision_review_previews_are_comparison_panels(self):
        core = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        for method in ("confirmRevision", "confirmSplit", "confirmMerge", "showRetractionPreview"):
            start = core.find("private void " + method)
            self.assertGreaterEqual(start, 0, method)
            end = core.find("\n    private ", start + 1)
            segment = core[start:end if end >= 0 else None]
            self.assertIn("KnowledgeDecisionReviewDialog.show(", segment, method)
        dialog = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDecisionReviewDialog.java").read_text()
        for text in ("ScrollView", "MaterialCardView", "changedRanges", "setTextIsSelectable",
                     "setNegativeButton", "setPositiveButton"):
            self.assertIn(text, dialog)
        for locale in ("values", "values-ru"):
            elements = ET.parse(ROOT / f"res/{locale}/knowledge_strings.xml").getroot()
            names = {element.get("name") for element in elements}
            for label in ("knowledge_diff_before", "knowledge_diff_after", "knowledge_diff_proposed",
                          "knowledge_editor_source_notice", "knowledge_editor_back"):
                self.assertIn(label, names)


if __name__ == "__main__":
    unittest.main()
