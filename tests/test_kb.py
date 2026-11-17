import json
import unittest

from ksys.core.errors import ErrorKind, KSError
from ksys.ir.ksf import parse_one
from ksys.query.answers import Answerer
from ksys.query.hypothesis import HypothesisEngine
from ksys.query import smt
from ksys.semantics.normalize import ksf_symbol
from tests.helpers import TempKB


def lit(src):
    return parse_one(src, ksf_symbol).assertion.literal


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.t = TempKB()
        self.kb = self.t.kb

    def tearDown(self):
        self.t.close()

    def test_preview_shows_original_formal_canonical(self):
        pv = self.kb.preview("Стресс приводит к бессоннице.")
        d = pv.to_dict()
        self.assertEqual(d["original"], "Стресс приводит к бессоннице.")
        self.assertEqual(d["options"][0]["ksf"], ["causes(стресс, бессонниц)."])
        self.assertTrue(d["options"][0]["paraphrase"])
        self.assertEqual(self.kb.journal.entries(), [])  # предпросмотр ничего не пишет

    def test_nothing_authoritative_without_acceptance(self):
        self.kb.preview("Python является языком.")
        self.assertEqual(self.kb.state.authoritative(), [])
        self.assertEqual(Answerer(self.kb).ask_literals("?", [lit("is_a(python, язык)")]).status, "UNKNOWN")

    def test_ambiguity_requires_choice_and_is_remembered(self):
        with self.assertRaises(KSError) as cm:
            self.kb.accept_text("Алекс держит магазин.")
        self.assertEqual(cm.exception.kind, ErrorKind.SEMANTIC_AMBIGUITY)
        pv = self.kb.preview("Алекс держит магазин.")
        owns = next(o for o in pv.options if "sense=owns" in o["labels"])
        self.kb.accept_text("Алекс держит магазин.", reading=owns["reading_id"])
        again = self.kb.preview("Алекс держит магазин.")
        self.assertEqual(again.formalization.status, "FORMALIZED")  # выбор сохранён и переиспользован
        self.assertEqual(again.formalization.selected.reading_id, owns["reading_id"])

    def test_unresolved_reference_then_bind(self):
        with self.assertRaises(KSError) as cm:
            self.kb.accept_text("Он использует Python.", origin="note:1")
        self.assertEqual(cm.exception.kind, ErrorKind.UNRESOLVED_REFERENCE)
        self.kb.bind("note:1", "c0.0", "алекс")
        self.kb.accept_text("Он использует Python.", origin="note:1")
        self.assertEqual(Answerer(self.kb).ask_literals("?", [lit("uses(алекс, python)")]).status, "PROVEN")

    def test_rejected_cannot_become_authoritative_without_new_decision(self):
        node = parse_one("uses(алекс, java)", ksf_symbol)
        self.kb.reject(targets=[node.id], reason="неверно")
        with self.assertRaises(KSError):
            self.kb.accept([node])
        self.assertFalse(self.kb.state.active(node.id))
        self.kb.accept([node], override_rejection=True, note="пересмотрено человеком")
        self.assertTrue(self.kb.state.records[node.id].authoritative)

    def test_claim_is_not_fact(self):
        self.kb.accept_text("По словам врача, кофе повышает давление.")
        st = self.kb.state
        self.assertEqual([r.role for r in st.records.values()], ["claim"])
        self.assertEqual(st.authoritative(), [])
        a = Answerer(self.kb).ask_literals("?", [lit("increases(кофе, давление)")])
        self.assertEqual(a.status, "UNKNOWN")
        self.assertTrue(a.notes)  # но система сообщает, что заявление есть
        h = HypothesisEngine(self.kb).evaluate_text("Кофе повышает давление.")
        self.assertEqual(h.status, "SUPPORTED")
        # человек принимает утверждение как факт — новое решение
        claim = next(iter(st.records.values()))
        self.kb.accept([claim.node], role="fact", confidence="medium")
        self.assertEqual(HypothesisEngine(self.kb).evaluate_text("Кофе повышает давление.").status, "PROVEN")


