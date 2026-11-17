import unittest

from ksys.core.enums import DialogueOp as Op
from ksys.dialogue.intents import classify
from ksys.dialogue.session import Dialogue
from tests.helpers import TempKB

EXAMPLES = {
    "А правильно ли, что стресс приводит к бессоннице?": Op.VERIFY,
    "Почему ты считаешь, что Алекс использует Python?": Op.EXPLAIN,
    "Какие факты противоречат «кофе полезен»?": Op.FIND_CONTRADICTIONS,
    "Проверь гипотезу, что Python лучше Java.": Op.HYPOTHESIZE,
    "Добавь факт: Алекс является разработчиком.": Op.ASSERT,
    "Определи понятие алгоритм.": Op.DEFINE,
    "Что следует из Алекс является разработчиком и Алекс работает с Python?": Op.DERIVE,
    "Откуда взялся этот вывод?": Op.TRACE,
    "Какие неизвестные остаются?": Op.UNKNOWNS,
    "Что будет, если удалить факт F:0123456789abcdef?": Op.WHAT_IF,
    "Исправление: возраст Алекса было 10, а не 12.": Op.REVISE,
    "Удали факт F:0123456789abcdef.": Op.RETRACT,
    "Сравни Python и Java.": Op.COMPARE,
    "Какие бывают языки?": Op.SPECIALIZE,
    "К чему относится Python?": Op.GENERALIZE,
    "Кто использует Python?": Op.ASK,
}


class IntentTests(unittest.TestCase):
    def test_examples_from_spec(self):
        for text, op in EXAMPLES.items():
            self.assertEqual(classify(text).op, op, text)


class DialogueTests(unittest.TestCase):
    def setUp(self):
        self.t = TempKB()
        self.d = Dialogue(self.t.kb)

    def tearDown(self):
        self.t.close()

    def run_(self, text, **kw):
        return self.d.handle(text, **kw)

    def test_full_conversation(self):
        r = self.run_("Добавь факт: Алекс является разработчиком.")
        self.assertEqual(r.status, "PREVIEW_FORMALIZED")
        self.assertFalse(r.committed)
        self.assertEqual(self.t.kb.journal.entries(), [])
        self.assertTrue(self.run_("Добавь факт: Алекс является разработчиком.", yes=True).committed)
        self.run_("Добавь факт: Алекс работает с Python.", yes=True)
        self.run_("Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.", yes=True)
        ask = self.run_("Использует ли Алекс Python?")
        self.assertEqual(ask.status, "PROVEN")
        why = self.run_("Почему ты считаешь, что Алекс использует Python?")
        self.assertEqual(why.status, "PROVEN")
        self.assertIn("по правилу", why.sections["derivation"][0])
        tr = self.run_("Откуда взялся этот вывод?")
        self.assertEqual(tr.status, "TRACED")
        self.assertEqual(len(tr.sections["trace"]["base_facts"]), 2)
        wi = self.run_("Что будет, если удалить факт «Алекс работает с Python»?")
        self.assertEqual(wi.status, "SIMULATED")
        self.assertIn("uses(алекс, python)", wi.sections["removed"])
        self.assertEqual(self.run_("Использует ли Алекс Python?").status, "PROVEN")  # симуляция не изменила базу
        self.assertEqual(self.run_("Кто использует Python?").answer, "WHO=алекс")
        unk = self.run_("Какие неизвестные остаются?")
        self.assertIn("unrated_confidence", unk.sections["unknowns"])
        der = self.run_("Что следует из Анна является разработчиком; Анна работает с Python?")
        self.assertIn("uses(ан, python)", der.sections["consequences"])
        self.assertEqual(self.run_("Использует ли Анна Python?").status, "UNKNOWN")

    def test_why_without_proof_is_honest(self):
        r = self.run_("Почему ты считаешь, что Python лучше Java?")
        self.assertEqual(r.status, "NO_PROOF")

    def test_verify_and_hypothesis(self):
        self.run_("Добавь факт: стресс вызывает бессонницу.", yes=True)
        self.assertEqual(self.run_("А правильно ли, что стресс приводит к бессоннице?").status, "PROVEN")
        self.assertEqual(self.run_("Проверь гипотезу, что Python лучше Java.").status, "UNKNOWN")

    def test_revision_dialogue(self):
        self.run_("Добавь факт: возраст Алекса равен 12.", yes=True)
        p = self.run_("Исправление: возраст Алекса было 10, а не 12.")
        self.assertEqual(p.status, "PREVIEW")
        c = self.run_("Исправление: возраст Алекса было 10, а не 12.", yes=True)
        self.assertEqual(c.status, "COMMITTED")
        self.assertIn("SUPERSEDED_BY", c.answer)

    def test_contradictions_dialogue(self):
        self.run_("Добавь факт: кофе полезен.", yes=True)
        self.run_("Добавь факт: кофе не полезен.", yes=True)
        r = self.run_("Найди противоречия")
        self.assertEqual(r.status, "CONTRADICTIONS_FOUND")
        r2 = self.run_("Какие факты противоречат «кофе полезен»?")
        self.assertTrue(r2.sections["contradictions"])

    def test_define_and_compare(self):
        self.run_("Добавь факт: Определение: алгоритм — это конечная последовательность шагов, которая решает задачу.", yes=True)
        d = self.run_("Определи понятие алгоритм.")
        self.assertEqual(d.status, "KNOWN")
        self.assertIn("конечная последовательность", d.answer)


if __name__ == "__main__":
    unittest.main()
