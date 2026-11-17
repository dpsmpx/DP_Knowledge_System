"""Термы, атомы, литералы, сравнения и временные интервалы IR.

Все объекты неизменяемы (frozen) и имеют каноническое строковое представление
``key()``, используемое для сортировки и хеширования — порядок вывода
детерминирован.
"""
from __future__ import annotations

import datetime as _dt
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Mapping, Union

from ..core.errors import ErrorKind, KSError

SYM, STR, NUM, DATE, BOOL = "sym", "str", "num", "date", "bool"
CONST_KINDS = (SYM, STR, NUM, DATE, BOOL)

SYMBOL_RE = re.compile(r"^(?!\d+$)[^\W_][\w]*$|^_[\w]+$", re.UNICODE)
PREDICATE_RE = re.compile(r"^[^\W\d][\w]*$", re.UNICODE)
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def canonical_number(raw: Union[str, int, Decimal]) -> str:
    try:
        d = Decimal(str(raw))
    except InvalidOperation as exc:
        raise KSError(ErrorKind.TYPE_ERROR, f"not a number: {raw!r}") from exc
    if not d.is_finite():
        raise KSError(ErrorKind.TYPE_ERROR, f"non-finite number: {raw!r}")
    text = format(d.normalize(), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def canonical_date(raw: str) -> str:
    if not DATE_RE.match(raw):
        raise KSError(ErrorKind.TYPE_ERROR, f"date must be YYYY-MM-DD: {raw!r}")
    try:
        _dt.date.fromisoformat(raw)
    except ValueError as exc:
        raise KSError(ErrorKind.TYPE_ERROR, f"invalid date: {raw!r}") from exc
    return raw


@dataclass(frozen=True, order=True)
class Const:
    kind: str
    value: str

    def __post_init__(self) -> None:
        if self.kind not in CONST_KINDS:
            raise KSError(ErrorKind.TYPE_ERROR, f"unknown constant kind {self.kind!r}")
        if self.kind == SYM and not SYMBOL_RE.match(self.value):
            raise KSError(ErrorKind.LEXICAL_ERROR, f"invalid symbol {self.value!r}")
        if self.kind == NUM and canonical_number(self.value) != self.value:
            raise KSError(ErrorKind.TYPE_ERROR, f"non-canonical number {self.value!r}")
        if self.kind == DATE:
            canonical_date(self.value)
        if self.kind == BOOL and self.value not in ("true", "false"):
            raise KSError(ErrorKind.TYPE_ERROR, f"invalid bool {self.value!r}")

    is_var = False

    @property
    def number(self) -> Decimal:
        if self.kind != NUM:
            raise KSError(ErrorKind.TYPE_ERROR, f"{self.key()} is not a number")
        return Decimal(self.value)

    def key(self) -> str:
        if self.kind == SYM:
            return self.value
        if self.kind == STR:
            return '"' + self.value.replace("\\", "\\\\").replace('"', '\\"') + '"'
        return self.value

    def to_dict(self) -> dict:
        return {"k": self.kind, "v": self.value}

    def __str__(self) -> str:
        return self.key()


@dataclass(frozen=True, order=True)
class Var:
    name: str
    is_var = True

    def key(self) -> str:
        return "?" + self.name

    def to_dict(self) -> dict:
        return {"var": self.name}

    def __str__(self) -> str:
        return self.key()


Term = Union[Const, Var]


def sym(value: str) -> Const:
    return Const(SYM, value)


def string(value: str) -> Const:
    return Const(STR, value)


def num(value: Union[str, int, Decimal]) -> Const:
    return Const(NUM, canonical_number(value))


def date(value: str) -> Const:
    return Const(DATE, canonical_date(value))


def term_from_dict(d: Mapping) -> Term:
    if "var" in d:
        return Var(d["var"])
    return Const(d["k"], d["v"])


Binding = Mapping[str, Const]


def substitute(term: Term, binding: Binding) -> Term:
    if isinstance(term, Var):
        return binding.get(term.name, term)
    return term


@dataclass(frozen=True, order=True)
class Atom:
    predicate: str
    args: tuple

    def __post_init__(self) -> None:
        if not PREDICATE_RE.match(self.predicate):
            raise KSError(ErrorKind.LEXICAL_ERROR, f"invalid predicate {self.predicate!r}")
        if not isinstance(self.args, tuple) or not self.args:
            raise KSError(ErrorKind.TYPE_ERROR, f"atom {self.predicate} needs a non-empty tuple of args")

    @property
    def arity(self) -> int:
        return len(self.args)

    @property
    def signature(self) -> str:
        return f"{self.predicate}/{self.arity}"

    def variables(self) -> set[str]:
        return {a.name for a in self.args if isinstance(a, Var)}

    def is_ground(self) -> bool:
        return not self.variables()

    def substitute(self, binding: Binding) -> "Atom":
        return Atom(self.predicate, tuple(substitute(a, binding) for a in self.args))

    def key(self) -> str:
        return f"{self.predicate}({', '.join(a.key() for a in self.args)})"

    def to_dict(self) -> dict:
        return {"p": self.predicate, "args": [a.to_dict() for a in self.args]}

    @staticmethod
    def from_dict(d: Mapping) -> "Atom":
        return Atom(d["p"], tuple(term_from_dict(a) for a in d["args"]))

    def __str__(self) -> str:
        return self.key()


@dataclass(frozen=True, order=True)
class Literal:
    """Атом с сильным отрицанием (¬p — явное знание «не p», а не «p неизвестно»)."""
    atom: Atom
    negated: bool = False

    def variables(self) -> set[str]:
        return self.atom.variables()

    def substitute(self, binding: Binding) -> "Literal":
        return Literal(self.atom.substitute(binding), self.negated)

    def complement(self) -> "Literal":
        return Literal(self.atom, not self.negated)

    def key(self) -> str:
        return ("not " if self.negated else "") + self.atom.key()

    def to_dict(self) -> dict:
        return {"atom": self.atom.to_dict(), "neg": self.negated}

    @staticmethod
    def from_dict(d: Mapping) -> "Literal":
        return Literal(Atom.from_dict(d["atom"]), bool(d.get("neg", False)))

    def __str__(self) -> str:
        return self.key()


COMPARISON_OPS = ("<", "<=", ">", ">=", "=", "!=")


@dataclass(frozen=True, order=True)
class Comparison:
    """Встроенное сравнение в теле правила (обе стороны должны быть связаны)."""
    op: str
    left: Term
    right: Term

    def __post_init__(self) -> None:
        if self.op not in COMPARISON_OPS:
            raise KSError(ErrorKind.PARSE_ERROR, f"unknown comparison {self.op!r}")

    def variables(self) -> set[str]:
        return {t.name for t in (self.left, self.right) if isinstance(t, Var)}

    def substitute(self, binding: Binding) -> "Comparison":
        return Comparison(self.op, substitute(self.left, binding), substitute(self.right, binding))

    def evaluate(self, binding: Binding) -> bool:
        left, right = substitute(self.left, binding), substitute(self.right, binding)
        if isinstance(left, Var) or isinstance(right, Var):
            raise KSError(ErrorKind.LOGIC_ERROR, f"unbound variable in comparison {self.key()}")
        if self.op in ("=", "!="):
            equal = left == right
            if left.kind == NUM and right.kind == NUM:
                equal = left.number == right.number
            return equal if self.op == "=" else not equal
        if left.kind != right.kind or left.kind not in (NUM, DATE, STR):
            return False  # несравнимые типы не дают совпадения (а не исключение «всё истинно»)
        a, b = (left.number, right.number) if left.kind == NUM else (left.value, right.value)
        return {"<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b}[self.op]

    def key(self) -> str:
        return f"{self.left.key()} {self.op} {self.right.key()}"

    def to_dict(self) -> dict:
        return {"cmp": self.op, "l": self.left.to_dict(), "r": self.right.to_dict()}

    @staticmethod
    def from_dict(d: Mapping) -> "Comparison":
        return Comparison(d["cmp"], term_from_dict(d["l"]), term_from_dict(d["r"]))

    def __str__(self) -> str:
        return self.key()


@dataclass(frozen=True, order=True)
class Interval:
    """Замкнутый интервал дат [start, end]; None — неограниченная сторона."""
    start: str | None = None
    end: str | None = None

    def __post_init__(self) -> None:
        if self.start is not None:
            canonical_date(self.start)
        if self.end is not None:
            canonical_date(self.end)
        if self.start and self.end and self.start > self.end:
            raise KSError(ErrorKind.TYPE_ERROR, f"interval start {self.start} is after end {self.end}")

    @property
    def unbounded(self) -> bool:
        return self.start is None and self.end is None

    def intersect(self, other: "Interval") -> "Interval | None":
        start = max(filter(None, (self.start, other.start)), default=None)
        end = min(filter(None, (self.end, other.end)), default=None)
        if start and end and start > end:
            return None
        return Interval(start, end)

    def contains(self, day: str) -> bool:
        return (self.start is None or self.start <= day) and (self.end is None or day <= self.end)

    def covers(self, other: "Interval") -> bool:
        lo = self.start is None or (other.start is not None and self.start <= other.start)
        hi = self.end is None or (other.end is not None and other.end <= self.end)
        return lo and hi

    def key(self) -> str:
        if self.unbounded:
            return ""
        if self.start == self.end:
            return f"@{self.start}"
        return f"@[{self.start or ''}, {self.end or ''}]"

    def to_dict(self) -> dict:
        return {"from": self.start, "to": self.end}

    @staticmethod
    def from_dict(d: Mapping | None) -> "Interval":
        if not d:
            return ALWAYS
        return Interval(d.get("from"), d.get("to"))

    def __str__(self) -> str:
        return self.key() or "always"


ALWAYS = Interval()