class ReasoningScenarioTests(unittest.TestCase):
    def setUp(self):
        self.t = TempKB()
        self.kb = self.t.kb
        self.ans = Answerer(self.kb)

    def tearDown(self):
        self.t.close()

    def test_rule_derivation_with_proof_to_source(self):
        kb = self.kb
        kb.accept_text("Алекс является разработчиком.", confidence="high")
        kb.accept_text("Алекс работает с Python.", confidence="medium")
        kb.accept_text("Если X является разработчиком и X работает с Python, то X использует Python.",
                       confidence="high")
        r = self.ans.ask_literals("Использует ли Алекс Python?", [lit("uses(алекс, python)")])
        self.assertEqual(r.status, "PROVEN")
        self.assertEqual(r.evidence[0]["kind"], "DERIVED")
        proof = r.proof[0]
        self.assertIn("по правилу R:", proof)
        self.assertIn("Алекс работает с Python.", proof)  # провенанс до исходного текста
        self.assertIn("уверенность=medium", proof)        # самое слабое звено

    def test_contradiction_is_local_not_explosive(self):
        kb = self.kb
        kb.accept_text("Кофе полезен.")
        kb.accept_text("Кофе не полезен.")
        kb.accept_text("Чай полезен.")
        r = self.ans.ask_literals("?", [lit("is_a(кофе, полезный)")])
        self.assertEqual(r.status, "BOTH")
        self.assertEqual(self.ans.ask_literals("?", [lit("is_a(чай, полезный)")]).status, "PROVEN")
        self.assertEqual(self.ans.ask_literals("?", [lit("is_a(чай, вредный)")]).status, "UNKNOWN")
        c = self.ans.contradictions()
        self.assertEqual(len(c), 1)
        self.assertTrue(c[0]["positive_basis"][0]["provenance"][0]["text"].startswith("Кофе полезен"))

    def test_unknown_is_not_false(self):
        self.kb.accept_text("Алекс любит кошек.")
        self.assertEqual(self.ans.ask_literals("?", [lit("likes(алекс, собака)")]).status, "UNKNOWN")
        self.kb.accept_text("Алекс не любит собак.")
        self.assertEqual(self.ans.ask_literals("?", [lit("likes(алекс, собака)")]).status, "DISPROVEN")

    def test_revision_invalidates_and_supersedes(self):
        kb = self.kb
        kb.accept_text("Возраст Алекса равен 12.")
        kb.accept_text("older(X, Y) :- value(X, возраст, A), value(Y, возраст, B), A > B.")
        kb.accept_text("Возраст Анны равен 11.")
        self.assertEqual(self.ans.ask_literals("?", [lit("older(алекс, анна)")]).status, "PROVEN")
        old = parse_one("value(алекс, возраст, 12)", ksf_symbol)
        res = kb.revise(old.id, parse_one("value(алекс, возраст, 10)", ksf_symbol), reason="Исправление")
        self.assertIn("A:" + parse_one("older(алекс, анна)", ksf_symbol).id[2:], res.auth.removed)
        self.assertEqual(self.ans.ask_literals("?", [lit("older(алекс, анна)")]).status, "UNKNOWN")
        self.assertEqual(self.ans.ask_literals("?", [lit("older(анна, алекс)")]).status, "PROVEN")
        rec_old = kb.state.records[old.id]
        self.assertEqual(rec_old.status.value, "OBSOLETE")
        self.assertTrue(rec_old.superseded_by.startswith("F:"))

    def test_retraction_reports_decision_impact(self):
        kb = self.kb
        kb.accept_text("Linux является бесплатным.")
        kb.accept_text("Решение: перейти на Linux, потому что Linux является бесплатным.")
        fact = parse_one("is_a(linux, бесплатный)", ksf_symbol)
        res = kb.retract(fact.id, reason="ошибка")
        self.assertTrue(any("перейти на Linux" in n for n in res.notes))

    def test_what_if_does_not_write(self):
        kb = self.kb
        kb.accept_text("f1(a).")
        kb.accept_text("f2(X) :- f1(X).")
        n = len(kb.journal.entries())
        d = kb.what_if_retract(parse_one("f1(a)", ksf_symbol).id)
        self.assertEqual(len(d.removed), 2)
        self.assertEqual(len(kb.journal.entries()), n)
        self.assertIn("A:" + parse_one("f2(a)", ksf_symbol).id[2:], kb.auth.items)

    def test_temporal_validity(self):
        self.kb.accept_text("С 2025-01-01 по 2025-06-01 Алекс работает в Google.")
        q = [lit("works_at(алекс, google)")]
        self.assertEqual(self.ans.ask_literals("?", q, at="2025-03-01").status, "PROVEN")
        self.assertEqual(self.ans.ask_literals("?", q, at="2025-07-01").status, "UNKNOWN")

    def test_hypothesis_statuses(self):
        kb = self.kb
        kb.accept_text("Стресс вызывает бессонницу.")
        kb.accept_text("По словам Ивана, кофе вызывает бессонницу.")
        kb.accept_text("По словам Петра, кофе не вызывает бессонницу.")
        kb.accept_text("Шум не вызывает бессонницу.")
        kb.accept_text("По словам Ивана, шум вызывает бессонницу.")
        kb.accept_text("Если X вызывает бессонницу, то X мешает сну.")
        H = HypothesisEngine(kb)
        self.assertEqual(H.evaluate_text("Стресс вызывает бессонницу.").status, "PROVEN")
        self.assertEqual(H.evaluate_text("Кофе вызывает бессонницу.").status, "CONTESTED")
        self.assertEqual(H.evaluate_text("Шум вызывает бессонницу.").status, "REFUTED")  # факт сильнее заявления
        u = H.evaluate_text("Чай вызывает бессонницу.")
        self.assertEqual(u.status, "UNKNOWN")

    def test_missing_premises_for_unknown(self):
        kb = self.kb
        kb.accept_text("Если X является разработчиком и X работает с Python, то X использует Python.")
        kb.accept_text("Алекс является разработчиком.")
        u = HypothesisEngine(kb).evaluate_text("Алекс использует Python.")
        self.assertEqual(u.status, "UNKNOWN")
        self.assertEqual(u.missing_premises[0]["missing"], ["works_with(алекс, python)"])


