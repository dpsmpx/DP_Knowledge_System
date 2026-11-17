import random
import unittest

from ksys.core.enums import Modality
from ksys.core.errors import ErrorKind, KSError
from ksys.ir import (Assertion, Atom, Claim, Comparison, Fact, Hypothesis, Interval, Literal,
                     Rule, Var, node_from_dict, num, string, sym)
from ksys.ir.ksf import Directive, expand_directive, format_node, parse_ksf, parse_one
from ksys.ir.terms import canonical_number
from ksys.ir.validate import check_all, check_node


class TermTests(unittest.TestCase):
    def test_canonical_numbers(self):
        self.assertEqual(canonical_number("10.50"), "10.5")
        self.assertEqual(canonical_number("1e2"), "100")
        self.assertEqual(canonical_number("-0"), "0")
        self.assertEqual(num(30), num("30.0"))

    def test_bad_date_is_type_error(self):
        with self.assertRaises(KSError) as cm:
            Interval("2025-13-01", None)
        self.assertEqual(cm.exception.kind, ErrorKind.TYPE_ERROR)

    def test_interval_algebra(self):
        a = Interval("2025-01-01", "2025-06-01")
        b = Interval("2025-03-01", None)
        self.assertEqual(a.intersect(b), Interval("2025-03-01", "2025-06-01"))
        self.assertIsNone(a.intersect(Interval("2025-07-01", "2025-08-01")))
        self.assertTrue(a.contains("2025-02-02"))
        self.assertFalse(a.contains("2025-06-02"))
        self.assertTrue(Interval().covers(a))
        self.assertFalse(a.covers(Interval()))

    def test_comparison_semantics(self):
        c = Comparison(">", Var("A"), Var("B"))
        self.assertTrue(c.evaluate({"A": num(5), "B": num(3)}))
        self.assertFalse(c.evaluate({"A": num(5), "B": string("x")}))  # несравнимо ≠ истина
        with self.assertRaises(KSError):
            c.evaluate({"A": num(5)})


class AssertionIdentityTests(unittest.TestCase):
    def test_same_content_same_id(self):
        a1 = Assertion(Atom("uses", (sym("alex"), sym("python"))))
        a2 = Assertion(Atom("uses", (sym("alex"), sym("python"))))
        self.assertEqual(a1.id, a2.id)
        self.assertEqual(Fact(a1).id.split(":")[1], a1.id.split(":")[1])

    def test_polarity_modality_context_interval_change_identity(self):
        base = Assertion(Atom("uses", (sym("alex"), sym("python"))))
        variants = [base.with_(negated=True), base.with_(modality=Modality.USUALLY),
                    base.with_(context="work"), base.with_(interval=Interval("2025-01-01", None))]
        ids = {base.id} | {v.id for v in variants}
        self.assertEqual(len(ids), 5)

    def test_claim_is_not_fact(self):
        a = Assertion(Atom("increases", (sym("x"), sym("performance"))))
        self.assertNotEqual(Claim(a, "article_a").id, Fact(a).id)
        self.assertTrue(Claim(a).id.startswith("C:"))

    def test_assertion_must_be_ground(self):
        with self.assertRaises(KSError) as cm:
            Assertion(Atom("uses", (Var("X"), sym("python"))))
        self.assertEqual(cm.exception.kind, ErrorKind.LOGIC_ERROR)


KSF_SAMPLES = """
developer(alex).
not likes(alex, java).
claim by ivan: increases(coffee, performance).
hypothesis causes(stress, insomnia).
value(alex, age, 30) @[2025-01-01, 2025-06-01] in work ~usually.
born(alex, 1990-05-01) @1990-05-01.
rule r1: uses(X, Y) :- developer(X), works_with(X, Y).
older(X, Y) :- value(X, age, A), value(Y, age, B), A > B.
not safe(X) :- has_risk(X, high).
constraint "возраст неотрицателен": :- value(X, age, A), A < 0.
?- uses(alex, X).
decision "проверить \\"рынок\\"" because F:0123456789abcdef expect "ясность спроса".
define язык as формальная_система "система записи".
event visit(who: alex, where: moscow) @[2025-01-01, 2025-01-03] "поездка".
"""


