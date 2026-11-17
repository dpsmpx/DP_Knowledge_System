"""Движок гипотез: нормализация → прямые подтверждения/опровержения → правила →
граф аргументов → статус PROVEN | SUPPORTED | CONTESTED | REFUTED | UNKNOWN.

* PROVEN     — есть аргумент «за» из авторитетного знания (IN), нет IN «против»;
* SUPPORTED  — «за» только из заявлений/аргументов человека уровня claim (IN), без IN «против»;
* REFUTED    — IN-аргумент «против», нет IN «за»;
* CONTESTED  — аргументы «за» и «против» взаимно поражают друг друга (UNDEC);
* UNKNOWN    — нет аргументов. Для UNKNOWN показываются недостающие посылки правил,
               из которых гипотеза могла бы следовать. Никакой статистической догадки.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from ..argumentation.grounded import Argument, Framework
from ..core.errors import ErrorKind, KSError
from ..inference.engine import unify
from ..ir.nodes import Assertion, Evidence, Hypothesis
from ..ir.terms import Literal, Var
from ..provenance.proof import ProofBuilder, render, short, trace_sources
from .answers import support


@dataclass
class HypothesisResult:
    hypothesis: str
    status: str
    answer: str
    supporting: list = field(default_factory=list)
    counter: list = field(default_factory=list)
    conflicts: list = field(default_factory=list)
    missing_premises: list = field(default_factory=list)
    proofs: list = field(default_factory=list)
    notes: list = field(default_factory=list)

    def to_dict(self) -> dict:
        return dict(self.__dict__)


ANSWERS = {"PROVEN": "Доказано из принятого знания.",
           "SUPPORTED": "Поддержано заявлениями/аргументами, но не доказано из принятого знания.",
           "CONTESTED": "Спорно: есть равносильные аргументы за и против.",
           "REFUTED": "Опровергнуто: принятое знание (или неопровергнутые аргументы) говорят обратное.",
           "UNKNOWN": "Неизвестно: нет ни подтверждений, ни опровержений (это не «ложь»)."}


class HypothesisEngine:
    def __init__(self, kb):
        self.kb = kb

    def _items_for(self, eng, lit: Literal, h: Assertion) -> tuple[list, list]:
        full_cover, partial = [], []
        for aid, _ in support(eng, lit, None, None):
            a = eng.items[aid]
            ctx_ok = h.context is None or a.context is None or a.context == h.context
            if not ctx_ok:
                continue
            if a.interval.covers(h.interval):
                full_cover.append(aid)
            elif a.interval.intersect(h.interval) is not None:
                partial.append(aid)
        return full_cover, partial

    def evaluate_assertion(self, h: Assertion, hid: str | None = None) -> HypothesisResult:
        kb = self.kb
        hid = hid or Hypothesis(h).id
        lit = h.literal
        fw = Framework()
        res = HypothesisResult(h.key(), "UNKNOWN", "")
        for stance, l in (("pro", lit), ("con", lit.complement())):
            auth_items, auth_partial = self._items_for(kb.auth, l, h)
            full_items, full_partial = self._items_for(kb.full, l, h)
            for aid in sorted(set(full_items)):
                strength = "auth" if aid in auth_items else "claim"
                eng = kb.auth if strength == "auth" else kb.full
                basis = tuple(trace_sources(eng, kb.state, aid)["base_facts"])
                fw.add(Argument(f"{stance}:{aid}", l.key(), stance, strength, basis, aid))
            for aid in sorted(set(auth_partial) | set(full_partial)):
                a = (kb.auth.items.get(aid) or kb.full.items[aid])
                res.notes.append(f"частичная временная поддержка ({stance}): {a.key()} — интервал не покрывает гипотезу")
        for rec in kb.state.authoritative("evidence"):
            ev: Evidence = rec.node
            if ev.target not in (hid, "A:" + hid.split(":", 1)[1], "F:" + hid.split(":", 1)[1]):
                continue
            strength = "auth" if all(kb.state.records.get(p) is not None and kb.state.records[p].authoritative
                                     for p in ev.premises) else "claim"
            fw.add(Argument(f"ev:{rec.id}", lit.key(), "pro" if ev.stance == "support" else "con", strength,
                            tuple(ev.premises), rec.id))
        args = list(fw.arguments.values())
        for a in args:
            for b in args:
                if a.stance != b.stance:
                    fw.attack(a.id, b.id)  # опровержение (rebuttal) — симметрично, решают предпочтения
        labels = fw.grounded()
        pro_in = [a for a in fw.arguments.values() if a.stance == "pro" and a.label == "IN"]
        con_in = [a for a in fw.arguments.values() if a.stance == "con" and a.label == "IN"]
        undec = [a for a in fw.arguments.values() if a.label == "UNDEC"]
        if pro_in and not con_in:
            # PROVEN — только логический вывод из принятого знания; аргумент человека «за» — поддержка
            res.status = "PROVEN" if any(a.strength == "auth" and a.id.startswith("pro:") for a in pro_in) \
                else "SUPPORTED"
        elif con_in and not pro_in:
            res.status = "REFUTED"
        elif undec or (pro_in and con_in):
            res.status = "CONTESTED"
        else:
            res.status = "UNKNOWN"
        for a in sorted(fw.arguments.values(), key=lambda x: x.id):
            entry = {"argument": a.id, "label": a.label, "strength": a.strength, "basis": list(a.basis)}
            if a.origin.startswith("A:"):
                eng = kb.auth if a.origin in kb.auth.items else kb.full
                entry["ksf"] = eng.items[a.origin].key()
                entry["id"] = short(a.origin, eng.is_base(a.origin))
                if len(res.proofs) < 10:
                    res.proofs.append(render(ProofBuilder(eng, kb.state).build(a.origin)))
            (res.supporting if a.stance == "pro" else res.counter).append(entry)
        if res.supporting and res.counter:
            res.conflicts = [{"pro": p["argument"], "con": c["argument"]} for p in res.supporting for c in res.counter]
        if res.status == "UNKNOWN":
            res.missing_premises = self.missing_premises(lit)
        res.answer = ANSWERS[res.status]
        return res

    def missing_premises(self, lit: Literal, limit: int = 20) -> list:
        """Из каких правил гипотеза могла бы следовать и каких посылок для этого не хватает."""
        out = []
        for rid, rule in sorted(self.kb.auth.rules.items()):
            if rule.head.negated != lit.negated or rule.head.atom.predicate != lit.atom.predicate:
                continue
            b = unify(rule.head, Assertion(lit.atom, lit.negated), {}) if lit.atom.is_ground() else None
            if b is None:
                continue
            missing = []
            for body in rule.positive_atoms():
                inst = body.substitute(b)
                if inst.atom.is_ground():
                    if not support(self.kb.auth, inst):
                        missing.append(inst.key())
                else:
                    if not self.kb.auth.match(inst):
                        missing.append(inst.key() + "  (для какого-либо значения переменных)")
            out.append({"rule": rid, "rule_ksf": rule.ksf(), "missing": missing})
            if len(out) >= limit:
                break
        return out

    def evaluate_text(self, text: str) -> HypothesisResult:
        pv = self.kb.preview(text)
        f = pv.formalization
        if f.status != "FORMALIZED":
            raise KSError(ErrorKind.SEMANTIC_AMBIGUITY if f.status == "AMBIGUOUS" else ErrorKind.PARSE_ERROR,
                          f"гипотеза не формализована ({f.status}): {f.reason}")
        nodes = [n for n in f.selected.nodes if hasattr(n, "assertion")]
        if len(nodes) != 1:
            raise KSError(ErrorKind.QUERY_ERROR, "гипотеза должна быть одним утверждением")
        return self.evaluate_assertion(nodes[0].assertion)