class ValidationAndSmtTests(unittest.TestCase):
    def setUp(self):
        self.t = TempKB()
        self.kb = self.t.kb

    def tearDown(self):
        self.t.close()

    def test_schema_errors_in_preview(self):
        self.kb.accept_text("Возраст Алекса равен 30.")
        pv = self.kb.preview("Возраст Алекса равен 35.")
        kinds = [d["kind"] for d in pv.options[0]["diagnostics"]]
        self.assertIn("SCHEMA_ERROR", kinds)  # возраст функционален
        pv = self.kb.preview("Возраст Анны равен 500.")
        self.assertTrue(any("outside" in d["message"] for d in pv.options[0]["diagnostics"]))
        pv = self.kb.preview("born_on(алекс, москва).")
        self.assertTrue(any(d["kind"] == "TYPE_ERROR" for d in pv.options[0]["diagnostics"]))

    def test_custom_constraint(self):
        self.kb.accept_text('constraint "никто не моложе нуля": :- value(X, возраст, A), A < 0.')
        pv = self.kb.preview("value(алекс, возраст, -1).")
        self.assertTrue(any("никто не моложе нуля" in d["message"] for d in pv.options[0]["diagnostics"]))

    def test_smt_numeric_contradiction_and_entailment(self):
        kb = self.kb
        kb.accept_text("Возраст Алекса больше 40.")
        kb.accept_text('attr_cmp(анна, возраст, ">", алекс, возраст).')
        r = smt.entails(kb.auth, "ан", "возраст", ">", 40)
        if smt.available():
            self.assertEqual(r["status"], "PROVEN")
            kb.accept_text("Возраст Анны равен 30.")
            c = smt.consistency(kb.auth)
            self.assertEqual(c["status"], "UNSAT")
            self.assertEqual(len(c["core"]), 3)
        else:
            self.assertEqual(r["error"], "SMT_UNAVAILABLE")


class VersioningTests(unittest.TestCase):
    def test_replay_is_deterministic_and_incremental_equals_full(self):
        t = TempKB()
        kb = t.kb
        kb.accept_text("#transitive part_of.")
        for i in range(5):
            kb.accept_text(f"part_of(n{i}, n{i + 1}).")
        kb.retract(parse_one("part_of(n2, n3)", ksf_symbol).id)
        kb.accept_text("part_of(n2, n3).")
        reopened = t.reopen()
        self.assertEqual(kb.auth.snapshot(), reopened.auth.snapshot())
        self.assertEqual(json.dumps({k: r.to_dict() for k, r in kb.state.records.items()}, sort_keys=True),
                         json.dumps({k: r.to_dict() for k, r in reopened.state.records.items()}, sort_keys=True))
        t.close()

    def test_history_at_commit(self):
        t = TempKB()
        kb = t.kb
        e1 = kb.accept_text("f(a).").entries[0]
        kb.accept_text("f(b).")
        past = t.reopen(at=e1.id)
        self.assertEqual(len(past.state.authoritative()), 1)
        with self.assertRaises(KSError):
            past.accept_text("f(c).")
        t.close()

    def test_tampering_is_detected(self):
        t = TempKB()
        t.kb.accept_text("f(a).")
        p = t.ws.journal_path
        p.write_text(p.read_text(encoding="utf-8").replace('"f"', '"g"'), encoding="utf-8")
        reopened = t.reopen()
        self.assertTrue(any(d.kind == ErrorKind.JOURNAL_ERROR for d in reopened.journal.verify()))
        t.close()


if __name__ == "__main__":
    unittest.main()
