"""SHACL-подобная проверка структуры знаний.

Схема (JSON) описывает предикаты и атрибуты:
    predicates: {p: {arity, args: [тип…], domain, range, functional, irreflexive, asymmetric, symmetric}}
    attributes: {атрибут: {datatype, min, max, functional, enum}}      # для value(s, атрибут, v)
    classes:    {класс: {required: [атрибут/предикат…]}}
    disjoint:   [[класс, класс], …]
    constraints: ["constraint \"сообщение\": :- тело.", …]             # пользовательские запреты (KSF)
Типы аргументов: entity | class | number | date | string | literal | any.

Открытый мир: отсутствие сведений — не ошибка. «Обязательное свойство отсутствует»
и «тип сущности неизвестен» — предупреждения (INCOMPLETE/UNKNOWN_ENTITY), а не нарушения.
Нарушение — только при наличии противоречащих сведений (лишнее значение, тип из
непересекающегося класса, значение вне диапазона…).
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from ..core.errors import Diagnostic, ErrorKind, Severity
from ..ir.ksf import parse_ksf
from ..ir.nodes import Assertion, Constraint
from ..ir.terms import DATE, NUM, STR, SYM, Const
from ..semantics.normalize import ksf_symbol

TYPE_OK = {
    "entity": lambda c: c.kind == SYM, "class": lambda c: c.kind == SYM, "number": lambda c: c.kind == NUM,
    "date": lambda c: c.kind == DATE, "string": lambda c: c.kind == STR,
    "literal": lambda c: c.kind in (NUM, DATE, STR), "any": lambda c: True,
}


@dataclass
class Schema:
    predicates: dict = field(default_factory=dict)
    attributes: dict = field(default_factory=dict)
    classes: dict = field(default_factory=dict)
    disjoint: list = field(default_factory=list)
    constraints: list = field(default_factory=list)

    @staticmethod
    def from_dict(d: dict) -> "Schema":
        s = Schema()
        s.merge(d)
        return s

    def merge(self, d: dict) -> "Schema":
        for p, spec in (d.get("predicates") or {}).items():
            self.predicates.setdefault(p, {}).update(spec)
        for a, spec in (d.get("attributes") or {}).items():
            spec = dict(spec)
            if "enum" in spec:
                spec["enum"] = [ksf_symbol(v) if isinstance(v, str) else v for v in spec["enum"]]
            self.attributes.setdefault(ksf_symbol(a), {}).update(spec)
        for c, spec in (d.get("classes") or {}).items():
            spec = dict(spec)
            spec["required"] = [ksf_symbol(x) if x not in self.predicates else x for x in spec.get("required", [])]
            self.classes.setdefault(ksf_symbol(c), {}).update(spec)
        for a, b in d.get("disjoint") or []:
            self.disjoint.append((ksf_symbol(a), ksf_symbol(b)))
        for src in d.get("constraints") or []:
            for item in parse_ksf(src, ksf_symbol):
                if isinstance(item, Constraint):
                    self.constraints.append(item)
        return self

    @staticmethod
    def load(paths) -> "Schema":
        s = Schema()
        for p in paths:
            s.merge(json.loads(Path(p).read_text(encoding="utf-8")))
        return s


def _overlap(a: Assertion, b: Assertion) -> bool:
    ctx_ok = a.context is None or b.context is None or a.context == b.context
    return ctx_ok and a.interval.intersect(b.interval) is not None


class Validator:
    def __init__(self, schema: Schema):
        self.schema = schema

    def types_of(self, entity: Const, items) -> set:
        return {a.atom.args[1].value for a in items
                if a.atom.predicate == "is_a" and not a.negated and a.atom.args[0] == entity
                and a.atom.args[1].kind == SYM}

    def _disjoint(self, c1: str, c2: str) -> bool:
        return (c1, c2) in self.schema.disjoint or (c2, c1) in self.schema.disjoint

    def validate(self, items: dict, engine=None, focus: set | None = None) -> list[Diagnostic]:
        """items: aid → Assertion. focus: проверять только эти aid (кандидаты), но с учётом всех items."""
        out: list[Diagnostic] = []
        sch = self.schema
        all_items = list(items.items())
        check = [(aid, a) for aid, a in all_items if focus is None or aid in focus]
        for aid, a in check:
            p = a.atom.predicate
            spec = sch.predicates.get(p)
            if spec:
                if "arity" in spec and spec["arity"] != a.atom.arity:
                    out.append(Diagnostic(ErrorKind.SCHEMA_ERROR,
                                          f"{p} expects {spec['arity']} arguments, got {a.atom.arity}", location=aid))
                    continue
                for i, (t, arg) in enumerate(zip(spec.get("args", []), a.atom.args)):
                    if not TYPE_OK.get(t, TYPE_OK["any"])(arg):
                        out.append(Diagnostic(ErrorKind.TYPE_ERROR,
                                              f"{p}: argument {i + 1} must be {t}, got {arg.kind} {arg.key()}",
                                              location=aid))
                if not a.negated:
                    for role, idx in (("domain", 0), ("range", 1)):
                        cls = spec.get(role)
                        if cls and idx < a.atom.arity and a.atom.args[idx].kind == SYM:
                            known = self.types_of(a.atom.args[idx], items.values())
                            if any(self._disjoint(k, cls) for k in known):
                                out.append(Diagnostic(ErrorKind.TYPE_ERROR,
                                                      f"{p}: {role} must be {cls}, but {a.atom.args[idx].key()} "
                                                      f"is {sorted(known)}", location=aid))
                            elif cls not in known:
                                out.append(Diagnostic(ErrorKind.UNKNOWN_ENTITY,
                                                      f"{p}: {role} {cls} expected; type of {a.atom.args[idx].key()} unknown",
                                                      Severity.INFO, aid))
                    if spec.get("irreflexive") and a.atom.arity == 2 and a.atom.args[0] == a.atom.args[1]:
                        out.append(Diagnostic(ErrorKind.SCHEMA_ERROR, f"{p} is irreflexive", location=aid))
            if p == "value" and a.atom.arity == 3 and not a.negated:
                attr = a.atom.args[1].value
                aspec = sch.attributes.get(attr)
                if aspec:
                    v = a.atom.args[2]
                    dt = aspec.get("datatype")
                    if dt and not TYPE_OK.get(dt, TYPE_OK["any"])(v):
                        out.append(Diagnostic(ErrorKind.TYPE_ERROR, f"attribute {attr} must be {dt}, got {v.key()}",
                                              location=aid))
                    elif v.kind == NUM:
                        if "min" in aspec and v.number < aspec["min"] or "max" in aspec and v.number > aspec["max"]:
                            out.append(Diagnostic(ErrorKind.SCHEMA_ERROR,
                                                  f"attribute {attr}={v.key()} outside [{aspec.get('min')}, {aspec.get('max')}]",
                                                  location=aid))
                    if "enum" in aspec and v.value not in aspec["enum"]:
                        out.append(Diagnostic(ErrorKind.SCHEMA_ERROR,
                                              f"attribute {attr}={v.key()} not in {aspec['enum']}", location=aid))
        # кардинальность / асимметрия — попарно в группе (предикат, субъект)
        groups: dict = {}
        for aid, a in all_items:
            if a.negated:
                continue
            p = a.atom.predicate
            if p == "value" and a.atom.arity == 3:
                key = (p, a.atom.args[0], a.atom.args[1].value)
                functional = sch.attributes.get(a.atom.args[1].value, {}).get("functional")
            else:
                key = (p, a.atom.args[0], None)
                functional = sch.predicates.get(p, {}).get("functional")
            if functional:
                groups.setdefault(key, []).append((aid, a))
        for key, members in sorted(groups.items(), key=lambda kv: str(kv[0])):
            for i, (aid1, a1) in enumerate(members):
                for aid2, a2 in members[i + 1:]:
                    if a1.atom.args[-1] != a2.atom.args[-1] and _overlap(a1, a2) and \
                            (focus is None or aid1 in focus or aid2 in focus):
                        out.append(Diagnostic(ErrorKind.SCHEMA_ERROR,
                                              f"{key[0]}{'/' + key[2] if key[2] else ''} is functional: "
                                              f"{a1.key()} vs {a2.key()}", location=f"{aid1},{aid2}"))
        for aid, a in check:
            spec = sch.predicates.get(a.atom.predicate, {})
            if spec.get("asymmetric") and a.atom.arity == 2 and not a.negated:
                rev = [x for x, b in all_items if b.atom.predicate == a.atom.predicate and not b.negated
                       and b.atom.args == (a.atom.args[1], a.atom.args[0]) and _overlap(a, b)]
                if rev and aid < rev[0]:
                    out.append(Diagnostic(ErrorKind.SCHEMA_ERROR, f"{a.atom.predicate} is asymmetric",
                                          location=f"{aid},{rev[0]}"))
        # обязательные свойства (открытый мир → предупреждение)
        for cls, cspec in sorted(sch.classes.items()):
            for aid, a in check:
                if a.atom.predicate == "is_a" and not a.negated and a.atom.args[1] == Const(SYM, cls):
                    ent = a.atom.args[0]
                    for req in cspec.get("required", []):
                        present = any(b.atom.args[0] == ent and not b.negated and
                                      (b.atom.predicate == req or (b.atom.predicate == "value" and
                                                                   b.atom.arity == 3 and b.atom.args[1].value == req))
                                      for b in items.values())
                        if not present:
                            out.append(Diagnostic(ErrorKind.SCHEMA_ERROR,
                                                  f"INCOMPLETE: {ent.key()} is {cls} but has no {req} (unknown, not false)",
                                                  Severity.WARNING, aid))
        # пользовательские ограничения-запреты
        if engine is not None:
            for c in self.schema.constraints:
                out.extend(constraint_violations(c, engine, focus))
        return out


def constraint_violations(c: Constraint, engine, focus: set | None = None) -> list[Diagnostic]:
    from ..ir.terms import Literal
    lits = [b for b in c.body if isinstance(b, Literal)]
    cmps = [b for b in c.body if not isinstance(b, Literal)]
    out = []
    seen = set()
    for binding, chosen in engine._join(lits, -1, {}, {}):
        if not all(x.evaluate(binding) for x in cmps):
            continue
        prem = tuple(chosen[i] for i in range(len(chosen)))
        if prem in seen or (focus is not None and not set(prem) & focus):
            continue
        seen.add(prem)
        msg = c.message or c.ksf()
        out.append(Diagnostic(ErrorKind.SCHEMA_ERROR, f"constraint violated: {msg}", location=",".join(prem),
                              details=(("constraint", c.id),)))
    return sorted(out, key=lambda d: (d.location or "", d.message))  # порядок не зависит от обхода множеств
