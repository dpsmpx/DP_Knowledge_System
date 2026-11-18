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
from ksys.importer.relations import projected_atoms, related_claims
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
            self.assertEqual("4", con.execute(
                "SELECT value FROM meta WHERE key='db_schema'").fetchone()[0])
        self.assertTrue(self._atoms())


if __name__ == "__main__":
    unittest.main()
