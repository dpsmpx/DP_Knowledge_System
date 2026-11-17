import random
import unittest

from ksys.core.enums import Modality
from ksys.ir import Assertion, Atom, Interval, sym
from ksys.ir.ksf import iter_rules, parse_ksf
from ksys.inference.engine import Engine


def A(pred, *args, neg=False, ctx=None, iv=None, mod=Modality.ASSERTED):
    return Assertion(Atom(pred, tuple(sym(a) for a in args)), neg, mod, ctx, iv or Interval())


def engine_with(rules_src, facts):
    e = Engine()
    for r in iter_rules(parse_ksf(rules_src)):
        e.add_rule(r.id, r)
    for f in facts:
        e.add_base(f, "F:" + f.id[2:])
    return e


def holds(e, pred, *args, neg=False):
    return A(pred, *args, neg=neg).id in e.items


class DatalogTests(unittest.TestCase):
    def test_scenario1_two_facts_rule_derived(self):
        e = engine_with("uses(X, Y) :- developer(X), works_with(X, Y).",
                        [A("developer", "alex"), A("works_with", "alex", "python")])
        self.assertTrue(holds(e, "uses", "alex", "python"))
        (j,) = e.justifications(A("uses", "alex", "python").id)
        self.assertEqual(j.premises, (A("developer", "alex").id, A("works_with", "alex", "python").id))

    def test_recursion_transitive_closure(self):
        e = engine_with("#transitive part_of.", [A("part_of", f"n{i}", f"n{i+1}") for i in range(10)])
        self.assertTrue(holds(e, "part_of", "n0", "n10"))
        self.assertEqual(sum(1 for a in e.items.values() if a.atom.predicate == "part_of"), 55)

    def test_strong_negation_is_independent_and_not_explosive(self):
        e = engine_with("not safe(X) :- risky(X).\nrecommend(X) :- safe(X).",
                        [A("safe", "x"), A("risky", "x")])
        self.assertTrue(holds(e, "safe", "x"))
        self.assertTrue(holds(e, "safe", "x", neg=True))  # BOTH, а не «всё истинно»
        self.assertTrue(holds(e, "recommend", "x"))
        self.assertFalse(holds(e, "recommend", "y"))
        self.assertEqual(len(e.items), 4)

    def test_interval_intersection_and_empty(self):
        e = engine_with("r(X) :- p(X), q(X).",
                        [A("p", "a", iv=Interval("2025-01-01", "2025-06-01")),
                         A("q", "a", iv=Interval("2025-03-01", None)),
                         A("p", "b", iv=Interval("2025-01-01", "2025-02-01")),
                         A("q", "b", iv=Interval("2025-03-01", None))])
        self.assertIn(A("r", "a", iv=Interval("2025-03-01", "2025-06-01")).id, e.items)
        self.assertFalse(any(a.atom.args[0] == sym("b") and a.atom.predicate == "r" for a in e.items.values()))

    def test_context_meet_and_modality(self):
        e = engine_with("r(X) :- p(X), q(X).",
                        [A("p", "a", ctx="work"), A("q", "a", mod=Modality.USUALLY),
                         A("p", "b", ctx="work"), A("q", "b", ctx="home")])
        self.assertIn(A("r", "a", ctx="work", mod=Modality.USUALLY).id, e.items)
        self.assertFalse(any(a.atom.predicate == "r" and a.atom.args[0] == sym("b") for a in e.items.values()))

    def test_comparisons(self):
        e = Engine()
        for r in iter_rules(parse_ksf("older(X, Y) :- value(X, age, A), value(Y, age, B), A > B.")):
            e.add_rule(r.id, r)
        for f in parse_ksf("value(alex, age, 30). value(anna, age, 25)."):
            e.add_base(f.assertion, f.id)
        self.assertTrue(holds(e, "older", "alex", "anna"))
        self.assertFalse(holds(e, "older", "anna", "alex"))


class BulkLoadTests(unittest.TestCase):
    def test_bulk_equals_incremental(self):
        rules = list(iter_rules(parse_ksf("#transitive edge.\nreach(X, Y) :- edge(X, Y).\nnot cut(X) :- reach(X, X).")))
        rng = random.Random(3)
        facts = [A("edge", f"n{rng.randrange(8)}", f"n{rng.randrange(8)}") for _ in range(20)]
        inc = Engine()
        for r in rules:
            inc.add_rule(r.id, r)
        for f in facts:
            inc.add_base(f, "s")
        bulk = Engine()
        bulk.bulk_load([(r.id, r) for r in rules], [(f, "s") for f in facts])
        self.assertEqual(inc.snapshot(), bulk.snapshot())


class TmsTests(unittest.TestCase):
    def test_retraction_invalidates_chain(self):
        e = engine_with("f2(X) :- f1(X).\nf3(X) :- f2(X).", [A("f1", "a")])
        self.assertTrue(holds(e, "f3", "a"))
        d = e.remove_base(A("f1", "a").id, "F:" + A("f1", "a").id[2:])
        self.assertEqual(d.removed, {A("f1", "a").id, A("f2", "a").id, A("f3", "a").id})
        self.assertFalse(holds(e, "f3", "a"))

    def test_alternative_support_survives(self):
        e = engine_with("q(X) :- p(X).\nq(X) :- r(X).", [A("p", "a"), A("r", "a")])
        d = e.remove_base(A("p", "a").id, "F:" + A("p", "a").id[2:])
        self.assertTrue(holds(e, "q", "a"))
        self.assertEqual(d.removed, {A("p", "a").id})  # только сам отозванный факт
        self.assertEqual(d.rederived, {A("q", "a").id})

    def test_cycle_does_not_self_support(self):
        e = engine_with("q(X) :- p(X).\np(X) :- q(X).\np(X) :- s(X).", [A("s", "a")])
        self.assertTrue(holds(e, "q", "a"))
        e.remove_base(A("s", "a").id, "F:" + A("s", "a").id[2:])
        self.assertFalse(holds(e, "p", "a"))
        self.assertFalse(holds(e, "q", "a"))

    def test_rule_removal(self):
        e = engine_with("rule r: q(X) :- p(X).", [A("p", "a")])
        (rule,) = list(iter_rules(parse_ksf("rule r: q(X) :- p(X).")))
        e.remove_rule(rule.id)
        self.assertFalse(holds(e, "q", "a"))
        self.assertEqual(e.just, {})

    def test_incremental_equals_full_recompute_property(self):
        """Инвариант TMS: после любой последовательности добавлений/удалений состояние
        совпадает с полным пересчётом от оставшихся фактов и правил."""
        rules = list(iter_rules(parse_ksf(
            "#transitive edge.\nreach(X, Y) :- edge(X, Y).\nnot isolated(X) :- edge(X, Y).\n"
            "linked(X, Y) :- reach(X, Y), reach(Y, X).")))
        rng = random.Random(42)
        nodes = [f"n{i}" for i in range(6)]
        for trial in range(30):
            inc = Engine()
            for r in rules:
                inc.add_rule(r.id, r)
            live: dict = {}
            for step in range(25):
                if live and rng.random() < 0.4:
                    aid = rng.choice(sorted(live))
                    inc.remove_base(aid, "src")
                    del live[aid]
                else:
                    a = A("edge", rng.choice(nodes), rng.choice(nodes))
                    inc.add_base(a, "src")
                    live[a.id] = a
                full = Engine()
                for r in rules:
                    full.add_rule(r.id, r)
                for a in live.values():
                    full.add_base(a, "src")
                self.assertEqual(inc.snapshot(), full.snapshot(), (trial, step))


if __name__ == "__main__":
    unittest.main()
