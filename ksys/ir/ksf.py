"""KSF — Knowledge System Formal: текстовый формальный синтаксис IR (Datalog-подобный).

Пример::

    % факты (по умолчанию fact), заявления и гипотезы
    developer(alex).
    not likes(alex, java).
    claim by ivan: increases(coffee, performance).
    hypothesis causes(stress, insomnia).
    value(alex, age, 30) @[2025-01-01, 2025-06-01] in work ~usually.

    % правила и ограничения (переменные: X, Person или ?x)
    rule r1: uses(X, Y) :- developer(X), works_with(X, Y).
    older(X, Y) :- value(X, age, A), value(Y, age, B), A > B.
    constraint "возраст неотрицателен": :- value(X, age, A), A < 0.
    #transitive part_of.

    ?- uses(alex, X).
    decision "проверить рынок" because F:0123456789abcdef expect "ясность спроса".
    define язык_программирования as формальный_язык "язык для записи программ".

Нормализация символов (ключей сущностей) инъецируется вызывающим слоем, чтобы
формальная запись и контролируемый язык давали один и тот же ключ.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Callable, Iterator

from ..core.enums import KnowledgeType, Modality
from ..core.errors import ErrorKind, KSError
from .nodes import (Assertion, Claim, Constraint, Decision, Definition, Event, Fact,
                    Hypothesis, Node, Question, Rule)
from .terms import (ALWAYS, BOOL, COMPARISON_OPS, DATE, NUM, STR, SYM, Atom,
                    Comparison, Const, Interval, Literal, Var, canonical_number)

SymbolNormalizer = Callable[[str], str]


def default_symbol_normalizer(raw: str) -> str:
    text = unicodedata.normalize("NFC", raw).lower().replace("ё", "е")
    text = re.sub(r"[^\w]+", "_", text, flags=re.UNICODE).strip("_")
    if not text:
        raise KSError(ErrorKind.LEXICAL_ERROR, f"empty symbol from {raw!r}")
    return text


# --------------------------------------------------------------------------- lexer

TOKEN_SPEC = [
    ("WS", r"[ \t\r\n]+"),
    ("COMMENT", r"%[^\n]*"),
    ("STRING", r'"(?:[^"\\]|\\.)*"'),
    ("NODEID", r"(?:A|F|C|H|R|K|D|Q|DEF|EVT|EV|DRV|DEC|J):[0-9a-f]{6,64}"),
    ("DATE", r"\d{4}-\d{2}-\d{2}(?![\w-])"),
    ("NUMBER", r"-?\d+(?:\.\d+)?(?![\w])"),
    ("VAR", r"\?[^\W\d][\w]*|[A-Z][A-Za-z0-9_]*(?![\w])"),
    ("IDENT", r"[^\W][\w]*"),
    ("IMPLIES", r":-"),
    ("QUERY", r"\?-"),
    ("CMP", r"<=|>=|!=|<|>|="),
    ("NEG", r"¬"),
    ("PUNCT", r"[(),.:#@\[\]~]"),
]
TOKEN_RE = re.compile("|".join(f"(?P<{n}>{p})" for n, p in TOKEN_SPEC), re.UNICODE)


@dataclass(frozen=True)
class Token:
    kind: str
    text: str
    pos: int
    line: int


def tokenize(src: str) -> list[Token]:
    out: list[Token] = []
    pos, line = 0, 1
    while pos < len(src):
        m = TOKEN_RE.match(src, pos)
        if not m:
            raise KSError(ErrorKind.LEXICAL_ERROR, f"unexpected character {src[pos]!r}", f"line {line}")
        kind = m.lastgroup
        text = m.group()
        if kind not in ("WS", "COMMENT"):
            out.append(Token(kind, text, pos, line))
        line += text.count("\n")
        pos = m.end()
    out.append(Token("EOF", "", pos, line))
    return out


def unescape(quoted: str) -> str:
    return re.sub(r'\\(["\\])', r"\1", quoted[1:-1])


# --------------------------------------------------------------------------- directives

@dataclass(frozen=True)
class Directive:
    """Директива ``#name a, b.`` — декларативное сокращение для правил/схемы."""
    name: str
    args: tuple

    def key(self) -> str:
        return f"#{self.name} {', '.join(self.args)}."


