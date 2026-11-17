"""Структурная (схемо-независимая) проверка корректности IR.

Проверки уровня «правильно ли построено выражение», а не «согласуется ли оно со
схемой предметной области» (это — ``validation``):

* безопасность правил: каждая переменная головы и сравнений связана литералом тела;
* непустое тело правила/ограничения;
* согласованность арности предиката в наборе узлов;
* формат ссылок на узлы в решениях и свидетельствах.
"""
from __future__ import annotations

import re
from typing import Iterable

from ..core.errors import Diagnostic, ErrorKind, Severity
from .nodes import (ASSERTION_NODES, Constraint, Decision, Evidence, Node, Question,
                    Rule)
from .terms import Comparison, Literal

NODE_REF_RE = re.compile(r"^(A|F|C|H|R|K|D|Q|DEF|EVT|EV|DRV|DEC):[0-9a-f]{6,64}$")


def _bound_vars(items) -> set[str]:
    out: set[str] = set()
    for b in items:
        if isinstance(b, Literal):
            out |= b.variables()
    return out


def check_node(node: Node) -> list[Diagnostic]:
    diags: list[Diagnostic] = []
    loc = node.id
    if isinstance(node, Rule):
        if not node.body:
            diags.append(Diagnostic(ErrorKind.LOGIC_ERROR, "rule has an empty body", location=loc))
        bound = _bound_vars(node.body)
        free_head = node.head.variables() - bound
        if free_head:
            diags.append(Diagnostic(ErrorKind.LOGIC_ERROR,
                                    f"unsafe rule: head variables {sorted(free_head)} not bound in body",
                                    location=loc))
        for c in node.comparisons():
            free = c.variables() - bound
            if free:
                diags.append(Diagnostic(ErrorKind.LOGIC_ERROR,
                                        f"comparison {c.key()} uses unbound variables {sorted(free)}",
                                        location=loc))
        if not node.positive_atoms():
            diags.append(Diagnostic(ErrorKind.LOGIC_ERROR, "rule body has no atoms", location=loc))
    elif isinstance(node, (Constraint, Question)):
        items = node.body if isinstance(node, Constraint) else node.query
        if not items:
            diags.append(Diagnostic(ErrorKind.LOGIC_ERROR, "empty body", location=loc))
        bound = _bound_vars(items)
        for c in items:
            if isinstance(c, Comparison) and c.variables() - bound:
                diags.append(Diagnostic(ErrorKind.LOGIC_ERROR,
                                        f"comparison {c.key()} uses unbound variables", location=loc))
    elif isinstance(node, Decision):
        for ref in node.because:
            if not NODE_REF_RE.match(ref):
                diags.append(Diagnostic(ErrorKind.PROVENANCE_ERROR, f"bad node reference {ref!r}",
                                        location=loc))
        if not node.action.strip():
            diags.append(Diagnostic(ErrorKind.LOGIC_ERROR, "decision without action", location=loc))
    elif isinstance(node, Evidence):
        for ref in (node.target, *node.premises):
            if not NODE_REF_RE.match(ref):
                diags.append(Diagnostic(ErrorKind.PROVENANCE_ERROR, f"bad node reference {ref!r}",
                                        location=loc))
    return diags


def atoms_of(node: Node):
    if isinstance(node, ASSERTION_NODES):
        yield node.assertion.atom
    elif isinstance(node, Rule):
        yield node.head.atom
        yield from (b.atom for b in node.body if isinstance(b, Literal))
    elif isinstance(node, (Constraint, Question)):
        items = node.body if isinstance(node, Constraint) else node.query
        yield from (b.atom for b in items if isinstance(b, Literal))


def check_arity(nodes: Iterable[Node]) -> list[Diagnostic]:
    seen: dict[str, set[int]] = {}
    for node in nodes:
        for atom in atoms_of(node):
            seen.setdefault(atom.predicate, set()).add(atom.arity)
    return [Diagnostic(ErrorKind.TYPE_ERROR,
                       f"predicate {p} used with different arities {sorted(a)}", Severity.WARNING)
            for p, a in sorted(seen.items()) if len(a) > 1]


def check_all(nodes: Iterable[Node]) -> list[Diagnostic]:
    nodes = list(nodes)
    out: list[Diagnostic] = []
    for n in nodes:
        out.extend(check_node(n))
    out.extend(check_arity(nodes))
    return out
