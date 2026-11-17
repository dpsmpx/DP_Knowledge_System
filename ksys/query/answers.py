"""Ответы на запросы: вердикт Белнапа, противоречия, неизвестные, определения, сравнение.

Структура результата (QueryResult):
    answer, status, evidence (поддержка), contradictions (опровержение), unknowns,
    proof (деревья доказательства), notes.
Открытый мир: отсутствие поддержки → UNKNOWN, а не FALSE. Ложь (DISPROVEN) — только
при наличии явного отрицательного знания.
"""
from __future__ import annotations

import copy
from dataclasses import dataclass, field

from ..core.enums import EpistemicStatus, Verdict
from ..inference.engine import Engine, _BOTTOM, complement_support, meet_context
from ..ir.nodes import Assertion, Fact, Question
from ..ir.terms import SYM, Const, Interval, Literal, Var
from ..provenance.proof import ProofBuilder, render, short


@dataclass
class QueryResult:
    question: str
    status: str
    answer: str
    evidence: list = field(default_factory=list)
    contradictions: list = field(default_factory=list)
    unknowns: list = field(default_factory=list)
    proof: list = field(default_factory=list)
    notes: list = field(default_factory=list)
    bindings: list = field(default_factory=list)

    def to_dict(self) -> dict:
        return {"question": self.question, "status": self.status, "answer": self.answer,
                "evidence": self.evidence, "contradictions": self.contradictions, "unknowns": self.unknowns,
                "proof": self.proof, "notes": self.notes, "bindings": self.bindings}


def _ctx_ok(item_ctx, query_ctx) -> bool:
    return query_ctx is None or item_ctx is None or item_ctx == query_ctx


def support(engine: Engine, lit: Literal, at: str | None = None, context: str | None = None) -> list:
    out = []
    for aid, b in engine.match(lit):
        a = engine.items[aid]
        if at is not None and not a.interval.contains(at):
            continue
        if not _ctx_ok(a.context, context):
            continue
        out.append((aid, b))
    return out


def belnap(pos: bool, neg: bool) -> Verdict:
    if pos and neg:
        return Verdict.BOTH
    if pos:
        return Verdict.PROVEN
    if neg:
        return Verdict.DISPROVEN
    return Verdict.UNKNOWN


def belnap_and(vs) -> Verdict:
    """Конъюнкция в FOUR: F доминирует; иначе N и B → N/B; иначе T."""
    vs = list(vs)
    if any(v == Verdict.DISPROVEN for v in vs):
        return Verdict.DISPROVEN
    has_n = any(v == Verdict.UNKNOWN for v in vs)
    has_b = any(v == Verdict.BOTH for v in vs)
    if has_n and has_b:
        return Verdict.DISPROVEN  # N ∧ B = F в решётке FOUR
    if has_n:
        return Verdict.UNKNOWN
    if has_b:
        return Verdict.BOTH
    return Verdict.PROVEN


