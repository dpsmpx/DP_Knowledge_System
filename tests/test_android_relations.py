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
from ksys.importer.relations import RELATIONS_VERSION, projected_atoms, related_claims
from ksys.importer.aliases import verified_owner_aliases, one_symbol_variants
from ksys.core.clock import FixedClock
from ksys.store.journal import Journal
from ksys.store.workspace import Workspace


class RelationTests(unittest.TestCase):
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

    def _relations(self, path="a.md"):
        return json.loads(note_relationships(str(self.workspace), path))

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
