"""Узлы формального IR.

IR — стабильная внутренняя модель: она не знает ни о Markdown, ни об UI, ни о
хранилище. Узел описывает *содержание*; эпистемический статус, provenance и
решения человека живут уровнем выше (``store``), поэтому ID узла зависит только
от содержания: одно и то же утверждение, выраженное разными словами, получает
один и тот же ID.

Префиксы ID:
    A:   утверждение (содержание факта/заявления/гипотезы — ключ в движке вывода)
    F:   факт (принятое человеком утверждение)
    C:   заявление (claim) источника/автора
    H:   гипотеза
    R:   правило            K:   ограничение (denial constraint)
    DEF: определение        EVT: событие
    Q:   вопрос             EV:  свидетельство (аргумент «за/против»)
    DRV: вывод (derivation) DEC: решение
    ENT: сущность           CON: понятие          CTX: контекст
"""
from __future__ import annotations

from dataclasses import dataclass
from functools import cached_property
from typing import ClassVar, Mapping, Union

from ..core.enums import KnowledgeType, Modality
from ..core.errors import ErrorKind, KSError
from ..core.ids import digest, make_id
from .terms import ALWAYS, Atom, Comparison, Interval, Literal, term_from_dict

BodyItem = Union[Literal, Comparison]


def body_from_dict(d: Mapping) -> BodyItem:
    return Comparison.from_dict(d) if "cmp" in d else Literal.from_dict(d)


@dataclass(frozen=True)
class Assertion:
    """Содержание утверждения: атом + полярность + модальность + контекст + интервал."""
    atom: Atom
    negated: bool = False
    modality: Modality = Modality.ASSERTED
    context: str | None = None
    interval: Interval = ALWAYS

    def __post_init__(self) -> None:
        if not self.atom.is_ground():
            raise KSError(ErrorKind.LOGIC_ERROR, f"assertion must be ground: {self.atom.key()}")

    @property
    def literal(self) -> Literal:
        return Literal(self.atom, self.negated)

    def payload(self) -> dict:
        d = {"atom": self.atom.to_dict(), "neg": self.negated, "mod": self.modality.value}
        if self.context:
            d["ctx"] = self.context
        if not self.interval.unbounded:
            d["iv"] = self.interval.to_dict()
        return d

    @cached_property
    def id(self) -> str:
        """A:sha256 от однозначной типизированной кодировки (каждая часть — с префиксом длины)."""
        a = self.atom
        parts = ["A1", a.predicate, str(len(a.args))]
        parts += [f"{t.kind}:{t.value}" for t in a.args]
        parts += ["neg" if self.negated else "pos", self.modality.value, self.context or "",
                  self.interval.start or "", self.interval.end or ""]
        return "A:" + digest(*parts)

    def key(self) -> str:
        parts = [self.literal.key()]
        if not self.interval.unbounded:
            parts.append(self.interval.key())
        if self.context:
            parts.append(f"in {self.context}")
        if self.modality != Modality.ASSERTED:
            parts.append(f"~{self.modality.value}")
        return " ".join(parts)

    def with_(self, **changes) -> "Assertion":
        data = {"atom": self.atom, "negated": self.negated, "modality": self.modality,
                "context": self.context, "interval": self.interval}
        data.update(changes)
        return Assertion(**data)

    @staticmethod
    def from_dict(d: Mapping) -> "Assertion":
        return Assertion(Atom.from_dict(d["atom"]), bool(d.get("neg", False)),
                         Modality(d.get("mod", "asserted")), d.get("ctx"),
                         Interval.from_dict(d.get("iv")))

    def __str__(self) -> str:
        return self.key()


class Node:
    TYPE: ClassVar[str] = ""
    PREFIX: ClassVar[str] = ""

    def payload(self) -> dict:
        raise NotImplementedError

    @property
    def id(self) -> str:
        return make_id(self.PREFIX, self.payload())

    def to_dict(self) -> dict:
        return {"type": self.TYPE, "id": self.id, **self.payload()}

    def ksf(self) -> str:
        from .ksf import format_node
        return format_node(self)


# --------------------------------------------------------------------------- assertions

@dataclass(frozen=True)
class Fact(Node):
    assertion: Assertion
    knowledge_type: KnowledgeType = KnowledgeType.OBJECTIVE_FACT
    TYPE: ClassVar[str] = "fact"
    PREFIX: ClassVar[str] = "F"

    def payload(self) -> dict:
        return {"assertion": self.assertion.payload()}

    @property
    def id(self) -> str:  # F:<hash> == A:<hash> — факт однозначно указывает на своё утверждение
        return "F:" + self.assertion.id.split(":", 1)[1]

    def to_dict(self) -> dict:
        return {**super().to_dict(), "knowledge_type": self.knowledge_type.value}


