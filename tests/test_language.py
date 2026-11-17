import unittest

from ksys.ir.ksf import parse_one
from ksys.language.stem import stem
from ksys.language.tokenize import tokenize
from ksys.parser.cnl import CNLParser
from ksys.semantics.build import NullResolver
from ksys.semantics.formalize import Formalizer
from ksys.semantics.normalize import ksf_symbol

# GOLDEN: вход на контролируемом языке → ожидаемый IR (в KSF).
GOLDEN = {
    "Python является интерпретируемым языком.": ["is_a(python, интерпретируем_язык)."],
    "Python — это интерпретируемый язык.": ["is_a(python, интерпретируем_язык)."],
    "Алекс не является программистом.": ["not is_a(алекс, программист)."],
    "Кофе не полезен.": ["not is_a(коф, полезн)."],
    "Стресс вызывает бессонницу.": ["causes(стресс, бессонниц)."],
    "Стресс приводит к бессоннице.": ["causes(стресс, бессонниц)."],
    "Алекс работает с Python.": ["works_with(алекс, python)."],
    "Москва находится в России.": ["located_in(москв, росс)."],
    "Алекс использует Python и Git.": ["uses(алекс, python).", "uses(алекс, git)."],
    "Алекс использует Python и работает с Git.": ["uses(алекс, python).", "works_with(алекс, git)."],
    "Python лучше Java.": ["better_than(python, java)."],
    "Возраст Алекса равен 30.": ["value(алекс, возраст, 30)."],
    "Возраст Алекса составляет 30 лет.": ['measure(алекс, возраст, 30, "лет").'],
    "Алекс родился 1990-05-01.": ["born_on(алекс, 1990-05-01)."],
    "Мне нравится кофе.": ["likes(me, коф)."],
    "Я не использую Windows.": ["not uses(me, windows)."],
    "Возможно, кофе повышает давление.": ["hypothesis increases(коф, давлен) ~possible."],
    "Гипотеза: стресс вызывает бессонницу.": ["hypothesis causes(стресс, бессонниц)."],
    "Обычно Python выполняет код.": ["executes(python, код) ~usually."],
    "По словам врача, кофе повышает давление.": ["claim by врач: increases(коф, давлен)."],
    "Иван утверждает, что кофе вреден.": ["claim by ива: is_a(коф, вредн)."],  # Snowball: иван/ивана → ива
    "С 2025-01-01 по 2025-06-01 Алекс работает в Google.": ["works_at(алекс, google) @[2025-01-01, 2025-06-01]."],
    "Алекс работает в Google в 2025 году.": ["works_at(алекс, google) @[2025-01-01, 2025-12-31]."],
    "В мае 2025 Алекс посетил Москву.": ["visited(алекс, москв) @[2025-05-01, 2025-05-31]."],
    "1 января 2025 Алекс посетил Москву.": ["visited(алекс, москв) @2025-01-01."],
    "В контексте работы: Алекс использует Python.": ["uses(алекс, python) in работ."],
    "Каждый разработчик использует Git.": ["uses(?V1, git) :- is_a(?V1, разработчик)."],
    "Все кошки являются животными.": ["is_a(?V1, животн) :- is_a(?V1, кошк)."],
    "Ни один кот не является собакой.": ["not is_a(?V1, собак) :- is_a(?V1, кот)."],
    "Если X является разработчиком и X работает с Python, то X использует Python.":
        ["uses(?X, python) :- is_a(?X, разработчик), works_with(?X, python)."],
    "Если кто-то является разработчиком, то он использует Git.": ["uses(?V1, git) :- is_a(?V1, разработчик)."],
    "Отношение «является частью» транзитивно.":
        ["rule transitive_part_of: part_of(?X, ?Z) :- part_of(?X, ?Y), part_of(?Y, ?Z)."],
    "Определение: алгоритм — это конечная последовательность шагов, которая решает задачу.":
        ['define алгоритм as конечн_последовательн_шаг "конечная последовательность шагов, которая решает задачу".',
         "is_a(алгоритм, конечн_последовательн_шаг)."],
    "Кто использует Python?": ["?- uses(?WHO, python)."],
    "Верно ли, что Python является языком?": ["?- is_a(python, язык)."],
}


def ksf_of(text):
    r = Formalizer().formalize(text)
    assert r.status == "FORMALIZED", (text, r.status, r.reason)
    return [n.ksf() for n in r.selected.nodes]


class GoldenTests(unittest.TestCase):
    def test_golden_ir(self):
        for text, expected in GOLDEN.items():
            with self.subTest(text=text):
                self.assertEqual(ksf_of(text), expected)

    def test_golden_ksf_is_parseable_ir(self):
        for expected in GOLDEN.values():
            for line in expected:
                self.assertEqual(parse_one(line, lambda s: s).ksf(), line)