class Answerer:
    def __init__(self, kb):
        self.kb = kb

    # ------------------------------------------------------------ literal status
    def literal_status(self, lit: Literal, at=None, context=None, layer: str = "auth") -> dict:
        eng = self.kb.auth if layer == "auth" else self.kb.full
        pos = support(eng, lit, at, context)
        neg = support(eng, lit.complement(), at, context)
        return {"literal": lit.key(), "verdict": belnap(bool(pos), bool(neg)),
                "support": [aid for aid, _ in pos], "counter": [aid for aid, _ in neg]}

    def evidence_entry(self, aid: str, eng: Engine) -> dict:
        a = eng.items[aid]
        base = eng.is_base(aid)
        e = {"id": short(aid, base), "ksf": a.key(), "kind": "ASSERTED" if base else "DERIVED",
             "paraphrase": self.kb.paraphrase([Fact(a)])}
        if base:
            e["records"] = sorted(eng.base[aid])
        else:
            e["rules"] = sorted({j.rule for j in eng.justifications(aid)})
        if not a.interval.unbounded:
            e["interval"] = a.interval.to_dict()
        if a.context:
            e["context"] = a.context
        return e

    def ask_literals(self, question: str, lits, at=None, context=None, with_proof: bool = True) -> QueryResult:
        """Вопрос да/нет (конъюнкция ground-литералов) или wh-вопрос (с переменными)."""
        lits = list(lits)
        if any(lit.variables() for lit in lits if isinstance(lit, Literal)):
            return self._wh(question, lits, at, context)
        statuses = [self.literal_status(l, at, context) for l in lits]
        verdict = belnap_and(s["verdict"] for s in statuses)
        res = QueryResult(question, verdict.value, "")
        for s in statuses:
            for aid in s["support"]:
                res.evidence.append(self.evidence_entry(aid, self.kb.auth))
                if with_proof:
                    res.proof.append(render(ProofBuilder(self.kb.auth, self.kb.state).build(aid)))
            for aid in s["counter"]:
                res.contradictions.append(self.evidence_entry(aid, self.kb.auth))
                if with_proof:
                    res.proof.append(render(ProofBuilder(self.kb.auth, self.kb.state).build(aid)))
            if s["verdict"] == Verdict.UNKNOWN:
                full = self.literal_status(_lit(s["literal"], lits), at, context, "full")
                if full["support"] or full["counter"]:
                    res.notes.append(f"{s['literal']}: в авторитетном знании нет, но есть заявления: "
                                     f"за {[short(a, True) for a in full['support']]}, "
                                     f"против {[short(a, True) for a in full['counter']]}")
                res.unknowns.append(s["literal"])
        if at is None:
            qualified = [e for e in res.evidence + res.contradictions if "interval" in e]
            if qualified:
                res.notes.append("часть свидетельств ограничена во времени — см. interval; "
                                 "для оценки на дату используйте --at")
        res.answer = self._verdict_text(verdict, res)
        return res

    def _wh(self, question, lits, at, context) -> QueryResult:
        eng = self.kb.auth
        body = [l for l in lits if isinstance(l, Literal)]
        cmps = [l for l in lits if not isinstance(l, Literal)]
        rows = {}
        for binding, chosen in eng._join(body, -1, {}, {}):
            if not all(c.evaluate(binding) for c in cmps):
                continue
            prem = [chosen[i] for i in range(len(chosen))]
            if any(at is not None and not eng.items[p].interval.contains(at) for p in prem):
                continue
            if any(not _ctx_ok(eng.items[p].context, context) for p in prem):
                continue
            key = tuple(sorted((k, v.key()) for k, v in binding.items()))
            rows.setdefault(key, set()).update(prem)
        res = QueryResult(question, "PROVEN" if rows else "UNKNOWN", "")
        for key, prem in sorted(rows.items()):
            res.bindings.append({"binding": dict(key), "evidence": [short(p, eng.is_base(p)) for p in sorted(prem)]})
        res.answer = ("; ".join(", ".join(f"{k}={v}" for k, v in b["binding"].items()) for b in res.bindings)
                      if rows else "Неизвестно: в базе нет подходящих утверждений (это не значит «нет»).")
        return res

    @staticmethod
    def _verdict_text(v: Verdict, res: QueryResult) -> str:
        return {Verdict.PROVEN: "Да — доказано из принятого знания.",
                Verdict.DISPROVEN: "Нет — в базе есть явное опровержение.",
                Verdict.BOTH: "Противоречие: утверждение одновременно поддержано и опровергнуто (CONTESTED).",
                Verdict.UNKNOWN: "Неизвестно: нет ни доказательства, ни опровержения (это не «нет»)."}[v]

    # ------------------------------------------------------------ contradictions
    def contradictions(self, layer: str = "auth") -> list[dict]:
        eng = self.kb.auth if layer == "auth" else self.kb.full
        out = []
        for aid, a in sorted(eng.items.items()):
            if a.negated:
                continue
            for nid in complement_support(eng, a):
                b = eng.items[nid]
                overlap = a.interval.intersect(b.interval)
                out.append({
                    "positive": self.evidence_entry(aid, eng), "negative": self.evidence_entry(nid, eng),
                    "overlap": overlap.to_dict() if overlap else None,
                    "context": meet_context(a.context, b.context),
                    "positive_basis": self._basis(eng, aid), "negative_basis": self._basis(eng, nid),
                    "dependents": sorted(short(x, eng.is_base(x)) for x in (eng.dependents(aid) | eng.dependents(nid))),
                    "layer": layer})
        return out

    def _basis(self, eng, aid) -> list:
        from ..provenance.proof import trace_sources, source_summary
        t = trace_sources(eng, self.kb.state, aid)
        return [source_summary(self.kb.state, r) for r in t["base_facts"]]

    def schema_issues(self) -> list:
        from ..validation.schema import constraint_violations
        diags = self.kb.validator.validate(dict(self.kb.auth.items), self.kb.auth)
        for rec in self.kb.state.authoritative("constraint"):
            diags.extend(constraint_violations(rec.node, self.kb.auth))
        return sorted((d.to_dict() for d in diags), key=lambda d: (d.get("location") or "", d["kind"], d["message"]))

    # ------------------------------------------------------------ unknowns
    def unknowns(self) -> dict:
        st = self.kb.state
        from ..query.hypothesis import HypothesisEngine
        hyp = HypothesisEngine(self.kb)
        open_h = []
        for rec in st.hypotheses():
            r = hyp.evaluate_assertion(rec.node.assertion, rec.id)
            if r.status in ("UNKNOWN", "SUPPORTED", "CONTESTED"):
                open_h.append({"id": rec.id, "ksf": rec.node.ksf(), "status": r.status})
        unrated = [r.id for r in st.authoritative() if r.confidence.value == "unrated"]
        decisions = []
        for rec in st.authoritative("decision"):
            for ref in rec.node.because:
                if ref.startswith("F:") and "A:" + ref[2:] not in self.kb.auth.items:
                    decisions.append({"decision": rec.id, "unsupported_basis": ref})
        incomplete = [d for d in self.schema_issues() if "INCOMPLETE" in d["message"] or d["kind"] == "UNKNOWN_ENTITY"]
        return {"open_hypotheses": open_h, "unsupported_decision_bases": decisions, "incomplete": incomplete,
                "unrated_confidence": unrated, "claims_not_reviewed": [r.id for r in st.claims()],
                "deferred_candidates": sorted(st.deferred)}

    # ------------------------------------------------------------ concept questions
    def define(self, key: str) -> dict:
        st, eng = self.kb.state, self.kb.auth
        defs = [r.node for r in st.authoritative("definition") if r.node.term == key]
        types = sorted({a.atom.args[1].value for a in eng.items.values() if a.atom.predicate == "is_a"
                        and not a.negated and a.atom.args[0] == Const(SYM, key) and a.atom.args[1].kind == SYM})
        not_types = sorted({a.atom.args[1].value for a in eng.items.values() if a.atom.predicate == "is_a"
                            and a.negated and a.atom.args[0] == Const(SYM, key)})
        members = sorted({a.atom.args[0].value for a in eng.items.values() if a.atom.predicate == "is_a"
                          and not a.negated and a.atom.args[1] == Const(SYM, key) and a.atom.args[0].kind == SYM})
        props = sorted({a.key() for a in eng.items.values() if a.atom.args[0] == Const(SYM, key)
                        and a.atom.predicate != "is_a"})
        return {"term": key, "definitions": [{"id": d.id, "genus": d.genus, "definiens": d.definiens} for d in defs],
                "is_a": types, "is_not": not_types, "instances_or_subclasses": members, "statements": props,
                "status": "KNOWN" if defs or types or props else "UNKNOWN"}

    def generalize(self, key: str) -> list:
        return self.define(key)["is_a"]

    def specialize(self, key: str) -> list:
        return self.define(key)["instances_or_subclasses"]

    def compare(self, x: str, y: str) -> dict:
        dx, dy = self.define(x), self.define(y)
        eng = self.kb.auth
        direct = sorted(a.key() for a in eng.items.values() if a.atom.arity >= 2 and
                        {a.atom.args[0], a.atom.args[1]} == {Const(SYM, x), Const(SYM, y)})
        return {"x": x, "y": y, "common_types": sorted(set(dx["is_a"]) & set(dy["is_a"])),
                "only_x": sorted(set(dx["is_a"]) - set(dy["is_a"])), "only_y": sorted(set(dy["is_a"]) - set(dx["is_a"])),
                "direct_relations": direct}

    # ------------------------------------------------------------ hypothetical derivation (не пишет в журнал)
    def derive_from(self, assertions) -> dict:
        eng = copy.deepcopy(self.kb.auth)
        added = set()
        for k, a in enumerate(assertions):
            d = eng.add_base(a, f"assumption:{k}")
            added |= d.added
        assumed = {a.id for a in assertions}
        new = sorted(added - assumed)
        return {"assumptions": [a.key() for a in assertions],
                "consequences": [eng.items[x].key() for x in new],
                "proofs": [render(ProofBuilder(eng, self.kb.state).build(x)) for x in new[:20]]}


def _lit(key: str, lits) -> Literal:
    for l in lits:
        if isinstance(l, Literal) and l.key() == key:
            return l
    raise KeyError(key)