DIRECTIVES = {"transitive": 1, "symmetric": 1, "inverse": 2, "subclass": 2, "disjoint": 2,
              "functional": 1}


def expand_directive(d: Directive) -> list[Rule]:
    """Детерминированное разворачивание директив в хорновские правила."""
    X, Y, Z = Var("X"), Var("Y"), Var("Z")
    if d.name == "transitive":
        p = d.args[0]
        return [Rule(Literal(Atom(p, (X, Z))), (Literal(Atom(p, (X, Y))), Literal(Atom(p, (Y, Z)))),
                     f"transitive_{p}")]
    if d.name == "symmetric":
        p = d.args[0]
        return [Rule(Literal(Atom(p, (Y, X))), (Literal(Atom(p, (X, Y))),), f"symmetric_{p}")]
    if d.name == "inverse":
        p, q = d.args
        return [Rule(Literal(Atom(q, (Y, X))), (Literal(Atom(p, (X, Y))),), f"inverse_{p}_{q}"),
                Rule(Literal(Atom(p, (Y, X))), (Literal(Atom(q, (X, Y))),), f"inverse_{q}_{p}")]
    if d.name == "subclass":
        a, b = (Const(SYM, x) for x in d.args)
        return [Rule(Literal(Atom("is_a", (X, b))), (Literal(Atom("is_a", (X, a))),),
                     f"subclass_{a.value}_{b.value}")]
    if d.name == "disjoint":
        a, b = (Const(SYM, x) for x in d.args)
        return [Rule(Literal(Atom("is_a", (X, b)), True), (Literal(Atom("is_a", (X, a))),),
                     f"disjoint_{a.value}_{b.value}"),
                Rule(Literal(Atom("is_a", (X, a)), True), (Literal(Atom("is_a", (X, b))),),
                     f"disjoint_{b.value}_{a.value}")]
    return []  # functional — ограничение схемы, а не правило


# --------------------------------------------------------------------------- parser

