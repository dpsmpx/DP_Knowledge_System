"""Synthetic cross-document claim relationships, without authoritative writes."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party" / "markor" / "app" / "src" / "main" / "python"))

from ksys_android_bridge import analyze_vault, note_relationships
from ksys.audit.library import _source_conflict_count, _source_conflict_page
from ksys.importer.relations import (RELATIONS_VERSION, projected_atoms, related_claims,
                                     indexed_source_evidence, _evidence_excerpts)
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
        self.assertEqual(["b.md"], [x["path"] for x in relations])
        overlap = relations[0]
        self.assertEqual("temporal_overlap", overlap["kind"])
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
        self.assertEqual(["b.md"], [x["path"] for x in boundary])
        self.assertTrue(boundary[0]["opposing_polarity"])
        self.assertEqual({"from": "2024-12-31", "to": "2024-12-31"},
                         boundary[0]["overlap_interval"])
        with sqlite3.connect(db) as con:
            assign(con, "b.md", "2024-12-31", "2025-02-01", context="проект-б")
        self.assertEqual([], self._relations()["matches"])
        self.assertEqual(original, {
            name: hashlib.sha256((self.vault / name).read_bytes()).hexdigest()
            for name in self.notes})
        self.assertFalse((self.workspace / "data").exists())

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
            self.assertEqual("5", con.execute(
                "SELECT value FROM meta WHERE key='db_schema'").fetchone()[0])
        self.assertTrue(self._atoms())


if __name__ == "__main__":
    unittest.main()
