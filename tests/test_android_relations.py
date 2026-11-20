"""Synthetic cross-document claim relationships, without authoritative writes."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party" / "markor" / "app" / "src" / "main" / "python"))

import ksys_android_bridge as bridge
from ksys_android_bridge import analyze_vault, note_relationships, editor_note_context
from ksys.audit.library import _source_conflict_count, _source_conflict_page
from ksys.importer.relations import (RELATIONS_VERSION, projected_atoms,
                                     projected_modal_atoms, related_claims,
                                     indexed_related_claims, indexed_source_evidence,
                                     _evidence_excerpts)
from ksys.importer.aliases import verified_owner_aliases, one_symbol_variants
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal
from ksys.store.workspace import Workspace


class RelationTests(unittest.TestCase):
    def test_cross_file_evidence_counts_distinct_notes_not_parser_rows(self):
        self._import()
        evidence = self._relations("a.md")
        self.assertFalse(evidence["evidence_limited"])
        self.assertEqual(1, len(evidence["evidence_groups"]))
        group = evidence["evidence_groups"][0]
        self.assertEqual("is_a", group["predicate"])
        self.assertEqual((2, 1, 0), (group["same_polarity_files"],
                        group["opposite_polarity_files"], group["mixed_polarity_files"]))
        self.assertEqual("cross_source_mixed", group["evidence_pattern"])
        self.assertEqual({"b.md", "c.md", "sub/e.markdown"},
                         {s["path"] for s in group["other_sources"]})
        self.assertEqual("not_assessed", group["confidence"])
        self.assertEqual("unapproved_candidates", group["authority"])
        self.assertEqual("indexed_snapshot_unverified", group["source_freshness"])
        self.assertTrue(all(s["locator"] for s in group["other_sources"]))

        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            # A new candidate interpretation/fragment in the *same* file
            # must never be counted as another independent source.
            row = con.execute("SELECT * FROM candidate_atoms WHERE doc='b.md'").fetchone()
            con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)",
                        ("second-reading", *row[1:]))
        after = self._relations("a.md")["evidence_groups"][0]
        self.assertEqual((2, 1, 0), (after["same_polarity_files"],
                        after["opposite_polarity_files"], after["mixed_polarity_files"]))

    def test_excerpt_representation_is_bounded_and_matches_one_source_fragment(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        baseline = self._relations(verified=True)["evidence_groups"][0]
        self.assertIn("Алекс является разработчиком", baseline["source_excerpt"])
        self.assertFalse(baseline["source_excerpt_truncated"])
        first = next(x for x in baseline["other_sources"] if x["path"] == "b.md")
        self.assertTrue(first["excerpt_available"])
        self.assertIn("Алекс является разработчиком", first["excerpt"])
        self.assertFalse(first["excerpt_truncated"])
        with sqlite3.connect(db) as con:
            row = con.execute("SELECT id, locator, text_hash FROM fragments "
                              "WHERE doc='b.md' ORDER BY ordinal LIMIT 1").fetchone()
            original = con.execute("SELECT * FROM candidate_atoms WHERE doc='b.md'").fetchone()
            # Simulated extra parser reading, attached to an earlier fragment.
            # The locator, ID, excerpt and hash must all come from one row.
            con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)",
                        ("synthetic-second-reading", row[0], "b.md", row[1],
                         *original[4:]))
        matched = next(x for x in self._relations()["evidence_groups"][0]["other_sources"]
                       if x["path"] == "b.md")
        self.assertEqual(row[0], matched["fragment_id"])
        self.assertEqual(row[1], matched["locator"])
        self.assertEqual(row[2], matched["excerpt_hash"])

        with sqlite3.connect(db) as con:
            con.execute("UPDATE fragments SET plain=? WHERE id=?", ("X" * 600, row[0]))
        clipped = next(x for x in self._relations()["evidence_groups"][0]["other_sources"]
                       if x["path"] == "b.md")
        self.assertEqual("X" * 240, clipped["excerpt"])
        self.assertTrue(clipped["excerpt_truncated"])

    def test_wrong_document_fragment_cannot_be_presented_as_its_excerpt(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            row = con.execute("SELECT * FROM candidate_atoms WHERE doc='b.md'").fetchone()
            con.execute("INSERT INTO documents(path,sha256,size,status,kind) "
                        "VALUES('ghost.md',?,20,'PARSED','markdown')", ("0" * 64,))
            con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)",
                        ("ghost-reading", row[1], "ghost.md", *row[3:]))
        group = self._relations()["evidence_groups"][0]
        ghost = next(x for x in group["other_sources"] if x["path"] == "ghost.md")
        self.assertFalse(ghost["excerpt_available"])
        self.assertIsNone(ghost["excerpt"])
        self.assertIsNone(ghost["excerpt_hash"])
        self.assertEqual("", ghost["locator"])

    def test_excerpt_batch_uses_one_ranked_sql_query_per_evidence_group(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            queries = []
            con.set_trace_callback(queries.append)
            groups, _ = indexed_source_evidence(con, "a.md")
            con.set_trace_callback(None)
            ranked = [query for query in queries
                      if query.lstrip().upper().startswith("WITH RANKED AS")]
            self.assertEqual(len(groups), len(ranked))
            self.assertTrue(groups)
            self.assertEqual(1 + 2 * len(groups), len(queries))
            with self.assertRaises(ValueError):
                _evidence_excerpts(con, "key", [])
            with self.assertRaises(ValueError):
                _evidence_excerpts(con, "key", ["file.md"] * 34)

    def test_live_hash_checks_and_possible_duplicate_excerpts_do_not_approve_claims(self):
        original = {name: hashlib.sha256((self.vault / name).read_bytes()).hexdigest()
                    for name in self.notes}
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            indexed_rows = sorted(con.iterdump())
        result = self._relations(verified=True)
        group = result["evidence_groups"][0]
        self.assertFalse(result["evidence_checks_limited"])
        self.assertEqual("current", group["source_verification"])
        self.assertEqual((2, 1, 0), (
            group["verified_same_polarity_files"],
            group["verified_opposite_polarity_files"],
            group["verified_mixed_polarity_files"]))
        self.assertEqual((3, 0, 0, 0), (
            group["current_files"], group["stale_files"],
            group["unavailable_files"], group["unchecked_files"]))
        same = {source["path"]: source for source in group["other_sources"]}
        self.assertTrue(same["b.md"]["repeated_excerpt"])
        self.assertTrue(same["sub/e.markdown"]["repeated_excerpt"])
        self.assertFalse(same["c.md"]["repeated_excerpt"])
        self.assertTrue(group["source_repeated_excerpt"])
        self.assertEqual("R1", group["source_excerpt_family"])
        self.assertEqual("R1", same["b.md"]["excerpt_family"])
        self.assertEqual("R1", same["sub/e.markdown"]["excerpt_family"])
        self.assertEqual([{"label": "R1", "paths": ["a.md", "b.md", "sub/e.markdown"]}],
                         group["excerpt_families"])
        self.assertEqual((4, 2), (group["current_excerpt_files"],
                                  group["distinct_representative_excerpts"]))
        self.assertFalse(group["independence_assessed"])
        self.assertEqual(2, group["repeated_excerpt_files"])
        self.assertEqual(0, group["identical_document_files"])
        self.assertEqual("not_assessed", group["confidence"])
        self.assertEqual("unapproved_candidates", group["authority"])
        self.assertEqual(original, {name: hashlib.sha256((self.vault / name).read_bytes()).hexdigest()
                                    for name in self.notes})
        with sqlite3.connect(db) as con:
            self.assertEqual(indexed_rows, sorted(con.iterdump()))
        self.assertFalse((self.workspace / "data").exists())

    def test_changed_missing_or_changed_source_files_are_not_current(self):
        self._import()
        (self.vault / "b.md").write_text("# Changed\nNothing relevant.\n", encoding="utf-8")
        (self.vault / "c.md").unlink()
        group = self._relations(verified=True)["evidence_groups"][0]
        self.assertEqual((1, 1, 1), (
            group["current_files"], group["stale_files"], group["unavailable_files"]))
        self.assertEqual((1, 0, 0), (
            group["verified_same_polarity_files"],
            group["verified_opposite_polarity_files"],
            group["verified_mixed_polarity_files"]))
        statuses = {s["path"]: s["verification"] for s in group["other_sources"]}
        self.assertEqual("stale", statuses["b.md"])
        self.assertEqual("unavailable", statuses["c.md"])
        self.assertEqual("current", statuses["sub/e.markdown"])
        self.assertFalse(any("repeated_excerpt" in item for item in group["other_sources"]
                             if item["verification"] != "current"))
        self.assertEqual([{"label": "R1", "paths": ["a.md", "sub/e.markdown"]}],
                         group["excerpt_families"])

        (self.vault / "a.md").write_text("# A changed\n", encoding="utf-8")
        stale = self._relations(verified=True)["evidence_groups"][0]
        self.assertEqual("stale", stale["source_verification"])
        self.assertEqual((0, 0, 0), (
            stale["verified_same_polarity_files"],
            stale["verified_opposite_polarity_files"],
            stale["verified_mixed_polarity_files"]))

    def test_wrong_vault_disables_live_checks_and_budget_marks_remainder(self):
        self._import()
        unverified = self._relations()
        self.assertTrue(unverified["evidence_checks_limited"])
        self.assertEqual("not_checked", unverified["evidence_groups"][0]["source_verification"])
        with tempfile.TemporaryDirectory() as other_root:
            wrong = json.loads(note_relationships(
                str(self.workspace), "a.md", other_root))
        self.assertTrue(wrong["evidence_checks_limited"])
        self.assertEqual(3, wrong["evidence_groups"][0]["unchecked_files"])

        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            row = con.execute("SELECT * FROM candidate_atoms WHERE doc='b.md'").fetchone()
            for i in range(35):
                path = f"many/note{i:02}.md"
                con.execute("INSERT INTO documents (path,sha256,size,status,kind) "
                            "VALUES (?,?,?,?,?)", (path, "0"*64, 12, "PARSED", "markdown"))
                con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)",
                            (f"many-{i}", row[1], path, *row[3:]))
        capped = self._relations(verified=True)
        group = capped["evidence_groups"][0]
        self.assertTrue(capped["evidence_checks_limited"])
        self.assertTrue(capped["evidence_limited"])
        self.assertEqual(32, group["indexed_other_files"])
        self.assertEqual(16, 1 + group["current_files"] + group["stale_files"]
                         + group["unavailable_files"])
        self.assertEqual(17, group["unchecked_files"])
        self.assertTrue(any(s["verification"] == "not_checked"
                            for s in group["other_sources"]))

    def test_whole_file_copy_is_detected_without_independence_claim(self):
        self.notes["b.md"] = self.notes["a.md"]
        (self.vault / "b.md").write_text(self.notes["b.md"], encoding="utf-8")
        self._import()
        group = self._relations(verified=True)["evidence_groups"][0]
        self.assertEqual(1, group["identical_document_files"])
        self.assertTrue(next(s for s in group["other_sources"]
                             if s["path"] == "b.md")["identical_document"])
        self.assertTrue(group["source_identical_document"])
        self.assertEqual("D1", group["source_document_family"])
        self.assertEqual([{"label": "D1", "paths": ["a.md", "b.md"]}],
                         group["document_families"])

    def test_full_file_copies_between_other_sources_are_symmetric(self):
        self.notes["sub/e.markdown"] = self.notes["b.md"]
        (self.vault / "sub/e.markdown").write_text(self.notes["b.md"], encoding="utf-8")
        self._import()
        group = self._relations(verified=True)["evidence_groups"][0]
        self.assertFalse(group["source_identical_document"])
        self.assertEqual(2, group["identical_document_files"])
        self.assertEqual([{"label": "D1", "paths": ["b.md", "sub/e.markdown"]}],
                         group["document_families"])
        other = {source["path"]: source for source in group["other_sources"]}
        self.assertTrue(other["b.md"]["identical_document"])
        self.assertTrue(other["sub/e.markdown"]["identical_document"])
        self.assertEqual("D1", other["b.md"]["document_family"])
        self.assertEqual("D1", other["sub/e.markdown"]["document_family"])
        self.assertFalse(other["c.md"]["identical_document"])

    def test_separate_excerpts_form_separate_families_even_across_opposition(self):
        self.notes["f.md"] = self.notes["c.md"]
        (self.vault / "f.md").write_text(self.notes["f.md"], encoding="utf-8")
        self._import()
        group = self._relations(verified=True)["evidence_groups"][0]
        self.assertEqual(2, len(group["excerpt_families"]))
        self.assertEqual(4, group["repeated_excerpt_files"])
        self.assertEqual(2, group["distinct_representative_excerpts"])
        self.assertEqual([{"label": "R1", "paths": ["a.md", "b.md", "sub/e.markdown"]},
                          {"label": "R2", "paths": ["c.md", "f.md"]}],
                         group["excerpt_families"])
        self.assertEqual((2, 2, 0), (
            group["verified_same_polarity_files"],
            group["verified_opposite_polarity_files"],
            group["verified_mixed_polarity_files"]))
        self.assertFalse(group["independence_assessed"])

    def test_mixed_polarity_in_one_file_never_double_votes(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            row = con.execute("SELECT * FROM candidate_atoms WHERE doc='b.md'").fetchone()
            altered = list(row)
            altered[6] = 1
            con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)", altered)
        evidence = self._relations("a.md")["evidence_groups"][0]
        self.assertEqual((1, 1, 1), (evidence["same_polarity_files"],
                         evidence["opposite_polarity_files"],
                         evidence["mixed_polarity_files"]))
        mixed = next(s for s in evidence["other_sources"] if s["path"] == "b.md")
        self.assertEqual("mixed", mixed["relation"])

        with sqlite3.connect(db) as con:
            row = con.execute("SELECT * FROM candidate_atoms WHERE doc='a.md'").fetchone()
            altered = list(row)
            altered[6] = 1
            con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)", altered)
        source_mixed = self._relations("a.md")["evidence_groups"][0]
        self.assertEqual("source_mixed", source_mixed["evidence_pattern"])
        self.assertEqual("mixed", source_mixed["source_polarity"])
        self.assertEqual(0, source_mixed["same_polarity_files"])
        self.assertEqual(0, source_mixed["opposite_polarity_files"])

    def test_unrelated_and_aliased_names_are_not_exact_evidence(self):
        self._import()
        group = self._relations("d.md")["evidence_groups"][0]
        self.assertEqual(0, group["indexed_other_files"])
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("alias", {"alias": "борис", "canonical": "алекс"})
        result = self._relations("d.md")
        self.assertTrue(result["matches"])  # separate alias hints still exist
        self.assertEqual(0, result["evidence_groups"][0]["indexed_other_files"])

    def test_evidence_document_cap_is_labeled_incomplete(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            row = con.execute("SELECT * FROM candidate_atoms WHERE doc='b.md'").fetchone()
            for i in range(35):
                path = f"many/note{i:02}.md"
                con.execute("INSERT INTO documents (path,sha256,size,status,kind) "
                            "VALUES (?,?,?,?,?)", (path, "0"*64, 12, "PARSED", "markdown"))
                con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)",
                            (f"many-{i}", row[1], path, *row[3:]))
        result = self._relations("a.md")
        self.assertTrue(result["evidence_limited"])
        group = result["evidence_groups"][0]
        self.assertTrue(group["limited"])
        self.assertEqual(32, group["indexed_other_files"])
        self.assertEqual(32, len(group["other_sources"]))
        self.assertEqual("not_assessed", group["confidence"])

    def test_evidence_signature_cap_marks_note_coverage_incomplete(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        from ksys.core.ids import canonical_json
        with sqlite3.connect(db) as con:
            row = con.execute("SELECT * FROM candidate_atoms WHERE doc='a.md'").fetchone()
            for i in range(18):
                synthetic = list(row)
                synthetic[0] = f"source-extra-{i}"
                synthetic[5] = f"new_predicate_{i:02}"
                key = json.loads(row[4])
                key["p"] = synthetic[5]
                synthetic[4] = canonical_json(key)
                synthetic[7] = canonical_json({k: v for k, v in key.items() if k != "iv"})
                con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)", synthetic)
        result = self._relations("a.md")
        self.assertTrue(result["evidence_limited"])
        self.assertEqual(16, len(result["evidence_groups"]))
        self.assertTrue(all(group["confidence"] == "not_assessed"
                            for group in result["evidence_groups"]))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.vault = self.base / "synthetic_notes"
        self.vault.mkdir()
        self.workspace = self.base / "private_workspace"
        self.notes = {
            "a.md": "# A\nАлекс является разработчиком.\n",
            "b.md": "# B\nАлекс является разработчиком.\n",
            "c.md": "# C\nАлекс не является разработчиком.\n",
            "d.md": "# D\nБорис является разработчиком.\n",
            "sub/e.markdown": "# E\nАлекс является разработчиком.\n",
        }
        (self.vault / "sub").mkdir()
        for name, content in self.notes.items():
            (self.vault / name).write_text(content, encoding="utf-8")

    def _import(self, callback=None):
        return json.loads(analyze_vault(str(self.workspace), str(self.vault), callback))

    def _relations(self, path="a.md", verified=False):
        return json.loads(note_relationships(str(self.workspace), path,
                                             str(self.vault) if verified else None))

    def _atoms(self):
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            return con.execute(
                "SELECT doc, atom_key, negated FROM candidate_atoms ORDER BY doc").fetchall()

    def test_precise_agreement_and_opposition_have_two_source_locators(self):
        before = {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                  for p in self.notes}
        self._import()
        result = self._relations()
        self.assertEqual("dpksys.relations.v1", result["protocol"])
        self.assertTrue(result["read_only"])
        self.assertTrue(result["requires_human_review"])
        links = {m["path"]: m for m in result["matches"]}
        self.assertEqual({"b.md", "c.md", "sub/e.markdown"}, set(links))
        self.assertEqual("possible_conflict", links["c.md"]["kind"])
        self.assertEqual("possible_agreement", links["b.md"]["kind"])
        self.assertEqual("is_a", links["c.md"]["predicate"])
        self.assertIsNone(links["c.md"]["context"])
        self.assertEqual({"from": None, "to": None}, links["c.md"]["interval"])
        self.assertTrue(all(link["source_locator"] and link["other_locator"]
                            and link["source_fragment"] and link["other_fragment"]
                            for link in links.values()))
        self.assertFalse(result["truncated"])
        self.assertFalse((self.workspace / "data").exists())
        self.assertEqual(before, {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                                  for p in self.notes})

    def test_exact_claim_pairs_require_both_live_markdown_hashes(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            before = sorted(con.iterdump())
        unchecked = self._relations("a.md")
        self.assertTrue(unchecked["relation_checks_limited"])
        self.assertTrue(all(m["source_verification"] == "not_checked"
                            and m["other_verification"] == "not_checked"
                            and not m["live_source_pair"] for m in unchecked["matches"]))

        verified = self._relations("a.md", verified=True)
        self.assertFalse(verified["relation_checks_limited"])
        self.assertTrue(all(m["source_verification"] == "current"
                            and m["other_verification"] == "current"
                            and m["live_source_pair"] for m in verified["matches"]))
        with sqlite3.connect(db) as con:
            self.assertEqual(before, sorted(con.iterdump()))
        editor = json.loads(editor_note_context(str(self.workspace), "a.md",
                            self.notes["a.md"], str(self.vault)))
        self.assertFalse(editor["relation_checks_limited"])
        self.assertTrue(all(m["live_source_pair"] for m in editor["candidate_relations"]
                            if m["kind"] in ("possible_agreement", "possible_conflict")))

        (self.vault / "b.md").write_text(self.notes["b.md"] + "\n", encoding="utf-8")
        (self.vault / "c.md").unlink()
        historical = self._relations("a.md", verified=True)
        statuses = {m["path"]: m["other_verification"] for m in historical["matches"]}
        self.assertEqual("stale", statuses["b.md"])
        self.assertEqual("unavailable", statuses["c.md"])
        self.assertEqual("current", statuses["sub/e.markdown"])
        self.assertFalse(historical["relation_checks_limited"])  # checked != current
        self.assertFalse(next(m for m in historical["matches"]
                              if m["path"] == "b.md")["live_source_pair"])
        self.assertTrue(next(m for m in historical["matches"]
                             if m["path"] == "sub/e.markdown")["live_source_pair"])

        (self.vault / "a.md").write_text(self.notes["a.md"] + "\n", encoding="utf-8")
        stale_source = self._relations("a.md", verified=True)
        self.assertTrue(all(m["source_verification"] == "stale"
                            and not m["live_source_pair"] for m in stale_source["matches"]))
        with tempfile.TemporaryDirectory() as wrong_root:
            wrong = json.loads(note_relationships(str(self.workspace), "a.md", wrong_root))
        self.assertTrue(wrong["relation_checks_limited"])
        self.assertTrue(all(m["source_verification"] == "not_checked"
                            and m["other_verification"] == "not_checked"
                            for m in wrong["matches"]))

    def test_unrelated_entities_are_not_equated(self):
        self._import()
        self.assertEqual([], self._relations("d.md")["matches"])
        with self.assertRaises(ValueError):
            self._relations("../a.md")

    def test_only_verified_direct_human_alias_yields_separate_candidate(self):
        original = {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                    for p in self.notes}
        self._import()
        self.assertEqual([], self._relations("d.md")["matches"])
        before_atoms = self._atoms()
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        decision = journal.append("alias", {"alias": "борис", "canonical": "алекс"})
        self.assertEqual([], journal.verify())
        hinted = self._relations("d.md")
        self.assertFalse(hinted["alias_search_limited"])
        self.assertEqual({"a.md", "b.md", "c.md", "sub/e.markdown"},
                         {r["path"] for r in hinted["matches"]})
        self.assertTrue(all(r["kind"] == "alias_candidate" for r in hinted["matches"]))
        for relation in hinted["matches"]:
            self.assertEqual("борис", relation["alias_evidence"]["source_symbol"])
            self.assertEqual("алекс", relation["alias_evidence"]["other_symbol"])
            self.assertEqual("алекс", relation["alias_evidence"]["canonical_symbol"])
            self.assertEqual([decision.id], list(relation["alias_evidence"]["journal_ids"]))
            self.assertEqual({"from": None, "to": None}, relation["interval"])
        self.assertTrue(next(r for r in hinted["matches"] if r["path"] == "c.md")
                        ["opposing_polarity"])
        unchecked_alias = self._relations("d.md")
        self.assertTrue(unchecked_alias["relation_checks_limited"])
        self.assertTrue(all(not r["live_source_pair"] for r in unchecked_alias["matches"]))
        current_alias = self._relations("d.md", verified=True)
        self.assertFalse(current_alias["relation_checks_limited"])
        self.assertTrue(all(r["source_verification"] == "current"
                            and r["other_verification"] == "current"
                            and r["live_source_pair"] for r in current_alias["matches"]))
        (self.vault / "a.md").write_text(self.notes["a.md"] + "\n", encoding="utf-8")
        stale_alias = self._relations("d.md", verified=True)
        a = next(r for r in stale_alias["matches"] if r["path"] == "a.md")
        self.assertEqual("stale", a["other_verification"])
        self.assertFalse(a["live_source_pair"])
        self.assertEqual([decision.id], a["alias_evidence"]["journal_ids"])
        (self.vault / "a.md").write_text(self.notes["a.md"], encoding="utf-8")
        # An alias never creates an exact-key conflict in the source audit.
        with sqlite3.connect(self.workspace / "var" / "ks.sqlite") as con:
            self.assertEqual(1, _source_conflict_count(con))  # existing Алекс vs not Алекс
        from_a = {r["path"]: r["kind"] for r in self._relations("a.md")["matches"]}
        self.assertEqual("possible_conflict", from_a["c.md"])
        self.assertEqual("alias_candidate", from_a["d.md"])
        self.assertEqual(original, {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                                    for p in self.notes})
        self.assertEqual(["alias"], [e.op for e in journal.entries()])
        self.assertEqual(before_atoms, self._atoms())

    def test_exact_and_alias_checks_cannot_escape_shared_sixteen_file_budget(self):
        self._import()
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("alias", {"alias": "борис", "canonical": "алекс"})
        actual = self._relations("d.md")["matches"]
        self.assertTrue(actual and all(m["kind"] == "alias_candidate" for m in actual))
        matches = actual + [{"kind": "alias_candidate", "path": f"missing-{i:02}.md"}
                            for i in range(20)]
        checked_paths = []
        hash_file = bridge._entity_live_hash

        def counted_hash(root, path):
            checked_paths.append(path)
            return hash_file(root, path)

        with sqlite3.connect(self.workspace / "var/ks.sqlite") as con:
            with patch.object(bridge, "_entity_live_hash", side_effect=counted_hash):
                evidence, modal, temporal, relation = bridge._verify_evidence_freshness(
                    con, str(self.vault), "d.md", [], matches)
        self.assertFalse(evidence)
        self.assertFalse(modal)
        self.assertFalse(temporal)
        self.assertTrue(relation)
        self.assertEqual(bridge.MAX_EVIDENCE_LIVE_CHECKS, len(checked_paths))
        self.assertEqual(len(checked_paths), len(set(checked_paths)))
        self.assertTrue(all(m["live_source_pair"] for m in actual))
        self.assertTrue(any(m["other_verification"] == "not_checked"
                            and not m["live_source_pair"] for m in matches[len(actual):]))
        self.assertEqual(["alias"], [entry.op for entry in journal.entries()])

    def test_rebound_cycle_and_non_owner_aliases_are_not_trusted(self):
        self._import()
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("alias", {"alias": "борис", "canonical": "алекс"},
                       actor="peer:unknown")
        self.assertEqual([], self._relations("d.md")["matches"])
        journal.append("alias", {"alias": "борис", "canonical": "алекс"})
        self.assertTrue(self._relations("d.md")["matches"])
        journal.append("alias", {"alias": "борис", "canonical": "другой"})
        self.assertEqual([], self._relations("d.md")["matches"])

        with tempfile.TemporaryDirectory() as temp:
            other_journal = Journal(Path(temp) / "journal.jsonl", FixedClock())
            other_journal.append("alias", {"alias": "борис", "canonical": "алекс"})
            other_journal.append("alias", {"alias": "алекс", "canonical": "борис"})
            self.assertEqual([], other_journal.verify())
            snapshot = verified_owner_aliases(other_journal.entries(), "human:me")
            self.assertEqual({}, snapshot.by_symbol)
            other_journal.append("alias", {"alias": "алекс", "canonical": "саш"})
            self.assertEqual({}, verified_owner_aliases(
                other_journal.entries(), "human:me").by_symbol)

    def test_alias_variants_require_exact_other_arguments_and_qualifiers(self):
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("alias", {"alias": "борис", "canonical": "алекс"})
        snapshot = verified_owner_aliases(journal.entries(), "human:me")
        claim = {"p": "knows", "args": [{"k": "sym", "v": "борис"},
                                         {"k": "str", "v": "Алекс"}],
                 "ctx": "школа", "iv": {"from": "2024-01-01", "to": None}}
        generated = list(one_symbol_variants(json.dumps(claim), snapshot))
        self.assertEqual(1, len(generated))
        alternate = json.loads(generated[0][0])
        self.assertEqual("алекс", alternate["args"][0]["v"])
        self.assertEqual({"k": "str", "v": "Алекс"}, alternate["args"][1])
        self.assertEqual("школа", alternate["ctx"])
        self.assertEqual({"from": "2024-01-01", "to": None}, alternate["iv"])
        self.assertEqual([], list(one_symbol_variants("broken-JSON", snapshot)))

    def test_two_independent_alias_decisions_are_both_cited(self):
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        a = journal.append("alias", {"alias": "борис", "canonical": "человек"})
        b = journal.append("alias", {"alias": "алекс", "canonical": "человек"})
        snapshot = verified_owner_aliases(journal.entries(), "human:me")
        base = {"p": "is_a", "args": [{"k": "sym", "v": "борис"}],
                "ctx": None, "iv": {"from": None, "to": None}}
        candidates = dict(one_symbol_variants(json.dumps(base), snapshot))
        alternate = dict(base, args=[{"k": "sym", "v": "алекс"}])
        from ksys.core.ids import canonical_json
        self.assertIn(canonical_json(alternate), candidates)
        self.assertEqual({a.id, b.id},
                         set(candidates[canonical_json(alternate)]["journal_ids"]))

    def test_malformed_alias_data_is_ignored_without_crashing(self):
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("alias", {"alias": ["not-a-symbol"], "canonical": "алекс"})
        journal.append("alias", {"alias": "борис", "canonical": {"not": "a-symbol"}})
        journal.append("alias", {"alias": "борис", "canonical": "борис"})
        self.assertEqual([], journal.verify())
        self.assertEqual({}, verified_owner_aliases(
            journal.entries(), "human:me").by_symbol)

    def test_alias_matching_does_not_override_exact_context_and_time(self):
        self._import()
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        journal.append("alias", {"alias": "борис", "canonical": "алекс"})
        db = self.workspace / "var" / "ks.sqlite"
        with sqlite3.connect(db) as con:
            row = con.execute("SELECT atom_key FROM candidate_atoms WHERE doc='d.md'").fetchone()
            key = json.loads(row[0])
            key["ctx"] = "только-в-организации-а"
            key["iv"] = {"from": "2024-01-01", "to": "2024-12-31"}
            con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc='d.md'",
                        (json.dumps(key, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")),))
        # Names are aliased but neither context nor date is a wildcard.
        self.assertEqual([], self._relations("d.md")["matches"])

    def test_excess_owner_alias_operations_fail_closed_with_limit_notice(self):
        self._import()
        journal = Journal(Workspace(self.workspace).journal_path, FixedClock())
        for n in range(129):
            journal.append("alias", {"alias": f"имя{n}", "canonical": "алекс"})
        snapshot = verified_owner_aliases(journal.entries(), "human:me")
        self.assertTrue(snapshot.limited)
        self.assertEqual({}, snapshot.by_symbol)
        result = self._relations("d.md")
        self.assertTrue(result["alias_search_limited"])
        self.assertEqual([], result["matches"])

    def test_incremental_reuse_and_change_replaces_obsolete_relations(self):
        self._import()
        original = self._atoms()
        self._import()
        self.assertEqual(original, self._atoms())
        (self.vault / "c.md").write_text("# C\nБорис не является разработчиком.\n",
                                         encoding="utf-8")
        self._import()
        self.assertNotIn("c.md", {m["path"] for m in self._relations()["matches"]})

    def test_cancel_does_not_publish_partial_relation_graph(self):
        self._import()
        baseline = self._atoms()
        (self.vault / "a.md").write_text("# A\nБорис является разработчиком.\n",
                                         encoding="utf-8")

        class Cancel:
            def onProgress(self, processed):
                return processed < 2

        with self.assertRaises(InterruptedError):
            self._import(Cancel())
        self.assertEqual(baseline, self._atoms())

    def test_only_unambiguous_asserted_claim_atoms_are_projected(self):
        example = {"id": "candidate-1", "nodes": [
            {"type": "claim", "assertion": {
                "mod": "asserted", "neg": False,
                "atom": {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}}},
            {"type": "claim", "assertion": {
                "mod": "possible", "neg": True,
                "atom": {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}}},
            {"type": "rule", "assertion": {
                "mod": "asserted", "neg": True,
                "atom": {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}}},
            {"type": "claim", "assertion": {
                "mod": "asserted", "neg": True,
                "atom": {"p": "is_a", "args": [{"k": "var", "v": "x"}]}}},
        ]}
        self.assertEqual(1, len(projected_atoms(example, "a.md", "F:abc", "a:1")))

    def test_unapproved_modal_and_hypothetical_nodes_never_become_claim_votes(self):
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"},
                                       {"k": "sym", "v": "разработчик"}]}
        asserted = {"type": "claim", "assertion": {
            "mod": "asserted", "neg": False, "atom": atom}}
        possible = {"type": "hypothesis", "assertion": {
            "mod": "possible", "neg": True, "atom": atom}}
        usual = {"type": "claim", "assertion": {
            "mod": "usually", "neg": False, "atom": atom}}
        assumption = {"type": "hypothesis", "assertion": {
            "mod": "asserted", "neg": False, "atom": atom}}
        example = {"id": "synthetic", "nodes": [asserted, possible, usual, assumption]}
        strict = projected_atoms(example, "a.md", "F:test", "a:1")
        modals = projected_modal_atoms(example, "a.md", "F:test", "a:1")
        self.assertEqual(1, len(strict))
        self.assertEqual(3, len(modals))
        self.assertEqual({("possible", "hypothesis"), ("usually", "claim"),
                          ("asserted", "hypothesis")}, {(x[7], x[8]) for x in modals})
        self.assertEqual({strict[0][4]}, {x[4] for x in modals})
        invalid = {"id": "bad", "nodes": [
            {"type": "hypothesis", "assertion": dict(possible["assertion"],
                 iv={"from": "2026-02-30", "to": None})},
            {"type": "claim", "assertion": dict(usual["assertion"], neg="false")},
            {"type": "hypothesis", "assertion": dict(possible["assertion"],
                 atom={"p": "is_a", "args": [{"k": "var", "v": "x"}]})},
        ]}
        self.assertEqual([], projected_modal_atoms(invalid, "a.md", "F:test", "a:1"))

    def test_modal_contrasts_are_exact_scoped_read_only_non_conflict_hints(self):
        self.notes["b.md"] = "# B\nВозможно, Алекс является разработчиком.\n"
        self.notes["c.md"] = "# C\nОбычно Алекс является разработчиком.\n"
        for path in ("b.md", "c.md"):
            (self.vault / path).write_text(self.notes[path], encoding="utf-8")
        original = {path: hashlib.sha256((self.vault / path).read_bytes()).hexdigest()
                    for path in self.notes}
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            rows = con.execute("SELECT doc, modality, node_kind FROM candidate_modal_atoms "
                               "WHERE doc IN ('b.md','c.md') ORDER BY doc").fetchall()
            self.assertTrue(any(path == "b.md" and mode == "possible"
                                for path, mode, _ in rows), rows)
            self.assertTrue(any(path == "c.md" and mode == "usually"
                                for path, mode, _ in rows), rows)
            self.assertEqual(0, _source_conflict_count(con))
        result = self._relations("a.md")
        self.assertFalse(result["modal_search_limited"])
        contrasts = [row for row in result["matches"] if row["kind"] == "modal_contrast"]
        self.assertTrue({"b.md", "c.md"}.issubset({r["path"] for r in contrasts}), contrasts)
        self.assertTrue(all(r["source_modality"] == "asserted"
                            and r["other_modality"] in ("possible", "usually")
                            and r["authority"] == "unapproved_parser_hint"
                            for r in contrasts if r["path"] in ("b.md", "c.md")))
        other_modal = [row for row in self._relations("b.md")["matches"]
                       if row["kind"] == "modal_contrast" and row["path"] == "c.md"]
        self.assertTrue(any(row["source_modality"] == "possible"
                            and row["other_modality"] == "usually" for row in other_modal))
        evidence = result["evidence_groups"][0]
        self.assertEqual("not_assessed", evidence["confidence"])
        self.assertFalse({"b.md", "c.md"} &
                         {source["path"] for source in evidence["other_sources"]})
        editor = json.loads(editor_note_context(str(self.workspace), "a.md",
                             self.notes["a.md"], str(self.vault)))
        self.assertFalse(editor["modal_search_limited"])
        self.assertTrue(any(x["kind"] == "modal_contrast"
                            for x in editor["candidate_relations"]))
        with sqlite3.connect(db) as con:
            con.execute("UPDATE candidate_modal_atoms SET negated=1 WHERE doc='b.md'")
            self.assertEqual(0, _source_conflict_count(con))
        polarity = [row for row in self._relations("a.md")["matches"]
                    if row["kind"] == "modal_contrast" and row["path"] == "b.md"]
        self.assertTrue(polarity and all(row["opposing_polarity"] for row in polarity))
        self.assertFalse(any(row["kind"] == "possible_conflict" and row["path"] == "b.md"
                             for row in self._relations("a.md")["matches"]))
        self.assertEqual(original, {path: hashlib.sha256((self.vault / path).read_bytes()).hexdigest()
                                    for path in self.notes})

    def test_modal_contrasts_require_both_original_sha_checks_for_live_status(self):
        self.notes["b.md"] = "# B\nВозможно, Алекс является разработчиком.\n"
        self.notes["c.md"] = "# C\nОбычно Алекс является разработчиком.\n"
        for path in ("b.md", "c.md"):
            (self.vault / path).write_text(self.notes[path], encoding="utf-8")
        self._import()

        historical = self._relations("a.md")
        hints = [m for m in historical["matches"] if m["kind"] == "modal_contrast"]
        self.assertTrue(historical["modal_checks_limited"])
        self.assertTrue(all(m["source_verification"] == "not_checked"
                            and m["other_verification"] == "not_checked"
                            and not m["live_source_pair"] for m in hints))

        current = self._relations("a.md", verified=True)
        verified = [m for m in current["matches"] if m["kind"] == "modal_contrast"]
        self.assertFalse(current["modal_checks_limited"])
        self.assertTrue({"b.md", "c.md"}.issubset({m["path"] for m in verified}))
        self.assertTrue(all(m["source_verification"] == "current"
                            and m["other_verification"] == "current"
                            and m["live_source_pair"] for m in verified))
        editor = json.loads(editor_note_context(str(self.workspace), "a.md",
                            self.notes["a.md"], str(self.vault)))
        self.assertFalse(editor["modal_checks_limited"])
        self.assertTrue(all(m["live_source_pair"] for m in editor["candidate_relations"]
                            if m["kind"] == "modal_contrast"))

        # b.md contains only a modal claim: its own evidence groups are empty,
        # but its pair against the other modal note must still be SHA-checked.
        modal_only = self._relations("b.md", verified=True)
        self.assertEqual([], modal_only["evidence_groups"])
        self.assertFalse(modal_only["modal_checks_limited"])
        self.assertTrue(any(m["live_source_pair"] for m in modal_only["matches"]
                            if m["kind"] == "modal_contrast" and m["path"] == "c.md"))

        (self.vault / "b.md").write_text(self.notes["b.md"] + "\n", encoding="utf-8")
        (self.vault / "c.md").unlink()
        changed = self._relations("a.md", verified=True)
        statuses = {m["path"]: m["other_verification"] for m in changed["matches"]
                    if m["kind"] == "modal_contrast"}
        self.assertEqual("stale", statuses["b.md"])
        self.assertEqual("unavailable", statuses["c.md"])
        self.assertFalse(changed["modal_checks_limited"])  # checked, but not current
        self.assertTrue(all(not m["live_source_pair"] for m in changed["matches"]
                            if m["kind"] == "modal_contrast"))

        (self.vault / "a.md").write_text(self.notes["a.md"] + "\n", encoding="utf-8")
        stale_source = self._relations("a.md", verified=True)
        self.assertTrue(all(m["source_verification"] == "stale"
                            and not m["live_source_pair"] for m in stale_source["matches"]
                            if m["kind"] == "modal_contrast"))

        with tempfile.TemporaryDirectory() as wrong_root:
            wrong = json.loads(note_relationships(str(self.workspace), "a.md", wrong_root))
        self.assertTrue(wrong["modal_checks_limited"])
        self.assertTrue(all(m["source_verification"] == "not_checked"
                            and m["other_verification"] == "not_checked"
                            for m in wrong["matches"] if m["kind"] == "modal_contrast"))

    def test_modal_and_exact_evidence_share_one_sixteen_file_sha_budget(self):
        self.notes["b.md"] = "# B\nВозможно, Алекс является разработчиком.\n"
        (self.vault / "b.md").write_text(self.notes["b.md"], encoding="utf-8")
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            row = con.execute("SELECT * FROM candidate_atoms WHERE doc='sub/e.markdown'").fetchone()
            for i in range(25):
                path = f"00-extra-{i:02}.md"
                con.execute("INSERT INTO documents(path,sha256,size,status,kind) "
                            "VALUES(?,? ,12,'PARSED','markdown')", (path, "0" * 64))
                con.execute("INSERT INTO candidate_atoms VALUES(?,?,?,?,?,?,?,?,?,?)",
                            (f"extra-{i}", row[1], path, *row[3:]))
        checked_paths = []
        original_hash = bridge._entity_live_hash

        def counted_hash(root, path):
            checked_paths.append(path)
            return original_hash(root, path)

        with patch.object(bridge, "_entity_live_hash", side_effect=counted_hash):
            result = self._relations("a.md", verified=True)
            self.assertTrue(result["evidence_checks_limited"])
        self.assertTrue(result["modal_checks_limited"])
        self.assertEqual(bridge.MAX_EVIDENCE_LIVE_CHECKS, len(checked_paths))
        self.assertEqual(len(checked_paths), len(set(checked_paths)))
        hints = [m for m in result["matches"] if m["kind"] == "modal_contrast"]
        self.assertTrue(hints)
        self.assertTrue(all(m["source_verification"] == "current"
                            and m["other_verification"] == "not_checked"
                            and not m["live_source_pair"] for m in hints))

    def test_modal_index_uses_exact_qualifiers_and_clears_after_note_reanalysis(self):
        self.notes["b.md"] = "# B\nВозможно, Алекс является разработчиком.\n"
        (self.vault / "b.md").write_text(self.notes["b.md"], encoding="utf-8")
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            self.assertTrue(con.execute("SELECT 1 FROM candidate_modal_atoms "
                                        "WHERE doc='b.md' LIMIT 1").fetchone())
            con.execute("UPDATE candidate_modal_atoms SET atom_key=? WHERE doc='b.md'",
                        ('{"ctx":"other-context","iv":{"from":null,"to":null},'
                         '"p":"is_a","args":[]}',))
        self.assertNotIn("b.md", {row["path"] for row in self._relations("a.md")["matches"]
                                  if row["kind"] == "modal_contrast"})
        (self.vault / "b.md").write_text(self.notes["b.md"] + "\n", encoding="utf-8")
        self._import()
        self.assertIn("b.md", {row["path"] for row in self._relations("a.md")["matches"]
                                 if row["kind"] == "modal_contrast"})
        (self.vault / "b.md").write_text("# B\nАлекс является разработчиком.\n",
                                          encoding="utf-8")
        self._import()
        with sqlite3.connect(db) as con:
            self.assertFalse(con.execute("SELECT 1 FROM candidate_modal_atoms "
                                         "WHERE doc='b.md' LIMIT 1").fetchone())
        self.assertFalse(any(row["kind"] == "modal_contrast" and row["path"] == "b.md"
                             for row in self._relations("a.md")["matches"]))

    def test_missing_modal_table_reports_incompleteness_without_crash(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            con.execute("DROP TABLE candidate_modal_atoms")
            result = indexed_related_claims(con, "a.md")
        self.assertTrue(result["modal_search_limited"])
        self.assertTrue(result["matches"])

    def test_context_and_time_must_match_before_candidate_claims_are_related(self):
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}

        def signature(*, context=None, start=None, end=None, neg=False):
            assertion = {"mod": "asserted", "neg": neg, "atom": atom}
            if context is not None:
                assertion["ctx"] = context
            if start is not None or end is not None:
                assertion["iv"] = {"from": start, "to": end}
            return projected_atoms({"id": "synthetic", "nodes": [
                {"type": "claim", "assertion": assertion},
            ]}, "test.md", "F:test", "test:1")[0][4]

        default = signature()
        context_a = signature(context="организация-а")
        context_b = signature(context="организация-б")
        period_a = signature(start="2024-01-01", end="2024-12-31")
        period_b = signature(start="2025-01-01", end="2025-12-31")
        self.assertEqual(default, signature(neg=True))
        self.assertEqual(context_a, signature(context="организация-а", neg=True))
        self.assertEqual(period_a, signature(start="2024-01-01", end="2024-12-31", neg=True))
        self.assertEqual(5, len({default, context_a, context_b, period_a, period_b}))

        self._import()
        db = self.workspace / "var" / "ks.sqlite"
        with sqlite3.connect(db) as con:
            self.assertEqual(1, con.execute(
                "SELECT COUNT(*) FROM candidate_atoms WHERE doc='a.md' "
                "AND atom_key LIKE '%\"ctx\":null%'"
            ).fetchone()[0])
            # Synthetic derived-index edits only: the original vault and journal
            # remain untouched; verify both consumers use the same safe key.
            con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc='a.md'", (period_a,))
            con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc='b.md'", (period_a,))
            con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc='c.md'", (period_b,))
            self.assertEqual(0, _source_conflict_count(con))
        timed = {m["path"]: m for m in self._relations()["matches"]}
        self.assertEqual({"b.md"}, set(timed))
        self.assertEqual({"from": "2024-01-01", "to": "2024-12-31"},
                         timed["b.md"]["interval"])

        with sqlite3.connect(db) as con:
            con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc='c.md'", (period_a,))
            self.assertEqual(1, _source_conflict_count(con))
            conflict = _source_conflict_page(con, 0)[0]
            self.assertEqual({"from": "2024-01-01", "to": "2024-12-31"},
                             conflict["interval"])
        self.assertEqual("possible_conflict", {
            m["path"]: m for m in self._relations()["matches"]}["c.md"]["kind"])

        with sqlite3.connect(db) as con:
            con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc='a.md'", (context_a,))
            con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc='b.md'", (context_a,))
            con.execute("UPDATE candidate_atoms SET atom_key=? WHERE doc='c.md'", (context_b,))
            self.assertEqual(0, _source_conflict_count(con))
        scoped = {m["path"]: m for m in self._relations()["matches"]}
        self.assertEqual({"b.md"}, set(scoped))
        self.assertEqual("организация-а", scoped["b.md"]["context"])

    def test_malformed_qualifiers_never_fall_back_to_unqualified_matches(self):
        assertion = {"mod": "asserted", "neg": False,
                     "atom": {"p": "owns", "args": [{"k": "sym", "v": "алекс"}]}}

        def atoms(qualifier):
            return projected_atoms({"id": "synthetic", "nodes": [
                {"type": "claim", "assertion": dict(assertion, **qualifier)}
            ]}, "test.md", "F:test", "test:1")

        for qualifier in ({"ctx": 7}, {"ctx": ""}, {"iv": None},
                          {"iv": {"from": "2024-02-30", "to": None}},
                          {"iv": {"from": "2025-01-01", "to": "2024-01-01"}},
                          {"iv": {"from": None, "to": None}},
                          {"iv": {"from": "2024-01-01"}},
                          {"iv": {"from": 7, "to": None}}):
            with self.subTest(qualifier=qualifier):
                self.assertEqual([], atoms(qualifier))
        self.assertEqual(1, len(atoms({"iv": {"from": "2024-01-01", "to": None}})))

    def test_one_reading_keeps_distinct_time_scopes_without_duplicate_atoms(self):
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}
        one = {"type": "claim", "assertion": {"mod": "asserted", "neg": False,
                "atom": atom, "iv": {"from": "2023-01-01", "to": None}}}
        two = {"type": "claim", "assertion": {"mod": "asserted", "neg": False,
                "atom": atom, "iv": {"from": "2024-01-01", "to": None}}}
        rows = projected_atoms({"id": "synthetic", "nodes": [one, one, two]},
                               "a.md", "F:test", "a:1")
        self.assertEqual(2, len(rows))
        self.assertNotEqual(rows[0][4], rows[1][4])

    def test_temporal_overlap_is_not_a_conflict_or_agreement(self):
        original = {name: hashlib.sha256((self.vault / name).read_bytes()).hexdigest()
                    for name in self.notes}
        self._import()
        db = self.workspace / "var" / "ks.sqlite"
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}

        def assign(con, path, start, end, *, context="проект-а"):
            assertion = {"mod": "asserted", "neg": path == "c.md", "atom": atom,
                         "ctx": context, "iv": {"from": start, "to": end}}
            row = projected_atoms({"id": "test", "nodes": [
                {"type": "claim", "assertion": assertion}
            ]}, path, "F:test", "test:1")[0]
            con.execute("UPDATE candidate_atoms SET atom_key=?, scope_key=?, "
                        "valid_from=?, valid_to=? WHERE doc=?",
                        (row[4], row[7], row[8], row[9], path))

        with sqlite3.connect(db) as con:
            assign(con, "a.md", "2024-01-01", "2024-12-31")
            assign(con, "b.md", "2024-05-01", "2025-02-01")
            assign(con, "c.md", "2025-02-02", "2025-12-31")
            self.assertEqual(0, _source_conflict_count(con))
        relations = self._relations()["matches"]
        self.assertEqual({"b.md", "c.md"}, {x["path"] for x in relations})
        overlap = next(x for x in relations if x["path"] == "b.md")
        self.assertEqual("temporal_overlap", overlap["kind"])
        self.assertEqual("temporal_separation", next(x for x in relations
                         if x["path"] == "c.md")["kind"])
        self.assertFalse(overlap["opposing_polarity"])
        self.assertEqual({"from": "2024-01-01", "to": "2024-12-31"},
                         overlap["interval"])
        self.assertEqual({"from": "2024-05-01", "to": "2025-02-01"},
                         overlap["other_interval"])
        self.assertEqual({"from": "2024-05-01", "to": "2024-12-31"},
                         overlap["overlap_interval"])

        with sqlite3.connect(db) as con:
            # A different polarity still only merits a temporal hint. It must
            # never enter the exact-key contradiction audit by date overlap.
            con.execute("UPDATE candidate_atoms SET negated=1 WHERE doc='b.md'")
            self.assertEqual(0, _source_conflict_count(con))
            assign(con, "b.md", "2024-12-31", "2024-12-31")
        boundary = self._relations()["matches"]
        self.assertEqual({"b.md", "c.md"}, {x["path"] for x in boundary})
        self.assertTrue(next(x for x in boundary if x["path"] == "b.md")["opposing_polarity"])
        self.assertEqual({"from": "2024-12-31", "to": "2024-12-31"},
                         next(x for x in boundary if x["path"] == "b.md")["overlap_interval"])
        with sqlite3.connect(db) as con:
            assign(con, "b.md", "2024-12-31", "2025-02-01", context="проект-б")
        final = self._relations()["matches"]
        self.assertEqual(["c.md"], [x["path"] for x in final])
        self.assertEqual("temporal_separation", final[0]["kind"])
        self.assertEqual(original, {
            name: hashlib.sha256((self.vault / name).read_bytes()).hexdigest()
            for name in self.notes})
        self.assertFalse((self.workspace / "data").exists())

    def test_separated_explicit_time_ranges_show_order_and_gap_not_version_truth(self):
        source_hashes = {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                         for p in self.notes}
        self._import()
        db = self.workspace / "var/ks.sqlite"
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}

        def assign(con, path, start, end, context="проект-а", negated=False):
            assertion = {"mod": "asserted", "neg": negated, "atom": atom,
                         "ctx": context, "iv": {"from": start, "to": end}}
            row = projected_atoms({"id": "test", "nodes": [
                {"type": "claim", "assertion": assertion},
            ]}, path, "F:test", "test:1")[0]
            con.execute("UPDATE candidate_atoms SET atom_key=?, scope_key=?, "
                        "valid_from=?, valid_to=?, negated=? WHERE doc=?",
                        (row[4], row[7], row[8], row[9], row[6], path))

        with sqlite3.connect(db) as con:
            assign(con, "a.md", "2025-02-01", "2025-02-28")
            assign(con, "b.md", "2024-01-01", "2024-12-31")
            assign(con, "c.md", "2025-03-01", "2025-03-31", negated=True)
            self.assertEqual(0, _source_conflict_count(con))
        before = hashlib.sha256(db.read_bytes()).hexdigest()
        page = self._relations("a.md")
        self.assertEqual({"b.md", "c.md"}, {m["path"] for m in page["matches"]})
        self.assertTrue(all(m["kind"] == "temporal_separation" for m in page["matches"]))
        by_path = {m["path"]: m for m in page["matches"]}
        self.assertEqual(("other_before_source", 31),
                         (by_path["b.md"]["relative_order"], by_path["b.md"]["gap_days"]))
        self.assertEqual(("source_before_other", 0),
                         (by_path["c.md"]["relative_order"], by_path["c.md"]["gap_days"]))
        self.assertTrue(by_path["c.md"]["opposing_polarity"])
        self.assertTrue(all(m["authority"] == "unapproved_parser_hint"
                            for m in page["matches"]))
        reverse = next(m for m in self._relations("b.md")["matches"] if m["path"] == "a.md")
        self.assertEqual("source_before_other", reverse["relative_order"])
        self.assertEqual(31, reverse["gap_days"])
        self.assertEqual("2025-02-01", reverse["other_interval"]["from"])
        self.assertEqual(before, hashlib.sha256(db.read_bytes()).hexdigest())

        # A distinct context and an open endpoint never qualify as two
        # complete, date-separated historical versions.
        with sqlite3.connect(db) as con:
            assign(con, "b.md", "2024-01-01", "2024-12-31", context="проект-б")
            assign(con, "c.md", "2025-03-01", None, negated=True)
        self.assertFalse(any(m["kind"] == "temporal_separation"
                             for m in self._relations("a.md")["matches"]))

        # The inclusive shared endpoint belongs to overlap, not separation.
        with sqlite3.connect(db) as con:
            assign(con, "c.md", "2025-02-28", "2025-03-31", negated=True)
        touching = [m for m in self._relations("a.md")["matches"] if m["path"] == "c.md"]
        self.assertEqual(["temporal_overlap"], [m["kind"] for m in touching])
        self.assertEqual({"from": "2025-02-28", "to": "2025-02-28"},
                         touching[0]["overlap_interval"])

        with sqlite3.connect(db) as con:
            assign(con, "b.md", "2024-01-01", "2024-12-31")
            con.execute("UPDATE candidate_atoms SET valid_to='2024-12-30' WHERE doc='b.md'")
        self.assertFalse(any(m["kind"] == "temporal_separation" and m["path"] == "b.md"
                             for m in self._relations("a.md")["matches"]))
        self.assertEqual(source_hashes, {p: hashlib.sha256((self.vault / p).read_bytes()).hexdigest()
                                         for p in self.notes})
        self.assertFalse((self.workspace / "data").exists())

    def test_both_temporal_hint_kinds_verify_original_shas_without_authority(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}
        with sqlite3.connect(db) as con:
            for path, start, end in (("a.md", "2024-01-01", "2024-12-31"),
                                     ("b.md", "2024-06-01", "2025-02-01"),
                                     ("c.md", "2025-02-02", "2025-12-31")):
                assertion = {"mod": "asserted", "neg": path == "c.md", "atom": atom,
                             "ctx": "проект-а", "iv": {"from": start, "to": end}}
                row = projected_atoms({"id": "test", "nodes": [
                    {"type": "claim", "assertion": assertion}
                ]}, path, "F:test", "test:1")[0]
                con.execute("UPDATE candidate_atoms SET atom_key=?, scope_key=?, "
                            "valid_from=?, valid_to=?, negated=? WHERE doc=?",
                            (row[4], row[7], row[8], row[9], row[6], path))
            self.assertEqual(0, _source_conflict_count(con))
        before = hashlib.sha256(db.read_bytes()).hexdigest()

        unchecked = self._relations("a.md")
        self.assertTrue(unchecked["temporal_checks_limited"])
        self.assertEqual({"temporal_overlap", "temporal_separation"},
                         {m["kind"] for m in unchecked["matches"]})
        self.assertTrue(all(m["source_verification"] == "not_checked"
                            and m["other_verification"] == "not_checked"
                            and not m["live_source_pair"] for m in unchecked["matches"]))

        current = self._relations("a.md", verified=True)
        self.assertFalse(current["temporal_checks_limited"])
        self.assertEqual(0, current["evidence_groups"][0]["indexed_other_files"])
        self.assertTrue(all(m["source_verification"] == "current"
                            and m["other_verification"] == "current"
                            and m["live_source_pair"] for m in current["matches"]))
        self.assertEqual(before, hashlib.sha256(db.read_bytes()).hexdigest())

        editor = json.loads(editor_note_context(str(self.workspace), "a.md",
                            self.notes["a.md"], str(self.vault)))
        self.assertFalse(editor["temporal_checks_limited"])
        self.assertTrue(all(m["live_source_pair"] for m in editor["candidate_relations"]
                            if m["kind"] in ("temporal_overlap", "temporal_separation")))

        (self.vault / "b.md").write_text(self.notes["b.md"] + "\n", encoding="utf-8")
        (self.vault / "c.md").unlink()
        changed = self._relations("a.md", verified=True)
        statuses = {m["path"]: m["other_verification"] for m in changed["matches"]}
        self.assertEqual({"b.md": "stale", "c.md": "unavailable"}, statuses)
        self.assertFalse(changed["temporal_checks_limited"])  # checked but not current
        self.assertTrue(all(not m["live_source_pair"] for m in changed["matches"]))

        (self.vault / "a.md").write_text(self.notes["a.md"] + "\n", encoding="utf-8")
        stale_source = self._relations("a.md", verified=True)
        self.assertTrue(all(m["source_verification"] == "stale"
                            and not m["live_source_pair"] for m in stale_source["matches"]))
        with tempfile.TemporaryDirectory() as wrong_root:
            wrong = json.loads(note_relationships(str(self.workspace), "a.md", wrong_root))
        self.assertTrue(wrong["temporal_checks_limited"])
        self.assertTrue(all(m["source_verification"] == "not_checked"
                            and m["other_verification"] == "not_checked"
                            for m in wrong["matches"]))

    def test_time_and_modal_hints_share_existing_evidence_sha_budget(self):
        self._import()
        db = self.workspace / "var/ks.sqlite"
        with sqlite3.connect(db) as con:
            original = self._relations("a.md")["evidence_groups"]
            self.assertEqual(1, len(original))
            matches = [{"kind": "modal_contrast", "path": "b.md"}]
            matches.extend({"kind": "temporal_separation", "path": f"missing-{i:02}.md"}
                           for i in range(16))
            checked_paths = []
            hash_file = bridge._entity_live_hash

            def counted_hash(root, path):
                checked_paths.append(path)
                return hash_file(root, path)

            with patch.object(bridge, "_entity_live_hash", side_effect=counted_hash):
                evidence_limited, modal_limited, temporal_limited, relation_limited = (
                    bridge._verify_evidence_freshness(
                        con, str(self.vault), "a.md", original, matches))
            self.assertFalse(evidence_limited)
            self.assertFalse(modal_limited)
            self.assertTrue(temporal_limited)
            self.assertFalse(relation_limited)
            self.assertEqual(bridge.MAX_EVIDENCE_LIVE_CHECKS, len(checked_paths))
            self.assertEqual(len(checked_paths), len(set(checked_paths)))
            self.assertEqual("current", matches[0]["other_verification"])
            self.assertTrue(matches[0]["live_source_pair"])
            self.assertTrue(any(m["other_verification"] == "not_checked"
                                and not m["live_source_pair"] for m in matches[1:]))

    def test_open_ended_intervals_require_actual_bounds_on_both_sides(self):
        self._import()
        db = self.workspace / "var" / "ks.sqlite"
        atom = {"p": "is_a", "args": [{"k": "sym", "v": "алекс"}]}

        def rows(start, end):
            return projected_atoms({"id": "synthetic", "nodes": [{
                "type": "claim", "assertion": {
                    "mod": "asserted", "neg": False, "atom": atom,
                    "iv": {"from": start, "to": end},
                },
            }]}, "x.md", "F:x", "x:1")[0]

        start_only = rows("2024-06-01", None)
        end_only = rows(None, "2024-06-01")
        with sqlite3.connect(db) as con:
            for path, row in (("a.md", start_only), ("b.md", end_only)):
                con.execute("UPDATE candidate_atoms SET atom_key=?, scope_key=?, "
                            "valid_from=?, valid_to=? WHERE doc=?",
                            (row[4], row[7], row[8], row[9], path))
        result = self._relations()["matches"]
        self.assertEqual(["b.md"], [r["path"] for r in result])
        self.assertEqual({"from": "2024-06-01", "to": "2024-06-01"},
                         result[0]["overlap_interval"])
        # The unspecified period in c.md and sub/e.markdown must NOT behave
        # like an all-time interval and create spurious overlap suggestions.
        self.assertNotIn("c.md", {r["path"] for r in result})
        self.assertNotIn("sub/e.markdown", {r["path"] for r in result})

    def test_projection_version_change_rebuilds_unchanged_derived_notes(self):
        self._import()
        db = self.workspace / "var" / "ks.sqlite"
        with sqlite3.connect(db) as con:
            con.execute("UPDATE documents SET versions='legacy-relations-v1' WHERE path='a.md'")
            con.execute("UPDATE candidate_atoms SET atom_key='legacy-key' WHERE doc='a.md'")
        self._import()
        with sqlite3.connect(db) as con:
            version = con.execute("SELECT versions FROM documents WHERE path='a.md'").fetchone()[0]
            restored = con.execute("SELECT atom_key FROM candidate_atoms WHERE doc='a.md'").fetchone()[0]
        self.assertEqual(RELATIONS_VERSION, json.loads(version)["relations"])
        self.assertNotEqual("legacy-key", restored)
        self.assertEqual(None, json.loads(restored)["ctx"])

    def test_relations_missing_index_and_page_limit(self):
        ws = Workspace(self.workspace)
        self.assertEqual([], related_claims(ws, "a.md")["matches"])
        for invalid_limit in (0, 101):
            with self.assertRaises(ValueError):
                related_claims(ws, "a.md", invalid_limit)
        self._import()
        result = related_claims(ws, "a.md", limit=1)
        self.assertEqual(1, len(result["matches"]))
        self.assertTrue(result["truncated"])

    def test_disposable_schema_upgrade_preserves_private_journal(self):
        old_index = self.workspace / "var" / "ks.sqlite"
        old_index.parent.mkdir(parents=True)
        with sqlite3.connect(old_index) as con:
            con.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
            con.execute("INSERT INTO meta VALUES('db_schema', '3')")
        journal = self.workspace / "data" / "formal" / "journal.jsonl"
        journal.parent.mkdir(parents=True)
        fixture = '{"synthetic":"test-only"}\n'
        journal.write_text(fixture, encoding="utf-8")
        self._import()
        self.assertEqual(fixture, journal.read_text(encoding="utf-8"))
        with sqlite3.connect(old_index) as con:
            self.assertEqual("6", con.execute(
                "SELECT value FROM meta WHERE key='db_schema'").fetchone()[0])
        self.assertTrue(self._atoms())


if __name__ == "__main__":
    unittest.main()
