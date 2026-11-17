"""Логическая форма (LF) — выход грамматики, вход семантического построителя.

LF ещё не IR: в ней поверхностные именные группы (NP) с исходными словами,
неразрешённые местоимения и метки выбора прочтения (``reading``). Семантический
слой нормализует NP в ключи, разрешает ссылки (по решениям человека) и строит IR.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class NP:
    kind: str            # name | me | pronoun | var | number | date | string | group | wh
    text: str            # поверхностный текст (для ярлыков и пересказа)
    words: tuple = ()    # слова (нижний регистр) — для ключа
    value: str | None = None  # число/дата (каноническое), имя переменной
    type_words: tuple = ()    # тип переменной, введённой квантором («каждый разработчик»)
    members: tuple = ()       # для group: составляющие NP
    slot: str = ""            # subj | obj | attr | author | context
    unit: str | None = None   # единица измерения при числе

    def key_repr(self) -> str:
        if self.kind in ("var", "wh"):
            return "?" + (self.value or "")
        if self.kind == "group":
            return "{" + "+".join(m.key_repr() for m in self.members) + "}"
        return f"{self.kind}:{self.value or ' '.join(self.words) or self.text}"


@dataclass(frozen=True)
class Clause:
    predicate: str
    args: tuple
    negated: bool = False

    def key_repr(self) -> str:
        return ("¬" if self.negated else "") + f"{self.predicate}(" + ", ".join(a.key_repr() for a in self.args) + ")"


@dataclass(frozen=True)
class LF:
    form: str                       # assertion | rule | definition | directive | decision | question
    clauses: tuple = ()             # утверждения / голова правила / тело вопроса
    conditions: tuple = ()          # тело правила
    modality: str = "asserted"
    hypothesis: bool = False
    author: NP | None = None        # автор заявления
    knowledge_type: str | None = None
    context: NP | None = None
    interval: tuple | None = None   # (start, end) ISO-даты или None
    definition: tuple | None = None  # (term NP, genus NP | None, definiens text | None)
    directive: tuple | None = None  # (name, predicate)
    decision: tuple | None = None   # (action, because LF | None, effect | None)
    reading: tuple = ()             # метки выбора: ("coord=distributive", …)

    def key_repr(self) -> str:
        parts = [self.form, self.modality, "H" if self.hypothesis else "",
                 self.knowledge_type or "", self.author.key_repr() if self.author else "",
                 self.context.key_repr() if self.context else "", str(self.interval or ""),
                 "|".join(c.key_repr() for c in self.conditions), "|".join(c.key_repr() for c in self.clauses),
                 str(self.directive or ""),
                 str((self.definition[0].key_repr(), self.definition[1].key_repr() if self.definition[1] else "",
                      self.definition[2]) if self.definition else ""),
                 str((self.decision[0], self.decision[1].key_repr() if self.decision[1] else "",
                      self.decision[2]) if self.decision else "")]
        return "#".join(parts)


@dataclass(frozen=True)
class ParseOutcome:
    status: str                 # OK | AMBIGUOUS | NO_PARSE
    readings: tuple = ()
    reason: str = ""            # почему NO_PARSE (наблюдаемость)
