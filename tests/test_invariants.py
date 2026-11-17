"""Инварианты раздела XL — по одному явному тесту на каждый."""
import random
import sqlite3
import unittest

from ksys.core.clock import FixedClock
from ksys.core.enums import FragmentKind, Formalization
from ksys.core.errors import KSError
from ksys.demo.scenarios import DEMO_VAULT
from ksys.importer.vault import import_vault
from ksys.ir.ksf import parse_one
from ksys.query.answers import Answerer
from ksys.importer.language_analyzer import LanguageAnalyzer
from ksys.semantics.normalize import ksf_symbol
from ksys.source.coverage import check_coverage
from ksys.source.markdown import parse_document
from tests.helpers import TempKB


def lit(src):
    return parse_one(src, ksf_symbol).assertion.literal


class Invariants(unittest.TestCase):
    def setUp(self):
        self.t = TempKB()
        self.kb = self.t.kb

    def tearDown(self):
        self.t.close()

    def _random_kb(self, seed=1):
        rng = random.Random(seed)
        self.kb.accept_text("#transitive part_of.")
        self.kb.accept_text("q(X) :- part_of(X, Y), r(Y).")
        ids = []
        for i in range(25):
            a, b = rng.randrange(8), rng.randrange(8)
            res = self.kb.accept_text(f"part_of(n{a}, n{b}).")
            ids.append(res.entries[0].data["node"]["id"])
            if rng.random() < 0.3:
                self.kb.accept_text(f"r(n{rng.randrange(8)}).")
            if ids and rng.random() < 0.25:
                victim = ids.pop(rng.randrange(len(ids)))
                if self.kb.state.active(victim):
                    self.kb.retract(victim)

    def test_1_no_meaningful_fragment_is_lost(self):
        for path in sorted(DEMO_VAULT.rglob("*.md")):
            text = path.read_text(encoding="utf-8")
            r = check_coverage(text, parse_document(path.name, text).fragments)
            self.assertTrue(r.ok and r.chars_covered == r.chars_total, path)

    def test_2_every_fragment_has_processing_status(self):
        import_vault(self.t.ws, DEMO_VAULT, FixedClock(), LanguageAnalyzer())
        con = sqlite3.connect(self.t.ws.db_path)
        rows = con.execute("SELECT kind, formalization FROM fragments").fetchall()
        self.assertTrue(rows)
        for kind, form in rows:
            FragmentKind(kind)
            Formalization(form)

    def test_3_no_derived_fact_without_derivation(self):
        self._random_kb()
        eng = self.kb.auth
        for aid in eng.items:
            if not eng.is_base(aid):
                justs = eng.justifications(aid)
                self.assertTrue(justs, aid)
                self.assertTrue(any(all(p in eng.items for p in j.premises) for j in justs), aid)

    def test_4_no_authoritative_fact_without_provenance(self):
        self._random_kb()
        for rec in self.kb.state.authoritative():
            self.assertTrue(rec.provenance)
            self.assertTrue(all(p.get("journal_entry", "").startswith("J:") for p in rec.provenance))

    def test_5_unknown_is_not_false(self):
        self._random_kb()
        ans = Answerer(self.kb)
        for q in ("part_of(zz1, zz2)", "r(nothing)", "q(n99)"):
            self.assertEqual(ans.ask_literals("?", [lit(q)]).status, "UNKNOWN")

    def test_6_contradiction_does_not_trivialize(self):
        self.kb.accept_text("p(a).")
        before = len(self.kb.auth.items)
        self.kb.accept_text("not p(a).")
        self.assertEqual(len(self.kb.auth.items), before + 1)
        ans = Answerer(self.kb)
        self.assertEqual(ans.ask_literals("?", [lit("p(a)")]).status, "BOTH")
        self.assertEqual(ans.ask_literals("?", [lit("p(b)")]).status, "UNKNOWN")
        self.assertEqual(ans.ask_literals("?", [lit("anything(at, all)")]).status, "UNKNOWN")

    def test_7_reimport_creates_no_duplicates(self):
        m1 = import_vault(self.t.ws, DEMO_VAULT, FixedClock(), LanguageAnalyzer()).manifest
        con = sqlite3.connect(self.t.ws.db_path)
        n1 = con.execute("SELECT COUNT(*), COUNT(DISTINCT id) FROM fragments").fetchone()
        c1 = con.execute("SELECT COUNT(*) FROM candidates").fetchone()[0]
        m2 = import_vault(self.t.ws, DEMO_VAULT, FixedClock(), LanguageAnalyzer(), force=True).manifest
        n2 = con.execute("SELECT COUNT(*), COUNT(DISTINCT id) FROM fragments").fetchone()
        c2 = con.execute("SELECT COUNT(*) FROM candidates").fetchone()[0]
        self.assertEqual((n1, c1, m1["import_id"]), (n2, c2, m2["import_id"]))
        self.assertEqual(n1[0], n1[1])

    def test_8_retraction_propagates_invalidation(self):
        self.kb.accept_text("f2(X) :- f1(X).")
        self.kb.accept_text("f3(X) :- f2(X).")
        fid = self.kb.accept_text("f1(a).").entries[0].data["node"]["id"]
        self.assertEqual(Answerer(self.kb).ask_literals("?", [lit("f3(a)")]).status, "PROVEN")
        res = self.kb.retract(fid)
        self.assertEqual(len(res.auth.removed), 3)
        self.assertEqual(Answerer(self.kb).ask_literals("?", [lit("f3(a)")]).status, "UNKNOWN")
        reopened = self.t.reopen()
        self.assertEqual(reopened.auth.snapshot(), self.kb.auth.snapshot())

    def test_9_formal_representation_has_source_mapping(self):
        import_vault(self.t.ws, DEMO_VAULT, FixedClock(), LanguageAnalyzer())
        con = sqlite3.connect(self.t.ws.db_path)
        rows = con.execute("SELECT c.id, f.locator, f.start, f.end, f.text, f.doc FROM candidates c "
                           "JOIN fragments f ON f.id=c.origin").fetchall()
        self.assertTrue(rows)
        for cid, loc, s, e, text, doc in rows:
            src = (DEMO_VAULT / doc).read_text(encoding="utf-8")
            self.assertEqual(src[s:e], text, loc)
        cid = rows[0][0]
        res = self.kb.accept_candidate(cid, role="fact")
        prov = self.kb.state.records[res.entries[0].data["node"]["id"]].provenance[0]
        self.assertEqual((prov["fragment_id"] is not None, prov["locator"]), (True, rows[0][1]))

    def test_10_rejected_never_becomes_authoritative_silently(self):
        node = parse_one("uses(алекс, java)", ksf_symbol)
        self.kb.reject(targets=[node.id])
        for attempt in (lambda: self.kb.accept([node]), lambda: self.kb.accept_text("uses(алекс, java).")):
            with self.assertRaises(KSError):
                attempt()
        self.assertFalse(self.kb.state.active(node.id))
        replayed = self.t.reopen()
        self.assertFalse(replayed.state.active(node.id))


if __name__ == "__main__":
    unittest.main()