class KsfTests(unittest.TestCase):
    def test_roundtrip_every_sample(self):
        items = parse_ksf(KSF_SAMPLES)
        self.assertEqual(len(items), 14)
        for item in items:
            text = format_node(item)
            again = parse_one(text)
            self.assertEqual(again, item, text)
            self.assertEqual(format_node(again), text)

    def test_dict_roundtrip(self):
        for item in parse_ksf(KSF_SAMPLES):
            self.assertEqual(node_from_dict(item.to_dict()), item)

    def test_kinds(self):
        items = parse_ksf(KSF_SAMPLES)
        self.assertIsInstance(items[0], Fact)
        self.assertTrue(items[1].assertion.negated)
        self.assertIsInstance(items[2], Claim)
        self.assertEqual(items[2].author, "ivan")
        self.assertIsInstance(items[3], Hypothesis)
        a = items[4].assertion
        self.assertEqual((a.context, a.modality, a.interval), ("work", Modality.USUALLY,
                                                               Interval("2025-01-01", "2025-06-01")))
        self.assertIsInstance(items[6], Rule)
        self.assertEqual(items[6].name, "r1")

    def test_symbols_are_normalized_by_injected_normalizer(self):
        item = parse_one("uses(Алекс_Иванов, ЁЖИК)", normalize=lambda s: s.lower().replace("ё", "е"))
        self.assertEqual(item.assertion.atom.args, (sym("алекс_иванов"), sym("ежик")))

    def test_errors_are_typed(self):
        cases = {"uses(alex, $).": ErrorKind.LEXICAL_ERROR,
                 "uses(alex python).": ErrorKind.PARSE_ERROR,
                 "uses(X, python).": ErrorKind.LOGIC_ERROR,
                 "#frobnicate p.": ErrorKind.PARSE_ERROR}
        for src, kind in cases.items():
            with self.assertRaises(KSError, msg=src) as cm:
                parse_ksf(src)
            self.assertEqual(cm.exception.kind, kind, src)

    def test_directives_expand_to_rules(self):
        (d,) = parse_ksf("#transitive part_of.")
        self.assertEqual(d, Directive("transitive", ("part_of",)))
        (r,) = expand_directive(d)
        self.assertEqual(format_node(r), "rule transitive_part_of: part_of(?X, ?Z) :- part_of(?X, ?Y), part_of(?Y, ?Z).")
        rules = expand_directive(Directive("disjoint", ("cat", "dog")))
        self.assertTrue(all(r.head.negated for r in rules))


class ValidateTests(unittest.TestCase):
    def test_unsafe_rule(self):
        r = Rule(Literal(Atom("p", (Var("X"), Var("Z")))), (Literal(Atom("q", (Var("X"),))),))
        diags = check_node(r)
        self.assertTrue(any("unsafe" in d.message for d in diags))

    def test_unbound_comparison(self):
        r = parse_one("p(X) :- q(X), Y > 3.")
        self.assertTrue(any("unbound" in d.message for d in check_node(r)))

    def test_arity_warning(self):
        diags = check_all(parse_ksf("p(a). p(a, b)."))
        self.assertEqual([d.kind for d in diags], [ErrorKind.TYPE_ERROR])


class PropertyTests(unittest.TestCase):
    """Свойство: format ∘ parse = id для случайных (детерминированно засеянных) утверждений."""

    def test_random_assertions_roundtrip(self):
        rng = random.Random(20261005)
        syms = ["alex", "python", "язык", "x1", "кот_мурзик"]
        for _ in range(300):
            args = []
            for _ in range(rng.randint(1, 3)):
                kind = rng.choice(["sym", "num", "str"])
                args.append(sym(rng.choice(syms)) if kind == "sym" else
                            num(rng.randint(-50, 50)) if kind == "num" else
                            string(rng.choice(["a b", 'q"uote', "слово"])))
            start = rng.choice([None, "2025-01-01"])
            end = rng.choice([None, "2025-12-31"])
            a = Assertion(Atom(rng.choice(["p", "uses", "имеет"]), tuple(args)), rng.random() < 0.3,
                          rng.choice(list(Modality)), rng.choice([None, "work"]), Interval(start, end))
            node = rng.choice([Fact(a), Claim(a, rng.choice([None, "ivan"])), Hypothesis(a)])
            self.assertEqual(parse_one(format_node(node)), node)


if __name__ == "__main__":
    unittest.main()