class Parser:
    def __init__(self, src: str, normalize: SymbolNormalizer = default_symbol_normalizer):
        self.toks = tokenize(src)
        self.i = 0
        self.norm = normalize

    # -- helpers
    @property
    def tok(self) -> Token:
        return self.toks[self.i]

    def peek(self, k: int = 1) -> Token:
        return self.toks[min(self.i + k, len(self.toks) - 1)]

    def error(self, msg: str) -> KSError:
        t = self.tok
        return KSError(ErrorKind.PARSE_ERROR, f"{msg}; got {t.kind} {t.text!r}", f"line {t.line}")

    def accept(self, kind: str, text: str | None = None) -> Token | None:
        t = self.tok
        if t.kind == kind and (text is None or t.text == text):
            self.i += 1
            return t
        return None

    def expect(self, kind: str, text: str | None = None) -> Token:
        t = self.accept(kind, text)
        if t is None:
            raise self.error(f"expected {text or kind}")
        return t

    def at_keyword(self, word: str) -> bool:
        return self.tok.kind == "IDENT" and self.tok.text == word

    # -- terms
    def term(self) -> Const | Var:
        t = self.tok
        if t.kind == "VAR":
            self.i += 1
            return Var(t.text[1:] if t.text.startswith("?") else t.text)
        if t.kind == "STRING":
            self.i += 1
            return Const(STR, unescape(t.text))
        if t.kind == "NUMBER":
            self.i += 1
            return Const(NUM, canonical_number(t.text))
        if t.kind == "DATE":
            self.i += 1
            return Const(DATE, t.text)
        if t.kind == "IDENT":
            self.i += 1
            if t.text in ("true", "false"):
                return Const(BOOL, t.text)
            return Const(SYM, self.norm(t.text))
        raise self.error("expected term")

    def atom(self) -> Atom:
        name = self.expect("IDENT").text
        self.expect("PUNCT", "(")
        args = [self.term()]
        while self.accept("PUNCT", ","):
            args.append(self.term())
        self.expect("PUNCT", ")")
        return Atom(name.lower(), tuple(args))

    def literal(self) -> Literal:
        negated = False
        if self.accept("NEG"):
            negated = True
        elif self.at_keyword("not") and self.peek().kind == "IDENT":
            self.i += 1
            negated = True
        return Literal(self.atom(), negated)

    def body_item(self):
        t = self.tok
        is_atom = t.kind == "IDENT" and self.peek().text == "(" and t.text not in ("not",)
        if t.kind == "NEG" or (t.kind == "IDENT" and t.text == "not" and self.peek().kind == "IDENT") or is_atom:
            return self.literal()
        left = self.term()
        op = self.expect("CMP").text
        if op not in COMPARISON_OPS:
            raise self.error("bad comparison")
        return Comparison(op, left, self.term())

    def body(self) -> tuple:
        items = [self.body_item()]
        while self.accept("PUNCT", ","):
            items.append(self.body_item())
        return tuple(items)

    def interval(self) -> Interval:
        if self.accept("PUNCT", "["):
            start = self.accept("DATE")
            self.expect("PUNCT", ",")
            end = self.accept("DATE")
            self.expect("PUNCT", "]")
            return Interval(start.text if start else None, end.text if end else None)
        d = self.expect("DATE").text
        return Interval(d, d)

    def annotations(self) -> tuple[Interval, str | None, Modality]:
        interval, context, modality = ALWAYS, None, Modality.ASSERTED
        while True:
            if self.accept("PUNCT", "@"):
                interval = self.interval()
            elif self.at_keyword("in"):
                self.i += 1
                context = self.norm(self.expect("IDENT").text)
            elif self.accept("PUNCT", "~"):
                word = self.expect("IDENT").text
                try:
                    modality = Modality(word)
                except ValueError:
                    raise self.error(f"unknown modality {word!r}") from None
            else:
                return interval, context, modality

    # -- statements
    def statement(self):
        t = self.tok
        if self.accept("PUNCT", "#"):
            name = self.expect("IDENT").text
            if name not in DIRECTIVES:
                raise self.error(f"unknown directive #{name}")
            args = [self.norm(self.expect("IDENT").text) if name in ("subclass", "disjoint")
                    else self.expect("IDENT").text.lower()]
            while self.accept("PUNCT", ","):
                args.append(self.norm(self.expect("IDENT").text) if name in ("subclass", "disjoint")
                            else self.expect("IDENT").text.lower())
            if len(args) != DIRECTIVES[name]:
                raise self.error(f"#{name} takes {DIRECTIVES[name]} argument(s)")
            self.expect("PUNCT", ".")
            return Directive(name, tuple(args))
        if self.accept("QUERY"):
            q = Question(self.body())
            self.expect("PUNCT", ".")
            return q
        if self.at_keyword("constraint") or t.kind == "IMPLIES":
            message = None
            if self.at_keyword("constraint"):
                self.i += 1
                s = self.accept("STRING")
                message = unescape(s.text) if s else None
                self.expect("PUNCT", ":")
            self.expect("IMPLIES")
            c = Constraint(self.body(), message)
            self.expect("PUNCT", ".")
            return c
        if self.at_keyword("decision"):
            self.i += 1
            action = unescape(self.expect("STRING").text)
            because: list[str] = []
            effect = None
            if self.at_keyword("because"):
                self.i += 1
                because.append(self.expect("NODEID").text)
                while self.accept("PUNCT", ","):
                    because.append(self.expect("NODEID").text)
            if self.at_keyword("expect"):
                self.i += 1
                effect = unescape(self.expect("STRING").text)
            self.expect("PUNCT", ".")
            return Decision(action, tuple(because), effect)
        if self.at_keyword("define"):
            self.i += 1
            term = self.norm(self.expect("IDENT").text)
            genus = None
            if self.at_keyword("as"):
                self.i += 1
                genus = self.norm(self.expect("IDENT").text)
            s = self.accept("STRING")
            self.expect("PUNCT", ".")
            return Definition(term, genus, unescape(s.text) if s else None)
        if self.at_keyword("event") and self.peek().kind == "IDENT":
            self.i += 1
            etype = self.expect("IDENT").text.lower()
            self.expect("PUNCT", "(")
            parts = []
            while True:
                role = self.expect("IDENT").text.lower()
                self.expect("PUNCT", ":")
                parts.append((role, self.term()))
                if not self.accept("PUNCT", ","):
                    break
            self.expect("PUNCT", ")")
            when = self.interval() if self.accept("PUNCT", "@") else ALWAYS
            s = self.accept("STRING")
            self.expect("PUNCT", ".")
            return Event(etype, tuple(parts), when, unescape(s.text) if s else None)
        # rules and assertions
        name = None
        if self.at_keyword("rule") and self.peek().kind == "IDENT" and self.peek(2).text == ":":
            self.i += 1
            name = self.expect("IDENT").text
            self.expect("PUNCT", ":")
        kind, author = "fact", None
        if name is None and self.tok.kind == "IDENT" and self.tok.text in ("fact", "claim", "hypothesis") \
                and self.peek().text != "(":
            kind = self.tok.text
            self.i += 1
            if kind == "claim" and self.at_keyword("by"):
                self.i += 1
                a = self.term()
                author = a.value if isinstance(a, Const) else None
            self.accept("PUNCT", ":")
        head = self.literal()
        if self.accept("IMPLIES"):
            if kind != "fact":
                raise self.error("only plain rules may have a body")
            rule = Rule(head, self.body(), name)
            self.expect("PUNCT", ".")
            return rule
        interval, context, modality = self.annotations()
        self.expect("PUNCT", ".")
        if not head.atom.is_ground():
            raise KSError(ErrorKind.LOGIC_ERROR, f"assertion with variables (use a rule): {head.key()}",
                          f"line {t.line}")
        a = Assertion(head.atom, head.negated, modality, context, interval)
        if kind == "claim":
            return Claim(a, author)
        if kind == "hypothesis":
            return Hypothesis(a)
        return Fact(a)

    def program(self) -> list:
        out = []
        while self.tok.kind != "EOF":
            out.append(self.statement())
        return out