class NormalizationTests(unittest.TestCase):
    """Сценарий 8: одно знание разными словами → один ID."""

    def ids(self, text):
        return [n.id for n in Formalizer().formalize(text).selected.nodes]

    def test_synonymous_verbs(self):
        self.assertEqual(self.ids("Стресс вызывает бессонницу."), self.ids("Стресс приводит к бессоннице."))

    def test_copula_variants_and_cases(self):
        a = self.ids("Python является интерпретируемым языком.")
        self.assertEqual(a, self.ids("Python — это интерпретируемый язык."))
        self.assertEqual(a, self.ids("Python — интерпретируемый язык."))

    def test_ksf_and_cnl_share_keys(self):
        self.assertEqual(self.ids("is_a(python, интерпретируемый_язык)."),
                         self.ids("Python является интерпретируемым языком."))

    def test_stemmer_examples(self):
        self.assertEqual(stem("программирования"), "программирован")
        self.assertEqual(ksf_symbol("язык_программирования"), "язык_программирован")


class AmbiguityTests(unittest.TestCase):
    def test_collective_vs_distributive(self):
        r = Formalizer().formalize("Алекс и Анна владеют домом.")
        self.assertEqual(r.status, "AMBIGUOUS")
        self.assertEqual(sorted(i.labels for i in r.interpretations),
                         [("coord=collective",), ("coord=distributive",)])

    def test_lexical_ambiguity(self):
        r = Formalizer().formalize("Алекс держит магазин.")
        self.assertEqual(r.status, "AMBIGUOUS")
        self.assertEqual({n.assertion.atom.predicate for i in r.interpretations for n in i.nodes}, {"owns", "holds"})

    def test_adjective_scope(self):
        r = Formalizer().formalize("Старые книги и журналы полезны.")
        self.assertEqual(r.status, "AMBIGUOUS")

    def test_pronoun_in_rule_with_two_antecedents(self):
        r = CNLParser().parse("Если кто-то знает что-то, то он использует его.")
        self.assertEqual(r.status, "AMBIGUOUS")

    def test_saved_choice_is_reused(self):
        class Chooser(NullResolver):
            def __init__(self, rid):
                self.rid = rid

            def reading_choice(self, origin):
                return self.rid
        first = Formalizer().formalize("Алекс держит магазин.", origin="f_x")
        owns = next(i for i in first.interpretations if i.labels == ("sense=owns",))
        again = Formalizer(resolver=Chooser(owns.reading_id)).formalize("Алекс держит магазин.", origin="f_x")
        self.assertEqual(again.status, "FORMALIZED")
        self.assertEqual(again.selected.nodes[0].assertion.atom.predicate, "owns")


class ReferenceTests(unittest.TestCase):
    def test_pronoun_is_not_guessed(self):
        for text in ("Он использует Python.", "Мы используем Git.", "Температура равна 20."):
            r = Formalizer().formalize(text)
            self.assertEqual(r.status, "UNRESOLVED", text)
            self.assertFalse(r.interpretations[0].nodes)
            self.assertTrue(r.interpretations[0].unresolved[0]["question"])

    def test_binding_resolves(self):
        class Binder(NullResolver):
            def binding(self, origin, slot):
                return "алекс" if (origin, slot) == ("f_1", "c0.0") else None
        r = Formalizer(resolver=Binder()).formalize("Он использует Python.", origin="f_1")
        self.assertEqual(r.status, "FORMALIZED")
        self.assertEqual(r.selected.nodes[0].ksf(), "uses(алекс, python).")


class NoParseTests(unittest.TestCase):
    def test_outside_cnl_is_reported_with_reason(self):
        cases = {"Python часто используется для анализа данных.": "no predicate",
                 "Обычно Python выполняет код через интерпретатор.": "prepositional",
                 "Python (язык) — это хорошо.": "punctuation",
                 "Алекс любит кошек или собак.": "disjunction",
                 "Язык, который прост, полезен.": "unsupported word"}
        for text, reason in cases.items():
            r = Formalizer().formalize(text)
            self.assertEqual(r.status, "NO_PARSE", text)
            self.assertIn(reason, r.reason, text)


class ParaphraseTests(unittest.TestCase):
    def test_canonical_paraphrases(self):
        cases = {"Мне нравится кофе.": "Я люблю «кофе».",
                 "Возможно, кофе повышает давление.": "Гипотеза: возможно, «кофе» повышает «давление».",
                 "Алекс не является программистом.": "Алекс не является «программистом».",
                 "С 2025-01-01 по 2025-06-01 Алекс работает в Google.":
                     "Алекс работает в Google (в период с 2025-01-01 по 2025-06-01)."}
        for text, para in cases.items():
            self.assertEqual(Formalizer().formalize(text).selected.paraphrase, para)


class TokenizeTests(unittest.TestCase):
    def test_tokens(self):
        kinds = [t.kind for t in tokenize("«Война и мир» [[Python|питон]] 3,5 2025-01-01 X кто-то — F:0123456789abcdef")]
        self.assertEqual(kinds, ["NAME", "LINK", "NUM", "DATE", "VAR", "WORD", "PUNCT", "NODEID"])


if __name__ == "__main__":
    unittest.main()


