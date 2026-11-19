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
    def test_stage22_first_open_guide_is_private_and_accessible(self):
        layout = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        buttons = [element for element in layout.iter()
                   if element.get(ANDROID + "id") == "@+id/knowledge_open_guide"]
        self.assertEqual(1, len(buttons))
        self.assertEqual("@string/knowledge_guide_open", buttons[0].get(ANDROID + "text"))
        self.assertEqual("48dp", buttons[0].get(ANDROID + "minHeight"))
        guide = ET.parse(ROOT / "res/layout/knowledge__guide.xml").getroot()
        self.assertEqual("ScrollView", guide.tag)
        headings = [e.get(ANDROID + "text") for e in guide.iter()
                    if e.get(ANDROID + "accessibilityHeading") == "true"]
        self.assertEqual([f"@string/knowledge_guide_{section}_title" for section in
                          ("markdown", "interpretation", "claim", "fact", "inference")], headings)
        hub = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        for needle in ("GUIDE_SEEN", "MODE_PRIVATE", "savedInstanceState == null",
                       "showKnowledgeGuide()", "MaterialAlertDialogBuilder(this)",
                       "R.layout.knowledge__guide"):
            self.assertIn(needle, hub)
        for folder in ("values", "values-ru"):
            strings = {e.get("name"): e.text or "" for e in ET.parse(
                ROOT / f"res/{folder}/knowledge_strings.xml").getroot()}
            for item in ("markdown", "interpretation", "claim", "fact", "inference"):
                self.assertTrue(strings[f"knowledge_guide_{item}_title"])
                self.assertTrue(strings[f"knowledge_guide_{item}_body"])
            self.assertIn("journal" if folder == "values" else "журнал",
                          strings["knowledge_guide_privacy"].lower())

    def test_decision_detail_accessibility_and_reduced_motion(self):
        layout = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        for name in ("knowledge_library_previous", "knowledge_library_next",
                     "knowledge_decisions_previous", "knowledge_decisions_next",
                     "knowledge_decisions_batch"):
            element = next(e for e in layout.iter()
                           if e.get(ANDROID + "id") == "@+id/" + name)
            self.assertEqual("48dp", element.get(ANDROID + "minHeight"), name)
        for name in ("knowledge_library_page_number", "knowledge_decisions_page_number"):
            element = next(e for e in layout.iter()
                           if e.get(ANDROID + "id") == "@+id/" + name)
            self.assertEqual("polite", element.get(ANDROID + "accessibilityLiveRegion"))
        detail = ET.parse(ROOT / "res/layout/knowledge__decision_detail.xml").getroot()
        self.assertTrue(any(e.get(ANDROID + "accessibilityHeading") == "true"
                            for e in detail.iter()))
        hub = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        self.assertIn("ViewCompat.setAccessibilityPaneTitle", hub)
        self.assertNotIn("smoothScrollToPosition", hub)

    def test_android_navigation_and_unsaved_source_guards_have_executable_checks(self):
        source = ROOT / "java/net/gsantner/markor/knowledge"
        hub = (source / "KnowledgeHubActivity.java").read_text()
        editor = (source / "KnowledgeDocumentActivity.java").read_text()
        runner = (source / "KnowledgeDeviceQaRunner.java").read_text()
        self.assertIn("workspaceNavigation.backAction()", hub)
        self.assertIn("workspaceNavigation.openDecisionDetail()", hub)
        self.assertIn("workspaceNavigation.closeDecisionDetail()", hub)
        self.assertIn("KnowledgeEditorReviewGuard.matchesSavedSource", editor)
        self.assertIn('step("UI-STATE-22", this::checkSyntheticWorkspaceNavigation)', runner)
        self.assertIn('report.add("SKIP", "EDITOR-22-UI"', runner)
        unit = ROOT.parent / "test/java/net/gsantner/markor/knowledge"
        for name in ("KnowledgeWorkspaceNavigationTest.java", "KnowledgeEditorReviewGuardTest.java"):
            self.assertTrue((unit / name).exists(), name)

    def test_stage22_process_timeline_only_persists_known_status_codes(self):
        layout = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        processes = next(e for e in layout.iter()
                         if e.get(ANDROID + "id") == "@+id/knowledge_page_processes")
        self.assertTrue(any(e.get(ANDROID + "id") == "@+id/knowledge_process_history"
                            for e in processes.iter()))
        model = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeProcessHistory.java").read_text()
        self.assertIn("MAX_EVENTS = 32", model)
        self.assertIn("Operation.valueOf", model)
        self.assertIn("Outcome.valueOf", model)
        hub = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        self.assertIn("MODE_PRIVATE", hub)
        self.assertIn("recordProcessEvent(KnowledgeProcessHistory.Operation", hub)
        runner = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDeviceQaRunner.java").read_text()
        self.assertIn('step("UI-HISTORY-22", this::checkSyntheticProcessHistory)', runner)
        unit = ROOT.parent / "test/java/net/gsantner/markor/knowledge"
        self.assertTrue((unit / "KnowledgeProcessHistoryTest.java").exists())

    def test_all_existing_actions_remain_available_and_unique(self):
        layout = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        observed = [element.get(ANDROID + "id", "") for element in layout.iter()]
        required = (
            "knowledge_root", "knowledge_status", "knowledge_refresh", "knowledge_filter",
            "knowledge_list", "knowledge_progress", "knowledge_ask", "knowledge_llm_ask",
            "knowledge_qa_quick", "knowledge_qa_stress", "knowledge_qa_editor",
            "knowledge_qa_progress",
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
            "knowledge_page_processes": ("knowledge_qa_quick", "knowledge_qa_editor",
                                         "knowledge_bulk_analyze",
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

    def test_stage22_one_button_qa_is_synthetic_cancellable_and_honestly_scoped(self):
        layout = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        editor_qa = next(x for x in layout.iter()
                         if x.get(ANDROID + "id") == "@+id/knowledge_qa_editor")
        self.assertEqual("@string/knowledge_qa_editor", editor_qa.get(ANDROID + "text"))
        hub = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        runner = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDeviceQaRunner.java").read_text()
        transport = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgePythonBridge.java").read_text()
        python = (ROOT / "python/ksys_android_bridge.py").read_text()
        for marker in ("qaEditorButton.setOnClickListener", "runDeviceQa(false, true)",
                       "runningDeviceQa.cancel(true)", "qaEditorButton.setText(",
                       "copyDeviceQaReport(report)"):
            self.assertIn(marker, hub)
        for marker in ("checkSyntheticStage22Editor", "stage22EditorSelfTest(context,",
                       "getCacheDir().getCanonicalPath()", '"EDITOR-22-UI"',
                       "selectedVaultUntouched=true", "editorStage22"):
            self.assertIn(marker, runner)
        self.assertIn('"stage22_editor_selftest"', transport)
        # Preview #17: Python emitted this protocol correctly, but Java's
        # shared allowlist rejected it before ten per-check results appeared.
        self.assertIn('"dpksys.stage22_editor_selftest.v1".equals(protocol)', transport)
        self.assertIn('isSupportedProtocol(protocol)', transport)
        self.assertIn('if (!isSupportedProtocol(protocol))', transport)
        for marker in ("def stage22_editor_selftest(", "tempfile.TemporaryDirectory",
                       '"used_selected_vault": False', '"requires_manual_ui_qa": True'):
            self.assertIn(marker, python)
        for folder in ("values", "values-ru"):
            names = {e.get("name") for e in ET.parse(
                ROOT / f"res/{folder}/knowledge_strings.xml").getroot()}
            self.assertTrue({"knowledge_qa_editor", "knowledge_qa_editor_limits"} <= names)

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

    def test_editor_context_panel_separates_journal_and_unapproved_relations(self):
        shell = ET.parse(ROOT / "res/layout/knowledge__document_activity.xml").getroot()
        ids = [element.get(ANDROID + "id", "") for element in shell.iter()]
        for name in ("knowledge_editor_context", "knowledge_editor_context_panel",
                     "knowledge_editor_context_summary", "knowledge_editor_context_records",
                     "knowledge_editor_context_relations_list",
                     "knowledge_editor_context_warning"):
            self.assertEqual(1, ids.count("@+id/" + name), name)
        panel = next(x for x in shell.iter()
                     if x.get(ANDROID + "id") == "@+id/knowledge_editor_context_panel")
        self.assertEqual("visible", panel.get(ANDROID + "visibility"))
        body = next(x for x in shell.iter()
                    if x.get(ANDROID + "id") == "@+id/knowledge_editor_context_body")
        self.assertEqual("gone", body.get(ANDROID + "visibility"))
        editor = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        for item in ("toggleKnowledgeContext()", "readSavedNote(file.getCanonicalFile())",
                     "KnowledgePythonBridge.editorContext(", "relation_index_state",
                     "accepted_records", "candidate_relations", "source_span_state",
                     "knowledge_editor_context_unsaved", "knowledge_editor_context_disclaimer",
                     "jumpToSavedSource(", "openRelatedNote(", "relativePath(candidate)"):
            self.assertIn(item, editor)
        bridge = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgePythonBridge.java").read_text()
        self.assertIn('"editor_note_context"', bridge)
        for folder in ("values", "values-ru"):
            strings = ET.parse(ROOT / f"res/{folder}/knowledge_strings.xml").getroot()
            names = [element.get("name") for element in strings]
            self.assertEqual(len(names), len(set(names)), folder)
            for name in ("knowledge_editor_context", "knowledge_editor_context_accepted",
                         "knowledge_editor_context_relations", "knowledge_editor_context_unsaved",
                         "knowledge_editor_context_index_stale",
                         "knowledge_editor_context_record_stale",
                         "knowledge_editor_context_conflict"):
                self.assertIn(name, names)

    def test_editor_persistent_dock_expands_and_navigates_inline_with_stale_guard(self):
        shell = ET.parse(ROOT / "res/layout/knowledge__document_activity.xml").getroot()
        ids = [x.get(ANDROID + "id", "") for x in shell.iter()]
        for name in ("knowledge_editor_context_panel", "knowledge_editor_context_body",
                     "knowledge_editor_context_refresh", "knowledge_editor_context_expand",
                     "knowledge_editor_context_back", "knowledge_editor_context_navigation",
                     "knowledge_editor_context_detail_scroll",
                     "knowledge_editor_context_detail_content",
                     "knowledge_editor_context_list_scroll"):
            self.assertEqual(1, ids.count("@+id/" + name), name)
        java = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        for marker in ("initializeContextDock()", "refreshKnowledgeContext()",
                       "setDockExpanded(", "showDockPage(", "navigateDockBack()",
                       "resetDockNavigation()", "dockPageHistory.size() >= 8",
                       "dockGeneration.incrementAndGet()", "currentDock(generation)",
                       "stopWatchingDockEdits()", "knowledge_editor_dock_stale",
                       "view -> inspectRecordEvidence(", "view -> openEvidenceOrigin(origin)",
                       "showDockPage(getString(R.string.knowledge_editor_evidence_title), body)"):
            self.assertIn(marker, java)
        # Core journal and conflict navigation no longer spawn nested modals.
        for section in ("showSourceKnowledge", "showContextRecord", "renderRecordEvidence"):
            start = java.index("private void " + section + "(")
            end = java.find("\n    private ", start + 1)
            snippet = java[start:end if end != -1 else None]
            self.assertNotIn("new AlertDialog.Builder", snippet, section)
        for locale in ("values", "values-ru"):
            names = {e.get("name") for e in ET.parse(
                ROOT / f"res/{locale}/knowledge_strings.xml").getroot()}
            for name in ("knowledge_editor_dock_initial", "knowledge_editor_dock_refresh",
                         "knowledge_editor_dock_expand", "knowledge_editor_dock_collapse",
                         "knowledge_editor_dock_back", "knowledge_editor_dock_stale"):
                self.assertIn(name, names)

    def test_editor_source_alignment_uses_provenance_offsets_not_ambiguous_text(self):
        editor = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        for marker in ("source_span_state", "source_span", "source_line", "source_column",
                       "knowledge_editor_context_source_snapshot",
                       "knowledge_editor_context_formal_statement",
                       "savedSnapshot.equals(disk)", "path.equals(relativePath(file))",
                       "buffer.offsetByCodePoints(0, start)",
                       "buffer.offsetByCodePoints(0, end)",
                       "knowledge_editor_context_span_unavailable"):
            self.assertIn(marker, editor)
        self.assertNotIn("currentText.indexOf(excerpt)", editor)
        bridge = (ROOT / "python/ksys_android_bridge.py").read_text()
        for marker in ("_verified_source_span", "markdown[start:end] != source[\"text\"]",
                       '"source_span_state": "verified"', '"source_span": [start, end]'):
            self.assertIn(marker, bridge)
        for folder in ("values", "values-ru"):
            names = {e.get("name") for e in ET.parse(
                ROOT / f"res/{folder}/knowledge_strings.xml").getroot()}
            for name in ("knowledge_editor_context_source_snapshot",
                         "knowledge_editor_context_formal_statement",
                         "knowledge_editor_context_exact_location",
                         "knowledge_editor_context_span_unavailable",
                         "knowledge_editor_context_truncated_excerpt",
                         "knowledge_editor_context_comparison_disclaimer"):
                self.assertIn(name, names)

    def test_editor_literal_diff_is_bounded_read_only_and_clearly_not_semantic(self):
        folder = ROOT / "java/net/gsantner/markor/knowledge"
        editor = (folder / "KnowledgeDocumentActivity.java").read_text()
        comparer = (folder / "KnowledgeTextDiff.java").read_text()
        self.assertIn("KnowledgeTextDiff.compare(sourceExcerpt, formalStatement)", editor)
        self.assertIn('"verified".equals(record.optString("source_span_state"))', editor)
        self.assertIn('!record.optBoolean("source_text_truncated")', editor)
        self.assertIn('!record.optBoolean("statement_truncated")', editor)
        self.assertIn("new SpannableStringBuilder(content)", editor)
        self.assertIn("value.setText(decorated, TextView.BufferType.SPANNABLE)", editor)
        self.assertIn("MAX_LENGTH = 1024", comparer)
        self.assertIn("MAX_TOKENS = 128", comparer)
        self.assertIn("final int[][] suffix", comparer)
        self.assertNotIn("KnowledgePythonBridge.", comparer)
        for folder_name in ("values", "values-ru"):
            xml = ET.parse(ROOT / f"res/{folder_name}/knowledge_strings.xml").getroot()
            strings = {e.get("name"): e.text for e in xml}
            for name in ("knowledge_editor_text_diff_legend",
                         "knowledge_editor_text_diff_identical",
                         "knowledge_editor_text_diff_unavailable"):
                self.assertIn(name, strings)
            self.assertTrue(strings["knowledge_editor_text_diff_legend"])

    def test_editor_structural_diff_discloses_uncertainty_and_separate_epistemic_roles(self):
        editor = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        bridge = (ROOT / "python/ksys_android_bridge.py").read_text()
        for marker in ("renderStructuralComparison(body,", "knowledge_editor_structural_unavailable",
                       "comparison.optBoolean(\"proves_truth\")",
                       "knowledge_editor_structural_disclaimer"):
            self.assertIn(marker, editor)
        for marker in ("_structural_editor_comparison(", "Formalizer(mode=\"import\")",
                       '"status": "unavailable"', '"proves_truth": False',
                       '"structured_comparison": _structural_editor_comparison('):
            self.assertIn(marker, bridge)
        for folder_name in ("values", "values-ru"):
            names = {element.get("name") for element in ET.parse(
                ROOT / f"res/{folder_name}/knowledge_strings.xml").getroot()}
            for name in ("knowledge_editor_structural_title",
                         "knowledge_editor_structural_same",
                         "knowledge_editor_structural_change",
                         "knowledge_editor_structural_unavailable",
                         "knowledge_editor_structural_disclaimer"):
                self.assertIn(name, names)

    def test_editor_has_responsive_keyboard_safe_workspace_with_permanent_rail(self):
        root = ET.parse(ROOT / "res/layout/knowledge__document_activity.xml").getroot()
        workspace = next(x for x in root.iter() if x.get(ANDROID + "id") ==
                         "@+id/knowledge_editor_workspace")
        children = [x.get(ANDROID + "id") for x in workspace]
        self.assertEqual(["@+id/knowledge_editor_context_panel",
                          "@+id/document__placeholder_fragment"], children)
        editor = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        policy = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeEditorLayoutPolicy.java").read_text()
        for marker in ("applyResponsiveEditorLayout()", "WindowInsetsCompat.Type.ime()",
                       "setDockExpanded(false)", "DOCK_EXPANDED_STATE",
                       "onSaveInstanceState(final Bundle state)",
                       "onConfigurationChanged(final Configuration config)"):
            self.assertIn(marker, editor)
        self.assertIn("smallestDp >= 600 && width >= 720 && height >= 400", policy)
        self.assertIn("Math.max(96, Math.min(200,", policy)

    def test_library_pagination_is_bounded_and_reaches_beyond_250_notes(self):
        shell = ET.parse(ROOT / "res/layout/knowledge__activity.xml").getroot()
        ids = [x.get(ANDROID + "id") for x in shell.iter()]
        for item in ("knowledge_library_pagination", "knowledge_library_previous",
                     "knowledge_library_next", "knowledge_library_page_number"):
            self.assertEqual(1, ids.count("@+id/" + item))
        index = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeLocalIndex.java").read_text()
        hub = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeHubActivity.java").read_text()
        device = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDeviceQaRunner.java").read_text()
        for marker in ("SearchPage searchPage(", "LIMIT ? OFFSET ?",
                       "path COLLATE NOCASE, path COLLATE BINARY",
                       "SELECT COUNT(*)", "nextOffset"):
            self.assertIn(marker, index)
        for marker in ("LIBRARY_PAGE_SIZE = 60", "loadLibraryPage(",
                       "libraryNextOffset", "knowledge_library_page_range",
                       "STATE_LIBRARY_OFFSET", "queryGeneration.incrementAndGet()"):
            self.assertIn(marker, hub)
        self.assertIn('testIndex.searchPage("", 0, 60)', device)
        self.assertIn('"amber", total + 120, 60)', device)

    def test_editor_inline_highlights_are_ephemeral_and_approved_only(self):
        shell = ET.parse(ROOT / "res/layout/knowledge__document_activity.xml").getroot()
        ids = [element.get(ANDROID + "id", "") for element in shell.iter()]
        for name in ("knowledge_editor_highlights", "knowledge_editor_highlight_legend"):
            self.assertEqual(1, ids.count("@+id/" + name), name)
        legend = next(x for x in shell.iter()
                      if x.get(ANDROID + "id") == "@+id/knowledge_editor_highlight_legend")
        self.assertEqual("gone", legend.get(ANDROID + "visibility"))
        bridge = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgePythonBridge.java").read_text()
        self.assertIn('"editor_note_highlights"', bridge)
        editor = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        for marker in ("toggleEditorHighlights()", "BackgroundColorSpan",
                       "snapshot.offsetByCodePoints(previousUtf16,",
                       "snapshot.offsetByCodePoints(startUtf16, end - start)",
                       "text.setSpan(span,", "buffer.removeSpan(span)",
                       "addTextChangedListener(highlightWatcher)",
                       "removeTextChangedListener(highlightWatcher)",
                       "if (preview) clearEditorHighlights()",
                       "onStop()", "only_approved_journal_sources"):
            if marker == "only_approved_journal_sources":
                self.assertIn(marker, (ROOT / "python/ksys_android_bridge.py").read_text())
            else:
                self.assertIn(marker, editor)
        for folder in ("values", "values-ru"):
            names = {element.get("name") for element in ET.parse(
                ROOT / f"res/{folder}/knowledge_strings.xml").getroot()}
            for label in ("knowledge_editor_highlights", "knowledge_editor_highlights_hide",
                          "knowledge_editor_highlights_summary", "knowledge_editor_highlights_mixed",
                          "knowledge_editor_highlights_stale", "knowledge_editor_highlights_truncated"):
                self.assertIn(label, names)

    def test_editor_source_inspector_uses_cursor_and_verified_journal_history(self):
        shell = ET.parse(ROOT / "res/layout/knowledge__document_activity.xml").getroot()
        self.assertEqual(1, sum(x.get(ANDROID + "id") ==
                                "@+id/knowledge_editor_source_inspector" for x in shell.iter()))
        java = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        for marker in ("inspectSourceKnowledge()", "editorSourceRecords(this,",
                       "snapshot.codePointCount(0, Math.min(a, b))",
                       "cutsSurrogate(snapshot, a)", "cutsSurrogate(snapshot, b)",
                       "path.equals(relativePath(file))", "saved.equals(snapshot)",
                       "showContextRecord(record, path,", "knowledge_editor_source_inspector_history",
                       'record.optJSONArray("history")'):
            self.assertIn(marker, java)
        bridge = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgePythonBridge.java").read_text()
        self.assertIn('"editor_source_records"', bridge)
        python = (ROOT / "python/ksys_android_bridge.py").read_text()
        self.assertIn('"only_verified_journal_sources": True', python)
        self.assertIn('"historical_total": len(matched) - active_total', python)
        for folder in ("values", "values-ru"):
            names = {e.get("name") for e in ET.parse(
                ROOT / f"res/{folder}/knowledge_strings.xml").getroot()}
            for label in ("knowledge_editor_source_inspector",
                          "knowledge_editor_source_inspector_none",
                          "knowledge_editor_source_inspector_summary",
                          "knowledge_editor_source_inspector_historical",
                          "knowledge_editor_source_inspector_history_partial"):
                self.assertIn(label, names)

    def test_editor_evidence_links_stay_in_editor_and_recheck_cross_note_source(self):
        java = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgeDocumentActivity.java").read_text()
        for marker in ("inspectRecordEvidence(", "renderRecordEvidence(",
                       "openEvidenceOrigin(", "selectIncomingSource(",
                       "verifiedEvidenceSpan(", "relative.equals(relativePath(target))",
                       "docSha.equals(sha256(disk))", "sourceSha.equals(sha256(disk.substring(a, b)))",
                       "saved.equals(current.getTextString())", "openAtSource(this, target,",
                       "JUMP_PATH", "JUMP_TEXT_SHA", "knowledge_editor_evidence_open"):
            self.assertIn(marker, java)
        bridge = (ROOT / "java/net/gsantner/markor/knowledge/KnowledgePythonBridge.java").read_text()
        self.assertIn('"editor_record_evidence"', bridge)
        python = (ROOT / "python/ksys_android_bridge.py").read_text()
        self.assertIn('"only_journal_records": True, "read_only": True', python)
        self.assertIn('"navigation_verified": False', python)
        for folder in ("values", "values-ru"):
            names = {e.get("name") for e in ET.parse(
                ROOT / f"res/{folder}/knowledge_strings.xml").getroot()}
            for label in ("knowledge_editor_evidence_open", "knowledge_editor_evidence_origins",
                          "knowledge_editor_evidence_unverified", "knowledge_editor_evidence_conflict",
                          "knowledge_editor_evidence_unavailable", "knowledge_editor_evidence_truncated"):
                self.assertIn(label, names)

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