def parse_ksf(src: str, normalize: SymbolNormalizer = default_symbol_normalizer) -> list:
    return Parser(src, normalize).program()


def parse_one(src: str, normalize: SymbolNormalizer = default_symbol_normalizer):
    items = parse_ksf(src if src.rstrip().endswith(".") else src.rstrip() + ".", normalize)
    if len(items) != 1:
        raise KSError(ErrorKind.PARSE_ERROR, f"expected exactly one statement, got {len(items)}")
    return items[0]


# --------------------------------------------------------------------------- printer

def quote(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _body(items) -> str:
    return ", ".join(i.key() for i in items)


def format_node(node: Node) -> str:
    if isinstance(node, Fact):
        return node.assertion.key() + "."
    if isinstance(node, Claim):
        by = f" by {node.author}" if node.author else ""
        return f"claim{by}: {node.assertion.key()}."
    if isinstance(node, Hypothesis):
        return f"hypothesis {node.assertion.key()}."
    if isinstance(node, Rule):
        prefix = f"rule {node.name}: " if node.name else ""
        return f"{prefix}{node.head.key()} :- {_body(node.body)}."
    if isinstance(node, Constraint):
        prefix = f"constraint {quote(node.message)}: " if node.message else ""
        return f"{prefix}:- {_body(node.body)}."
    if isinstance(node, Question):
        return f"?- {_body(node.query)}."
    if isinstance(node, Decision):
        because = f" because {', '.join(node.because)}" if node.because else ""
        effect = f" expect {quote(node.expected_effect)}" if node.expected_effect else ""
        return f"decision {quote(node.action)}{because}{effect}."
    if isinstance(node, Definition):
        genus = f" as {node.genus}" if node.genus else ""
        text = f" {quote(node.definiens)}" if node.definiens else ""
        return f"define {node.term}{genus}{text}."
    if isinstance(node, Event):
        parts = ", ".join(f"{r}: {t.key()}" for r, t in node.participants)
        when = f" {node.when.key()}" if not node.when.unbounded else ""
        desc = f" {quote(node.description)}" if node.description else ""
        return f"event {node.event_type}({parts}){when}{desc}."
    from ..core.ids import canonical_json
    return canonical_json(node.to_dict())


def iter_rules(items) -> Iterator[Rule]:
    """Все правила программы, включая развёрнутые директивы, в исходном порядке."""
    for item in items:
        if isinstance(item, Rule):
            yield item
        elif isinstance(item, Directive):
            yield from expand_directive(item)


__all__ = ["parse_ksf", "parse_one", "format_node", "Directive", "expand_directive", "iter_rules",
           "default_symbol_normalizer", "tokenize", "KnowledgeType"]
