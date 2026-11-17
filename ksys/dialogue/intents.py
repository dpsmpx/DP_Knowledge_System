"""Классификация естественно-языковых запросов → семантические операции диалога.

Правила — конечный упорядоченный список регулярных выражений (первое совпадение
побеждает). Классификация наблюдаема: результат содержит имя сработавшего правила.
Если ни одно правило не подходит, вопрос (?) → ASK, утверждение → ASSERT
(только предпросмотр: в базу ничего не пишется без подтверждения).
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from ..core.enums import DialogueOp as Op

ID = r"(?:[A-Z]{1,3}:[0-9a-f]{6,16}|#\d+)"


def _unquote(s: str) -> str:
    for a, b in (("«", "»"), ('"', '"'), ("“", "”")):
        if len(s) > 1 and s.startswith(a) and s.endswith(b) and s.count(a) == 1:
            return s[1:-1].strip()
    return s


def _n(text: str) -> str:
    return " ".join(text.strip().split())


@dataclass(frozen=True)
class Intent:
    op: Op
    rule: str
    args: dict


PATTERNS: list[tuple[str, Op, re.Pattern]] = [
    ("unknowns", Op.UNKNOWNS, re.compile(r"^(?:какие|что)\s+(?:неизвестные|неизвестно)(?:\s+остаются|\s+осталось)?\??$", re.I)),
    ("what_if", Op.WHAT_IF, re.compile(r"^что\s+будет,?\s+если\s+(?:удалить|отозвать|убрать)\s+(?:факт\s+|правило\s+)?(?P<target>.+?)\??$", re.I)),
    ("contradictions_of", Op.FIND_CONTRADICTIONS, re.compile(r"^какие\s+(?:факты|утверждения)\s+противоречат\s+(?P<target>.+?)\??$", re.I)),
    ("contradictions", Op.FIND_CONTRADICTIONS, re.compile(r"^(?:найди|покажи|есть ли)\s+противоречия\??$", re.I)),
    ("explain", Op.EXPLAIN, re.compile(r"^почему\s+(?:ты\s+считаешь|система\s+считает|считается|ты\s+думаешь)?,?\s*(?:что\s+)?(?P<target>.+?)(?:\s+(?:истинным|истинно|верным|верно|правдой))?\??$", re.I)),
    ("trace", Op.TRACE, re.compile(r"^откуда\s+(?:взял(?:ся|ось|ась)|появил(?:ся|ось|ась))\s*(?:этот\s+вывод|вывод|факт)?\s*(?P<target>.*?)\??$", re.I)),
    ("derive", Op.DERIVE, re.compile(r"^что\s+следует\s+из\s+(?P<premises>.+?)\??$", re.I)),
    ("define", Op.DEFINE, re.compile(r"^(?:определи(?:\s+понятие)?|что\s+такое|что\s+значит|дай\s+определение)\s+(?P<term>.+?)\??$", re.I)),
    ("compare", Op.COMPARE, re.compile(r"^(?:сравни|чем\s+отличаются)\s+(?P<x>.+?)\s+и\s+(?P<y>.+?)\??$", re.I)),
    ("generalize", Op.GENERALIZE, re.compile(r"^(?:обобщи|к\s+чему\s+относится|что\s+более\s+общее\s+для|какие\s+классы\s+у)\s+(?P<term>.+?)\??$", re.I)),
    ("specialize", Op.SPECIALIZE, re.compile(r"^(?:какие\s+бывают|приведи\s+примеры|конкретизируй|какие\s+есть)\s+(?P<term>.+?)\??$", re.I)),
    ("hypothesize", Op.HYPOTHESIZE, re.compile(r"^(?:проверь\s+гипотезу|гипотеза)[,:]?\s*(?:что\s+)?(?P<statement>.+?)\.?$", re.I)),
    ("retract", Op.RETRACT, re.compile(r"^(?:удали|отзови|забудь|отмени)\s+(?:факт|правило|утверждение)?\s*(?P<target>.+?)\.?$", re.I)),
    ("revise", Op.REVISE, re.compile(r"^(?:исправление|исправь|поправка)[,:]?\s*(?P<statement>.+?)\.?$", re.I)),
    ("assert", Op.ASSERT, re.compile(r"^(?:добавь(?:\s+(?:факт|правило|утверждение|знание|решение))?|запомни|утверждаю|прими)[,:]?\s*(?P<statement>.+)$", re.I)),
    ("verify", Op.VERIFY, re.compile(r"^(?:а\s+)?(?:правильно|верно|правда|действительно|так)\s+ли,?\s*(?:что\s+)?(?P<statement>.+?)\??$", re.I)),
    ("verify_cmd", Op.VERIFY, re.compile(r"^проверь,?\s*(?:что\s+)?(?P<statement>.+?)\.?$", re.I)),
]


def classify(text: str) -> Intent:
    t = _n(text)
    for name, op, rx in PATTERNS:
        m = rx.match(t)
        if m:
            return Intent(op, name, {k: _unquote(v.strip()) for k, v in m.groupdict().items() if v is not None})
    if t.endswith("?"):
        return Intent(Op.ASK, "question", {"statement": t})
    return Intent(Op.ASSERT, "statement", {"statement": t})


REVISION_RE = re.compile(
    r"^(?P<subject>.+?)\s+(?:было|был|была|были|равно|равен|равна)\s+(?P<new>-?\d+(?:[.,]\d+)?)\s*,?\s*а\s+не\s+(?P<old>-?\d+(?:[.,]\d+)?)$",
    re.I)