@dataclass(frozen=True)
class Claim(Node):
    """Утверждение источника. НЕ факт: в авторитетный слой попадает только через человека."""
    assertion: Assertion
    author: str | None = None
    knowledge_type: KnowledgeType = KnowledgeType.EXTERNAL_CLAIM
    TYPE: ClassVar[str] = "claim"
    PREFIX: ClassVar[str] = "C"

    def payload(self) -> dict:
        return {"assertion": self.assertion.payload(), "author": self.author}

    def to_dict(self) -> dict:
        return {**super().to_dict(), "knowledge_type": self.knowledge_type.value}


@dataclass(frozen=True)
class Hypothesis(Node):
    assertion: Assertion
    TYPE: ClassVar[str] = "hypothesis"
    PREFIX: ClassVar[str] = "H"

    def payload(self) -> dict:
        return {"assertion": self.assertion.payload()}

    @property
    def id(self) -> str:
        return "H:" + self.assertion.id.split(":", 1)[1]


# --------------------------------------------------------------------------- rules & constraints

@dataclass(frozen=True)
class Rule(Node):
    """Хорновское правило: head ← body₁ ∧ … ∧ bodyₙ (литералы с сильным отрицанием и сравнения)."""
    head: Literal
    body: tuple
    name: str | None = None
    TYPE: ClassVar[str] = "rule"
    PREFIX: ClassVar[str] = "R"

    def payload(self) -> dict:
        return {"head": self.head.to_dict(), "body": [b.to_dict() for b in self.body]}

    def to_dict(self) -> dict:
        d = super().to_dict()
        if self.name:
            d["name"] = self.name
        return d

    def positive_atoms(self) -> list[Literal]:
        return [b for b in self.body if isinstance(b, Literal)]

    def comparisons(self) -> list[Comparison]:
        return [b for b in self.body if isinstance(b, Comparison)]


@dataclass(frozen=True)
class Constraint(Node):
    """Ограничение-запрет ``:- body``: любое означивание тела — нарушение."""
    body: tuple
    message: str | None = None
    TYPE: ClassVar[str] = "constraint"
    PREFIX: ClassVar[str] = "K"

    def payload(self) -> dict:
        return {"body": [b.to_dict() for b in self.body]}

    def to_dict(self) -> dict:
        d = super().to_dict()
        if self.message:
            d["message"] = self.message
        return d


# --------------------------------------------------------------------------- other semantic units

@dataclass(frozen=True)
class Definition(Node):
    """Определение понятия: термин, род (genus) и определяющая часть (текст и/или литералы)."""
    term: str
    genus: str | None = None
    definiens: str | None = None
    differentia: tuple = ()
    TYPE: ClassVar[str] = "definition"
    PREFIX: ClassVar[str] = "DEF"

    def payload(self) -> dict:
        return {"term": self.term, "genus": self.genus, "definiens": self.definiens,
                "differentia": [lit.to_dict() for lit in self.differentia]}


@dataclass(frozen=True)
class Event(Node):
    event_type: str
    participants: tuple = ()  # ((role, Term), …)
    when: Interval = ALWAYS
    description: str | None = None
    TYPE: ClassVar[str] = "event"
    PREFIX: ClassVar[str] = "EVT"

    def payload(self) -> dict:
        return {"event_type": self.event_type,
                "participants": [[role, t.to_dict()] for role, t in self.participants],
                "when": self.when.to_dict(), "description": self.description}


@dataclass(frozen=True)
class Question(Node):
    """Вопрос: да/нет (все термы связаны) или wh-вопрос (есть переменные)."""
    query: tuple
    TYPE: ClassVar[str] = "question"
    PREFIX: ClassVar[str] = "Q"

    def payload(self) -> dict:
        return {"query": [b.to_dict() for b in self.query]}

    @property
    def variables(self) -> list[str]:
        out: list[str] = []
        for item in self.query:
            for v in sorted(item.variables()):
                if v not in out:
                    out.append(v)
        return out


@dataclass(frozen=True)
class Context(Node):
    key: str
    label: str | None = None
    parent: str | None = None
    TYPE: ClassVar[str] = "context"
    PREFIX: ClassVar[str] = "CTX"

    def payload(self) -> dict:
        return {"key": self.key, "parent": self.parent}

    @property
    def id(self) -> str:
        return f"CTX:{self.key}"


