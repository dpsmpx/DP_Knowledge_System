"""Канонический пересказ IR на русском (контроль семантических потерь).

Человек видит рядом: ИСХОДНЫЙ ТЕКСТ → ФОРМАЛЬНАЯ ЗАПИСЬ (KSF) → КАНОНИЧЕСКИЙ ПЕРЕСКАЗ.
Пересказ строится только из IR (не из исходного текста), поэтому если смысл
потерян при формализации, это видно по расхождению пересказа с оригиналом.
Ярлыки сущностей: сохранённый ярлык → поверхностная форма из предложения → ключ.
"""
from __future__ import annotations

from ..core.enums import Modality
from ..ir.nodes import (Assertion, Claim, Constraint, Decision, Definition, Event, Fact, Hypothesis,
                        Node, Question, Rule)
from ..ir.terms import Comparison, Const, Literal, Var
from ..language.lexicon import GLOSSES, GLOSSES_ME

NEG_GLOSSES = {"has": "у {0} нет {1}", "is_a": "{0} не является {1}"}
WH_TEXT = {"WHO": "кто", "WHAT": "что", "WHERE": "где", "WHEN": "когда", "WHICH": "какой"}


class Labeler:
    def __init__(self, resolver=None, surface: dict | None = None, glosses: dict | None = None):
        self.resolver = resolver
        self.surface = surface or {}
        self.glosses = glosses or GLOSSES

    def term(self, t) -> str:
        if isinstance(t, Var):
            return WH_TEXT.get(t.name, t.name)
        if t.kind == "sym":
            if t.value == "me":
                return "я"
            label = self.resolver.label(t.value) if self.resolver is not None else None
            label = label or self.surface.get(t.value)
            if label is None:
                if t.value.startswith("grp_"):
                    return "группа «" + " и ".join(t.value[4:].split("__")) + "»"
                return f"«{t.value}»"
            return label if (label[:1].isupper() or label.isascii()) and " " not in label else f"«{label}»"
        if t.kind == "str":
            return f"«{t.value}»"
        return t.value

    def literal(self, lit: Literal) -> str:
        p = lit.atom.predicate
        args = [self.term(a) for a in lit.atom.args]
        if p in ("bound", "attr_cmp") and len(args) > 2:
            ops = {">": "больше", "<": "меньше", ">=": "не меньше", "<=": "не больше", "=": "равно", "!=": "не равно"}
            args[2] = ops.get(lit.atom.args[2].value, args[2])
        first = lit.atom.args[0]
        if isinstance(first, Const) and first.kind == "sym" and first.value == "me" and p in GLOSSES_ME:
            tpl = GLOSSES_ME[p]
            if lit.negated:
                tpl = tpl.replace("я ", "я не ", 1) if tpl.startswith("я ") else "неверно, что " + tpl
            return tpl.format(*args)
        if lit.negated:
            tpl = NEG_GLOSSES.get(p)
            if tpl is None:
                base = self.glosses.get(p, "{0} " + p + " {1}")
                tpl = base.replace("{0} ", "{0} не ", 1) if base.startswith("{0} ") else "неверно, что " + base
        else:
            tpl = self.glosses.get(p, None) or ("{0} " + p.replace("_", " ") + " " +
                                                ", ".join("{%d}" % i for i in range(1, len(args))))
        try:
            return tpl.format(*args)
        except IndexError:
            return f"{p}({', '.join(args)})"

    def body_item(self, b) -> str:
        if isinstance(b, Comparison):
            ops = {"<": "меньше", "<=": "не больше", ">": "больше", ">=": "не меньше", "=": "равно", "!=": "не равно"}
            return f"{self.term(b.left)} {ops[b.op]} {self.term(b.right)}"
        return self.literal(b)

    def assertion(self, a: Assertion) -> str:
        text = self.literal(a.literal)
        if a.modality == Modality.POSSIBLE:
            text = "возможно, " + text
        elif a.modality == Modality.USUALLY:
            text = "обычно " + text
        if a.context:
            text = f"в контексте {self.term(Const('sym', a.context))}: " + text
        iv = a.interval
        if not iv.unbounded:
            if iv.start == iv.end:
                text += f" ({iv.start})"
            elif iv.start and iv.end:
                text += f" (в период с {iv.start} по {iv.end})"
            elif iv.start:
                text += f" (начиная с {iv.start})"
            else:
                text += f" (до {iv.end})"
        return text

    def node(self, n: Node) -> str:
        if isinstance(n, Fact):
            return _cap(self.assertion(n.assertion)) + "."
        if isinstance(n, Claim):
            who = f"по утверждению {self.term(Const('sym', n.author))}" if n.author else "по утверждению источника"
            return _cap(who) + ": " + self.assertion(n.assertion) + "."
        if isinstance(n, Hypothesis):
            return "Гипотеза: " + self.assertion(n.assertion) + "."
        if isinstance(n, Rule):
            body = " и ".join(self.body_item(b) for b in n.body)
            return f"Если {body}, то {self.literal(n.head)}."
        if isinstance(n, Constraint):
            return "Недопустимо, чтобы " + " и ".join(self.body_item(b) for b in n.body) + "."
        if isinstance(n, Question):
            body = " и ".join(self.body_item(b) for b in n.query)
            return (f"Верно ли, что {body}?" if not n.variables else _cap(body) + "?")
        if isinstance(n, Definition):
            g = f" — это {self.term(Const('sym', n.genus))}" if n.genus else ""
            d = f"; определение: «{n.definiens}»" if n.definiens else ""
            return f"{_cap(self.term(Const('sym', n.term)))}{g}{d}."
        if isinstance(n, Decision):
            because = f" (основание: {', '.join(n.because)})" if n.because else ""
            effect = f"; ожидаемый эффект: {n.expected_effect}" if n.expected_effect else ""
            return f"Решение: {n.action}{because}{effect}."
        if isinstance(n, Event):
            return n.description or n.event_type
        return n.ksf()


def _cap(s: str) -> str:
    return s[:1].upper() + s[1:] if s else s


def paraphrase_nodes(nodes, resolver=None, surface=None, knowledge_type: str | None = None) -> str:
    lab = Labeler(resolver, surface)
    parts = [lab.node(n) for n in nodes]
    text = " ".join(parts)
    if knowledge_type == "PERSONAL_OPINION" and text:
        text = "Я считаю: " + text[:1].lower() + text[1:]
    elif knowledge_type == "PERSONAL_OBSERVATION" and text and not text.startswith("Я "):
        text = "Наблюдение: " + text
    return text


def paraphrase_interpretation(interp, lf, resolver=None) -> str:
    if interp.unresolved:
        return "НЕ РАЗРЕШЕНО: " + "; ".join(u["question"] for u in interp.unresolved)
    if not interp.nodes:
        return "(нет формального представления)"
    return paraphrase_nodes(interp.nodes, resolver, interp.surface, interp.knowledge_type)
