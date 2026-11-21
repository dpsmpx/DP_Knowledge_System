"""Editor context: approved provenance is separate from tentative relations."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party/markor/app/src/main/python"))

from ksys_android_bridge import (analyze_vault, editor_note_context, inspect_note,
                                 preview_fragment, record_decision, _verified_source_span,
                                 _structural_editor_comparison)
from ksys.semantics.formalize import Formalizer
from ksys.core.clock import FixedClock
from ksys.importer.relations import projected_atoms
from ksys.store.journal import Journal
from ksys.store.workspace import Workspace


class EditorContextTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.workspace = self.base / "private_workspace"
        self.vault = self.base / "synthetic_notes"
        self.vault.mkdir()
        self.notes = {
            "a.md": "# A\nАлекс является разработчиком.\n",
            "b.md": "# B\nАлекс является разработчиком.\n",
            "c.md": "# C\nАлекс не является разработчиком.\n",
        }
        for path, content in self.notes.items():
            (self.vault / path).write_text(content, encoding="utf-8")

    def context(self, markdown=None, verify_files=False):
        return json.loads(editor_note_context(str(self.workspace), "a.md",
                                              self.notes["a.md"] if markdown is None
                                              else markdown,
                                              str(self.vault) if verify_files else None))

    def test_absent_index_and_journal_do_not_create_files(self):
        result = self.context()
        self.assertEqual("dpksys.editor_context.v1", result["protocol"])
        self.assertTrue(result["read_only"])
        self.assertTrue(result["relations_are_unapproved"])
        self.assertEqual("saved_markdown", result["source_snapshot"])
        self.assertEqual("absent", result["relation_index_state"])
        self.assertEqual([], result["evidence_groups"])
        self.assertEqual(0, result["accepted_total"])
        self.assertEqual([], result["candidate_relations"])
        self.assertFalse(self.workspace.exists())

    def test_accepted_journal_provenance_is_not_candidate_index(self):
        note = self.notes["a.md"]
        frag = json.loads(inspect_note("a.md", note))["fragments"][1]["id"]
        packet = json.loads(preview_fragment(str(self.workspace), "a.md", note, frag))
        reading = packet["preview"]["options"][0]["reading_id"]
        record_decision(str(self.workspace), "a.md", note, frag, reading, "claim",
                        packet["source_hash"], packet["journal_head"])
        state = self.context()
        self.assertEqual("absent", state["relation_index_state"])
        self.assertEqual(1, state["accepted_total"])
        rec = state["accepted_records"][0]
        self.assertEqual("claim", rec["role"])
        self.assertEqual("vault_note", rec["provenance_kind"])
        self.assertTrue(rec["source_current"])
        self.assertTrue(rec["locator"])
        self.assertEqual(frag, rec["fragment_id"])
        self.assertIn("Алекс", rec["source_excerpt"])
        self.assertEqual("verified", rec["source_span_state"])
        self.assertEqual("same_structure", rec["structured_comparison"]["status"])
        self.assertEqual("FORMALIZED", rec["structured_comparison"]["source_formalization"])
        self.assertFalse(rec["structured_comparison"]["proves_truth"])
        begin, finish = rec["source_span"]
        self.assertEqual(note[begin:finish], rec["source_excerpt"])
        self.assertEqual([2, 1], [rec["source_line"], rec["source_column"]])
        self.assertFalse(state["accepted_truncated"])
        self.assertEqual(0, state["stale_provenance_count"])

        changed = self.context(note + "Вторая строка.")
        self.assertEqual(1, changed["stale_provenance_count"])
        self.assertFalse(changed["accepted_records"][0]["source_current"])
        self.assertEqual("stale_source", changed["accepted_records"][0]["source_span_state"])
        self.assertEqual("unavailable", changed["accepted_records"][0]["structured_comparison"]["status"])
        self.assertNotIn("source_span", changed["accepted_records"][0])
        self.assertEqual([], Journal(self.workspace / "data/formal/journal.jsonl",
                                     FixedClock()).verify())

    def test_relations_require_exact_current_source_index_and_are_read_only(self):
        analyze_vault(str(self.workspace), str(self.vault))
        db = Workspace(self.workspace).db_path
        before = hashlib.sha256(db.read_bytes()).hexdigest()
        original = {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                    for p in self.notes}
        context = self.context()
        self.assertEqual("current", context["relation_index_state"])
        self.assertEqual(0, context["accepted_total"])
        related = {m["path"]: m for m in context["candidate_relations"]}
        self.assertEqual({"b.md", "c.md"}, set(related))
        self.assertEqual("possible_agreement", related["b.md"]["kind"])
        self.assertEqual("possible_conflict", related["c.md"]["kind"])
        self.assertEqual(1, len(context["evidence_groups"]))
        self.assertEqual((1, 1, 0), (
            context["evidence_groups"][0]["same_polarity_files"],
            context["evidence_groups"][0]["opposite_polarity_files"],
            context["evidence_groups"][0]["mixed_polarity_files"]))
        self.assertFalse(context["evidence_limited"])
        self.assertTrue(context["evidence_checks_limited"])
        self.assertEqual("not_checked", context["evidence_groups"][0]["source_verification"])
        self.assertIsNone(related["c.md"]["context"])
        self.assertEqual({"from": None, "to": None}, related["c.md"]["interval"])
        self.assertTrue(all(item["source_locator"] and item["other_locator"]
                            for item in related.values()))
        self.assertEqual(before, hashlib.sha256(db.read_bytes()).hexdigest())
        self.assertEqual(original, {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                                    for p in self.notes})

        changed = self.context(self.notes["a.md"] + "\nДругой текст.")
        self.assertEqual("stale", changed["relation_index_state"])
        self.assertEqual([], changed["candidate_relations"])
        self.assertEqual([], changed["evidence_groups"])
        self.assertEqual(before, hashlib.sha256(db.read_bytes()).hexdigest())

    def test_editor_evidence_checks_live_other_files_only_when_root_matches(self):
        analyze_vault(str(self.workspace), str(self.vault))
        current = self.context(verify_files=True)
        self.assertEqual("current", current["relation_index_state"])
        self.assertFalse(current["evidence_checks_limited"])
        group = current["evidence_groups"][0]
        self.assertEqual("current", group["source_verification"])
        self.assertEqual((1, 1, 0), (
            group["verified_same_polarity_files"],
            group["verified_opposite_polarity_files"], group["verified_mixed_polarity_files"]))
        self.assertEqual(1, len(group["excerpt_families"]))
        self.assertTrue(group["source_repeated_excerpt"])
        self.assertEqual("R1", group["source_excerpt_family"])
        self.assertFalse(group["independence_assessed"])
        (self.vault / "b.md").write_text("# B\nAnother claim.\n", encoding="utf-8")
        changed = self.context(verify_files=True)["evidence_groups"][0]
        self.assertEqual(1, changed["stale_files"])
        self.assertEqual((0, 1), (changed["verified_same_polarity_files"],
                                  changed["verified_opposite_polarity_files"]))
        self.assertEqual("current", changed["source_verification"])
        self.assertEqual([], changed["excerpt_families"])
        unsaved = self.context(self.notes["a.md"] + "\nUnsaved", verify_files=True)
        self.assertEqual("stale", unsaved["relation_index_state"])
        self.assertEqual([], unsaved["evidence_groups"])

    def test_editor_alias_hints_cite_owner_journal_without_approving_claim(self):
        (self.vault / "d.md").write_text("# D\nБорис является разработчиком.\n",
                                          encoding="utf-8")
        analyze_vault(str(self.workspace), str(self.vault))
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        event = journal.append("alias", {"alias": "борис", "canonical": "алекс"})
        before = hashlib.sha256(Workspace(self.workspace).db_path.read_bytes()).hexdigest()
        context = self.context()
        self.assertEqual("current", context["relation_index_state"])
        hints = [r for r in context["candidate_relations"] if r["kind"] == "alias_candidate"]
        self.assertEqual(["d.md"], [r["path"] for r in hints])
        self.assertEqual([event.id], list(hints[0]["alias_evidence"]["journal_ids"]))
        self.assertTrue(context["relations_are_unapproved"])
        self.assertEqual(0, context["accepted_total"])
        self.assertEqual(before, hashlib.sha256(Workspace(self.workspace).db_path.read_bytes()).hexdigest())

    def test_editor_relation_shows_matching_context_and_period(self):
        analyze_vault(str(self.workspace), str(self.vault))
        assertion = {"mod": "asserted", "neg": False,
                     "atom": {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]},
                     "ctx": "проект-а",
                     "iv": {"from": "2024-05-01", "to": "2024-05-31"}}
        key = projected_atoms({"id": "synthetic", "nodes": [
            {"type": "claim", "assertion": assertion}
        ]}, "a.md", "F:syn", "a:2")[0][4]
        with sqlite3.connect(Workspace(self.workspace).db_path) as con:
            for path in ("a.md", "b.md"):
                con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc=?", (key, path))
        related = {r["path"]: r for r in self.context()["candidate_relations"]}
        self.assertEqual({"b.md"}, set(related))
        self.assertEqual("проект-а", related["b.md"]["context"])
        self.assertEqual({"from": "2024-05-01", "to": "2024-05-31"},
                         related["b.md"]["interval"])
        overview = self.context()["evidence_groups"][0]
        self.assertEqual(1, overview["same_polarity_files"])
        self.assertEqual(0, overview["opposite_polarity_files"])
        self.assertEqual("проект-а", overview["context"])
        self.assertEqual({"from": "2024-05-01", "to": "2024-05-31"},
                         overview["interval"])

    def test_editor_shows_temporal_overlap_without_conflict_verdict(self):
        analyze_vault(str(self.workspace), str(self.vault))
        db = Workspace(self.workspace).db_path
        before = {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                  for p in self.notes}
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}
        with sqlite3.connect(db) as con:
            for path, start, end in (("a.md", "2023-01-01", "2024-01-01"),
                                     ("b.md", "2024-01-01", "2025-01-01")):
                assertion = {"mod": "asserted", "neg": False, "atom": atom,
                             "iv": {"from": start, "to": end}}
                row = projected_atoms({"id": "synthetic", "nodes": [
                    {"type": "claim", "assertion": assertion}
                ]}, path, "F:synthetic", "test:1")[0]
                con.execute("UPDATE candidate_atoms SET atom_key=?, scope_key=?, "
                            "valid_from=?, valid_to=? WHERE doc=?",
                            (row[4], row[7], row[8], row[9], path))
        index_before = hashlib.sha256(db.read_bytes()).hexdigest()
        context = self.context()
        self.assertEqual("current", context["relation_index_state"])
        self.assertEqual(["b.md"], [r["path"] for r in context["candidate_relations"]])
        item = context["candidate_relations"][0]
        self.assertEqual("temporal_overlap", item["kind"])
        self.assertTrue(item["source_excerpt_available"])
        self.assertTrue(item["other_excerpt_available"])
        self.assertEqual({"from": "2024-01-01", "to": "2024-01-01"},
                         item["overlap_interval"])
        self.assertTrue(context["relations_are_unapproved"])
        self.assertEqual(index_before, hashlib.sha256(db.read_bytes()).hexdigest())
        self.assertEqual(before, {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                                  for p in self.notes})

    def test_editor_shows_separated_dated_readings_without_claiming_change(self):
        analyze_vault(str(self.workspace), str(self.vault))
        db = Workspace(self.workspace).db_path
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}
        with sqlite3.connect(db) as con:
            for path, start, end in (("a.md", "2023-01-01", "2023-12-31"),
                                     ("b.md", "2024-01-01", "2024-12-31")):
                assertion = {"mod": "asserted", "neg": path == "b.md", "atom": atom,
                             "iv": {"from": start, "to": end}}
                row = projected_atoms({"id": "synthetic", "nodes": [
                    {"type": "claim", "assertion": assertion}
                ]}, path, "F:synthetic", "test:1")[0]
                con.execute("UPDATE candidate_atoms SET atom_key=?, scope_key=?, "
                            "valid_from=?, valid_to=?, negated=? WHERE doc=?",
                            (row[4], row[7], row[8], row[9], row[6], path))
        before = hashlib.sha256(db.read_bytes()).hexdigest()
        context = self.context()
        self.assertEqual("current", context["relation_index_state"])
        result = [r for r in context["candidate_relations"] if r["path"] == "b.md"]
        self.assertEqual(1, len(result))
        self.assertEqual("temporal_separation", result[0]["kind"])
        self.assertTrue(result[0]["source_excerpt_available"])
        self.assertTrue(result[0]["other_excerpt_available"])
        self.assertEqual("source_before_other", result[0]["relative_order"])
        self.assertEqual(0, result[0]["gap_days"])
        self.assertTrue(result[0]["opposing_polarity"])
        self.assertTrue(context["relations_are_unapproved"])
        self.assertEqual(before, hashlib.sha256(db.read_bytes()).hexdigest())

    def test_accepted_opposites_show_possible_journal_conflict(self):
        for path in ("a.md", "c.md"):
            note = self.notes[path]
            fragment = json.loads(inspect_note(path, note))["fragments"][1]["id"]
            packet = json.loads(preview_fragment(str(self.workspace), path, note, fragment))
            record_decision(str(self.workspace), path, note, fragment,
                            packet["preview"]["options"][0]["reading_id"], "claim",
                            packet["source_hash"], packet["journal_head"])
        state = self.context()
        self.assertEqual(1, state["accepted_total"])
        record = state["accepted_records"][0]
        self.assertTrue(record["conflicts_available"])
        self.assertGreater(record["conflict_count"], 0)
        self.assertEqual("absent", state["relation_index_state"],
                         "Journal conflict must not be mistaken for an index relation")

    def test_invalid_source_and_excess_size_are_rejected(self):
        for path in ("../outside.md", "/abs.md", "file.txt"):
            with self.assertRaises(ValueError):
                editor_note_context(str(self.workspace), path, self.notes["a.md"])
        with self.assertRaises(ValueError):
            editor_note_context(str(self.workspace), "a.md", "x" * 2_000_001)
        self.assertFalse(self.workspace.exists())

    def test_structured_comparison_reports_exact_ir_field_changes_without_truth_claim(self):
        formalizer = Formalizer(mode="import")
        positive = formalizer.formalize("Алекс является разработчиком.").selected.nodes[0]
        negative = formalizer.formalize("Алекс не является разработчиком.").selected.nodes[0]
        same = _structural_editor_comparison("Алекс является разработчиком.", positive, True)
        self.assertEqual("same_structure", same["status"])
        self.assertEqual([], same["changed_fields"])
        opposite = _structural_editor_comparison("Алекс является разработчиком.", negative, True)
        self.assertEqual("different_structure", opposite["status"])
        self.assertEqual(["negation"], [row["field"] for row in opposite["changed_fields"]])
        self.assertEqual("false", opposite["changed_fields"][0]["source"])
        self.assertEqual("true", opposite["changed_fields"][0]["accepted"])
        self.assertFalse(opposite["proves_truth"])
        for source, verified in (("Алекс (разработчик);", True),
                                 ("Алекс является разработчиком.", False),
                                 ("слово " * 55, True),
                                 ("x" * 1001, True)):
            result = _structural_editor_comparison(source, positive, verified)
            self.assertEqual("unavailable", result["status"])
            self.assertFalse(result["changed_fields"])
        self.assertFalse(self.workspace.exists(), "A pure IR comparison must not open the vault")

    def test_duplicate_excerpt_uses_approved_span_not_first_text_match(self):
        note = "# 😀\nАлекс является разработчиком.\nАлекс является разработчиком.\n"
        fragments = json.loads(inspect_note("a.md", note))["fragments"]
        target = fragments[2]["id"]
        packet = json.loads(preview_fragment(str(self.workspace), "a.md", note, target))
        record_decision(str(self.workspace), "a.md", note, target,
                        packet["preview"]["options"][0]["reading_id"], "claim",
                        packet["source_hash"], packet["journal_head"])
        rec = self.context(note)["accepted_records"][0]
        self.assertEqual(target, rec["fragment_id"])
        self.assertEqual("verified", rec["source_span_state"])
        self.assertEqual(note.rindex("Алекс является"), rec["source_span"][0])
        self.assertEqual(3, rec["source_line"])
        self.assertEqual(1, rec["source_column"])
        # Python spans are Unicode code points, not Android UTF-16 indices.
        self.assertNotEqual(len(note[:rec["source_span"][0]].encode("utf-16-le")) // 2,
                            rec["source_span"][0])

    def test_legacy_and_corrupt_provenance_never_guess_source_offset(self):
        note = "Повтор. Повтор."
        sha = hashlib.sha256(note.encode("utf-8")).hexdigest()
        metadata = {"document_sha": sha, "text": "Повтор."}
        self.assertEqual("missing", _verified_source_span(metadata, note, sha)["source_span_state"])
        self.assertEqual("mismatch", _verified_source_span(
            dict(metadata, span=[3, 10]), note, sha)["source_span_state"])
        self.assertEqual("missing", _verified_source_span(
            dict(metadata, span=[True, 7]), note, sha)["source_span_state"])
        self.assertEqual("mismatch", _verified_source_span(
            dict(metadata, span=[0, 999]), note, sha)["source_span_state"])
        self.assertEqual("stale_source", _verified_source_span(
            dict(metadata, span=[0, 7]), note, "0" * 64)["source_span_state"])
        aligned = _verified_source_span(dict(metadata, span=[8, 15]), note, sha)
        self.assertEqual({"source_span_state": "verified", "source_span": [8, 15],
                          "source_line": 1, "source_column": 9}, aligned)


if __name__ == "__main__":
    unittest.main()