@dataclass(frozen=True)
class Entity(Node):
    key: str
    label: str | None = None
    TYPE: ClassVar[str] = "entity"
    PREFIX: ClassVar[str] = "ENT"

    def payload(self) -> dict:
        return {"key": self.key}

    @property
    def id(self) -> str:
        return f"ENT:{self.key}"


@dataclass(frozen=True)
class Concept(Node):
    key: str
    label: str | None = None
    broader: tuple = ()
    TYPE: ClassVar[str] = "concept"
    PREFIX: ClassVar[str] = "CON"

    def payload(self) -> dict:
        return {"key": self.key, "broader": list(self.broader)}

    @property
    def id(self) -> str:
        return f"CON:{self.key}"


@dataclass(frozen=True)
class Evidence(Node):
    """Явный аргумент человека: ``premises`` поддерживают (support) или атакуют (attack) ``target``."""
    target: str
    stance: str  # "support" | "attack"
    premises: tuple = ()
    note: str | None = None
    TYPE: ClassVar[str] = "evidence"
    PREFIX: ClassVar[str] = "EV"

    def __post_init__(self) -> None:
        if self.stance not in ("support", "attack"):
            raise KSError(ErrorKind.TYPE_ERROR, f"evidence stance must be support|attack: {self.stance}")

    def payload(self) -> dict:
        return {"target": self.target, "stance": self.stance, "premises": list(self.premises),
                "note": self.note}


@dataclass(frozen=True)
class Derivation(Node):
    """Одно обоснование вывода: заключение ← правило(посылки)."""
    conclusion: str
    rule: str
    premises: tuple
    TYPE: ClassVar[str] = "derivation"
    PREFIX: ClassVar[str] = "DRV"

    def payload(self) -> dict:
        return {"conclusion": self.conclusion, "rule": self.rule, "premises": list(self.premises)}

    @property
    def id(self) -> str:
        return "DRV:" + digest("DRV1", self.conclusion, self.rule, *self.premises)


@dataclass(frozen=True)
class Decision(Node):
    """Решение человека. Никогда не становится фактом; помнит, на чём основано."""
    action: str
    because: tuple = ()
    expected_effect: str | None = None
    TYPE: ClassVar[str] = "decision"
    PREFIX: ClassVar[str] = "DEC"

    def payload(self) -> dict:
        return {"action": self.action, "because": list(self.because),
                "expected_effect": self.expected_effect}


NODE_TYPES: dict[str, type] = {cls.TYPE: cls for cls in (
    Fact, Claim, Hypothesis, Rule, Constraint, Definition, Event, Question, Context,
    Entity, Concept, Evidence, Derivation, Decision)}

ASSERTION_NODES = (Fact, Claim, Hypothesis)


def node_from_dict(d: Mapping) -> Node:
    t = d.get("type")
    if t in ("fact", "claim", "hypothesis"):
        a = Assertion.from_dict(d["assertion"])
        if t == "fact":
            return Fact(a, KnowledgeType(d.get("knowledge_type", "OBJECTIVE_FACT")))
        if t == "claim":
            return Claim(a, d.get("author"), KnowledgeType(d.get("knowledge_type", "EXTERNAL_CLAIM")))
        return Hypothesis(a)
    if t == "rule":
        return Rule(Literal.from_dict(d["head"]), tuple(body_from_dict(b) for b in d["body"]), d.get("name"))
    if t == "constraint":
        return Constraint(tuple(body_from_dict(b) for b in d["body"]), d.get("message"))
    if t == "definition":
        return Definition(d["term"], d.get("genus"), d.get("definiens"),
                          tuple(Literal.from_dict(x) for x in d.get("differentia", ())))
    if t == "event":
        return Event(d["event_type"], tuple((r, term_from_dict(x)) for r, x in d.get("participants", ())),
                     Interval.from_dict(d.get("when")), d.get("description"))
    if t == "question":
        return Question(tuple(body_from_dict(b) for b in d["query"]))
    if t == "context":
        return Context(d["key"], d.get("label"), d.get("parent"))
    if t == "entity":
        return Entity(d["key"], d.get("label"))
    if t == "concept":
        return Concept(d["key"], d.get("label"), tuple(d.get("broader", ())))
    if t == "evidence":
        return Evidence(d["target"], d["stance"], tuple(d.get("premises", ())), d.get("note"))
    if t == "derivation":
        return Derivation(d["conclusion"], d["rule"], tuple(d["premises"]))
    if t == "decision":
        return Decision(d["action"], tuple(d.get("because", ())), d.get("expected_effect"))
    raise KSError(ErrorKind.PARSE_ERROR, f"unknown IR node type {t!r}")
