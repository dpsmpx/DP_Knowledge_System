"""Диалоговая сессия: операция → вызовы ядра → структурированный ответ.

UI-слой (CLI) только печатает Response. Логики вывода здесь нет: всё делегируется
KnowledgeBase / Answerer / HypothesisEngine. Запись в журнал — только при ``yes=True``.
Ответ всегда содержит: ANSWER, STATUS и, где применимо, EVIDENCE, DERIVATION,
CONTRADICTIONS, UNKNOWNS.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field

from ..core.enums import DialogueOp as Op
from ..core.errors import ErrorKind, KSError
from ..ir.nodes import Assertion, Fact, Question
from ..ir.terms import NUM, Atom, Literal, num
from ..provenance.proof import ProofBuilder, render, trace_sources
from ..inference.engine import complement_support
from ..query.answers import Answerer
from ..query.hypothesis import HypothesisEngine
from ..semantics.normalize import entity_key
from .intents import REVISION_RE, classify

NODE_ID_RE = re.compile(r"^(?:[A-Z]{1,3}):[0-9a-f]{6,16}$")


@dataclass
class Response:
    op: str
    rule: str
    status: str
    answer: str
    sections: dict = field(default_factory=dict)
    committed: bool = False

    def to_dict(self) -> dict:
        return {"op": self.op, "rule": self.rule, "status": self.status, "answer": self.answer,
                "sections": self.sections, "committed": self.committed}

    def render(self) -> str:
        out = [f"OPERATION: {self.op} ({self.rule})", f"STATUS:    {self.status}", f"ANSWER:    {self.answer}"]
        for name, value in self.sections.items():
            if value in (None, [], {}, "") or name.endswith("_json"):
                continue
            out.append(f"\n{name.upper()}:")
            if isinstance(value, str):
                out.append(value)
            elif isinstance(value, list):
                for v in value:
                    out.append(v if isinstance(v, str) else "  " + json.dumps(v, ensure_ascii=False))
            else:
                out.append(json.dumps(value, ensure_ascii=False, indent=2))
        return "\n".join(out)


class Dialogue:
    def __init__(self, kb):
        self.kb = kb
        self.ans = Answerer(kb)
        self.hyp = HypothesisEngine(kb)
        self.last_ids: list[str] = []

    # ------------------------------------------------------------ entry
    def handle(self, text: str, yes: bool = False, at: str | None = None, reading: str | None = None) -> Response:
        intent = classify(text)
        handler = getattr(self, "_" + intent.op.value.lower())
        try:
            return handler(intent, yes=yes, at=at, reading=reading)
        except KSError as e:
            return Response(intent.op.value, intent.rule, e.kind.value, e.message)

    # ------------------------------------------------------------ helpers
    def _resolve_target(self, target: str) -> str | None:
        target = target.strip().rstrip("?.!")
        if target.lower() in ("этот вывод", "это", "этот факт", "его", "") and self.last_ids:
            return self.last_ids[0]
        if NODE_ID_RE.match(target) or target.startswith("#"):
            return target
        pv = self.kb.preview(target)
        if pv.formalization.status != "FORMALIZED":
            raise KSError(ErrorKind.PARSE_ERROR, f"не удалось формализовать «{target}»: {pv.formalization.reason or pv.formalization.status}")
        node = pv.formalization.selected.nodes[0]
        return node.assertion.id if hasattr(node, "assertion") else node.id

    def _aid(self, ref: str) -> str:
        if ref.startswith(("A:", "D:", "F:", "H:", "C:")):
            return "A:" + ref.split(":", 1)[1]
        return ref

    def _preview_section(self, pv) -> dict:
        d = pv.to_dict()
        return {"original": d["original"], "status": d["status"], "reason": d["reason"],
                "options": [{"reading_id": o["reading_id"], "labels": o["labels"], "formal": o["ksf"],
                             "canonical": o["paraphrase"], "unresolved": o["unresolved"], "notes": o["notes"],
                             "diagnostics": o["diagnostics"], "conflicts": o["conflicts"], "known": o["known"],
                             "rejected_before": o["rejected_before"]} for o in d["options"]]}

    def _query_literals(self, statement: str):
        pv = self.kb.preview(statement if statement.endswith("?") else statement)
        f = pv.formalization
        if f.status != "FORMALIZED":
            raise KSError(ErrorKind.SEMANTIC_AMBIGUITY if f.status == "AMBIGUOUS" else
                          (ErrorKind.UNRESOLVED_REFERENCE if f.status == "UNRESOLVED" else ErrorKind.PARSE_ERROR),
                          f"запрос не формализован ({f.status}): {f.reason}; варианты: " +
                          " | ".join(o["paraphrase"] for o in pv.options))
        lits = []
        for n in f.selected.nodes:
            if isinstance(n, Question):
                lits.extend(n.query)
            elif hasattr(n, "assertion"):
                lits.append(n.assertion.literal)
        if not lits:
            raise KSError(ErrorKind.QUERY_ERROR, "в запросе нет проверяемого утверждения")
        return lits

    # ------------------------------------------------------------ operations
    def _assert(self, intent, yes=False, at=None, reading=None) -> Response:
        st = intent.args["statement"]
        pv = self.kb.preview(st)
        sec = {"preview": self._preview_section(pv),
               "actions": ["ACCEPT: --yes [--reading RD:…] [--as fact|claim|hypothesis] [--confidence high|medium|low]",
                           "EDIT: assert \"<KSF>\" --yes", "REJECT: review reject <candidate|node>",
                           "MARK AS CLAIM/HYPOTHESIS: --as claim|hypothesis", "MARK AS UNKNOWN: review defer"]}
        if not yes:
            return Response(Op.ASSERT.value, intent.rule, f"PREVIEW_{pv.formalization.status}",
                            "Предпросмотр. В базу ничего не записано; подтвердите явно.", sec)
        res = self.kb.accept_text(st, reading=reading)
        ids = [e.data["node"]["id"] for e in res.entries]
        self.last_ids = ids
        sec["committed"] = [e.id for e in res.entries]
        sec["derived_added"] = [self.kb.auth.items[a].key() for a in sorted(res.auth.added) if a in self.kb.auth.items
                                and not self.kb.auth.is_base(a)]
        return Response(Op.ASSERT.value, intent.rule, "COMMITTED", f"Принято: {', '.join(ids)}", sec, True)

    def _ask(self, intent, yes=False, at=None, reading=None) -> Response:
        lits = self._query_literals(intent.args["statement"])
        r = self.ans.ask_literals(intent.args["statement"], lits, at=at)
        self.last_ids = [e["id"] for e in r.evidence]
        return Response(Op.ASK.value, intent.rule, r.status, r.answer, {
            "evidence": r.evidence, "derivation": r.proof, "contradictions": r.contradictions,
            "unknowns": r.unknowns, "notes": r.notes, "bindings": r.bindings})

    def _verify(self, intent, yes=False, at=None, reading=None) -> Response:
        return self._hypothesize(intent, yes=False, at=at, reading=reading, op=Op.VERIFY)

    def _hypothesize(self, intent, yes=False, at=None, reading=None, op=Op.HYPOTHESIZE) -> Response:
        st = intent.args["statement"]
        h = self.hyp.evaluate_text(st)
        sec = {"evidence": h.supporting, "counter_evidence": h.counter, "conflicts": h.conflicts,
               "derivation": h.proofs, "unknowns": h.missing_premises, "notes": h.notes}
        committed = False
        if yes and op == Op.HYPOTHESIZE:
            res = self.kb.accept_text(st, role="hypothesis", reading=reading)
            sec["saved_hypothesis"] = [e.data["node"]["id"] for e in res.entries]
            committed = True
        self.last_ids = [e["id"] for e in h.supporting if "id" in e]
        return Response(op.value, intent.rule, h.status, h.answer, sec, committed)

    def _explain(self, intent, yes=False, at=None, reading=None) -> Response:
        ref = self._resolve_target(intent.args.get("target", ""))
        if ref and ref.startswith(("R:", "DEC:", "DEF:", "K:", "C:", "H:")) and ref in self.kb.state.records:
            rec = self.kb.state.records[ref]
            return Response(Op.EXPLAIN.value, intent.rule, rec.status.value,
                            f"{ref} — запись ({rec.role}); основание — решения человека и источники ниже.",
                            {"record": rec.to_dict()})
        aid = self._aid(self.kb.resolve_id(ref) if ref.startswith("#") else ref)
        if aid not in self.kb.auth.items:
            neg = []
            if aid in self.kb.full.items:
                return Response(Op.EXPLAIN.value, intent.rule, "NO_PROOF",
                                "В авторитетном знании это не доказано; есть только заявления (см. ниже).",
                                {"derivation": [render(ProofBuilder(self.kb.full, self.kb.state).build(aid))]})
            return Response(Op.EXPLAIN.value, intent.rule, "NO_PROOF",
                            "Система не считает это истинным: доказательства нет (UNKNOWN). Объяснение не выдумывается.", {})
        tree = ProofBuilder(self.kb.auth, self.kb.state).build(aid)
        self.last_ids = [aid]
        contra = [self.kb.auth.items[x].key() for x in complement_support(self.kb.auth, self.kb.auth.items[aid])]
        return Response(Op.EXPLAIN.value, intent.rule, "BOTH" if contra else "PROVEN",
                        f"{tree.id} {tree.ksf}: {'выведено' if tree.kind == 'DERIVED' else 'принято человеком'}; "
                        f"дерево доказательства ниже.",
                        {"derivation": [render(tree)], "contradictions": contra, "proof_json": tree.to_dict()})

    def _trace(self, intent, yes=False, at=None, reading=None) -> Response:
        ref = self._resolve_target(intent.args.get("target", ""))
        if not ref:
            raise KSError(ErrorKind.QUERY_ERROR, "не указан вывод для трассировки")
        aid = self._aid(ref)
        t = trace_sources(self.kb.auth, self.kb.state, aid)
        return Response(Op.TRACE.value, intent.rule, "TRACED",
                        f"{len(t['base_facts'])} принятых фактов, {len(t['rules'])} правил, "
                        f"{len(t['sources'])} исходных записей provenance.", {"trace": t})

    def _retract(self, intent, yes=False, at=None, reading=None) -> Response:
        ref = self._resolve_target(intent.args["target"])
        rid = self.kb.resolve_id(ref if not ref.startswith("A:") else "F:" + ref[2:])
        delta = self.kb.what_if_retract(rid)
        affected = sorted(self.kb.auth.items[a].key() for a in delta.removed if a in self.kb.auth.items)
        if not yes:
            return Response(Op.RETRACT.value, intent.rule, "PREVIEW",
                            f"Отзыв {rid} затронет {len(affected)} утверждений (не записано; подтвердите --yes).",
                            {"would_remove": affected})
        res = self.kb.retract(rid, reason=f"dialogue: {intent.args['target']}")
        return Response(Op.RETRACT.value, intent.rule, "COMMITTED", f"Отозвано {rid}.",
                        {"removed": affected, "decision_impact": res.notes}, True)

    def _revise(self, intent, yes=False, at=None, reading=None) -> Response:
        st = intent.args["statement"]
        m = REVISION_RE.match(st)
        if not m:
            raise KSError(ErrorKind.PARSE_ERROR, "формат исправления: «<атрибут> <владелец> было N, а не M»")
        from ..parser.cnl import CNLParser
        sub = CNLParser(self.kb.lexicon).parse(f"{m.group('subject')} равно {m.group('old')}.")
        if sub.status != "OK":
            raise KSError(ErrorKind.PARSE_ERROR, f"не удалось разобрать «{m.group('subject')}»: {sub.reason}")
        from ..semantics.build import SemanticBuilder
        old_nodes = SemanticBuilder(self.kb.state).build(sub.readings[0], "revise").nodes
        if not old_nodes:
            raise KSError(ErrorKind.UNRESOLVED_REFERENCE, "не удалось определить владельца атрибута")
        old = old_nodes[0]
        if old.id not in self.kb.state.records or not self.kb.state.active(old.id):
            raise KSError(ErrorKind.LOGIC_ERROR, f"в базе нет активного факта {old.ksf()} — исправлять нечего")
        a = old.assertion
        new = Fact(a.with_(atom=Atom(a.atom.predicate, a.atom.args[:-1] + (num(m.group("new").replace(",", ".")),))))
        delta = self.kb.what_if_retract(old.id)
        if not yes:
            return Response(Op.REVISE.value, intent.rule, "PREVIEW",
                            f"{old.ksf()} → {new.ksf()} (не записано; подтвердите --yes).",
                            {"would_invalidate": sorted(self.kb.auth.items[x].key() for x in delta.removed
                                                        if x in self.kb.auth.items)})
        res = self.kb.revise(old.id, new, reason=st)
        return Response(Op.REVISE.value, intent.rule, "COMMITTED", f"{old.id} SUPERSEDED_BY {new.id}",
                        {"invalidated": sorted(res.auth.removed), "rederived": sorted(res.auth.rederived),
                         "added": sorted(res.auth.added), "decision_impact": res.notes}, True)

    def _define(self, intent, yes=False, at=None, reading=None) -> Response:
        key = entity_key(intent.args["term"])
        d = self.ans.define(key)
        txt = "; ".join(filter(None, [
            "; ".join(f"определение: {x['definiens']}" for x in d["definitions"] if x["definiens"]),
            f"является: {', '.join(d['is_a'])}" if d["is_a"] else "",
            f"не является: {', '.join(d['is_not'])}" if d["is_not"] else ""])) or "Неизвестно: определений нет."
        return Response(Op.DEFINE.value, intent.rule, d["status"], txt, {"definition": d})

    def _compare(self, intent, yes=False, at=None, reading=None) -> Response:
        c = self.ans.compare(entity_key(intent.args["x"]), entity_key(intent.args["y"]))
        status = "KNOWN" if c["common_types"] or c["direct_relations"] or c["only_x"] or c["only_y"] else "UNKNOWN"
        return Response(Op.COMPARE.value, intent.rule, status,
                        f"общее: {c['common_types'] or '—'}; прямые отношения: {c['direct_relations'] or '—'}",
                        {"comparison": c})

    def _generalize(self, intent, yes=False, at=None, reading=None) -> Response:
        g = self.ans.generalize(entity_key(intent.args["term"]))
        return Response(Op.GENERALIZE.value, intent.rule, "KNOWN" if g else "UNKNOWN", ", ".join(g) or "Неизвестно.")

    def _specialize(self, intent, yes=False, at=None, reading=None) -> Response:
        s = self.ans.specialize(entity_key(intent.args["term"]))
        return Response(Op.SPECIALIZE.value, intent.rule, "KNOWN" if s else "UNKNOWN", ", ".join(s) or "Неизвестно.")

    def _find_contradictions(self, intent, yes=False, at=None, reading=None) -> Response:
        auth = self.ans.contradictions("auth")
        seen = {(c["positive"]["id"][2:], c["negative"]["id"][2:]) for c in auth}
        allc = auth + [c for c in self.ans.contradictions("full")
                       if (c["positive"]["id"][2:], c["negative"]["id"][2:]) not in seen]
        target = intent.args.get("target")
        if target:
            lits = self._query_literals(target)
            keys = {l.atom.key() for l in lits}
            allc = [c for c in allc if c["positive"]["ksf"].split(" @")[0].split(" in ")[0].split(" ~")[0] in keys
                    or any(k in c["positive"]["ksf"] for k in keys)]
        schema = self.ans.schema_issues() if not target else []
        errors = [d for d in schema if d["severity"] == "error"]
        status = "CONTRADICTIONS_FOUND" if allc or errors else "NONE_FOUND"
        return Response(Op.FIND_CONTRADICTIONS.value, intent.rule, status,
                        f"прямых противоречий: {len(allc)}; нарушений схемы: {len(errors)}",
                        {"contradictions": allc, "schema_violations": errors})

    def _derive(self, intent, yes=False, at=None, reading=None) -> Response:
        prem = intent.args["premises"]
        parts = [p for p in re.split(r"\s*;\s*", prem) if p]
        if len(parts) == 1:
            parts = self._split_premises(prem)
        assertions = []
        for p in parts:
            pv = self.kb.preview(p)
            if pv.formalization.status != "FORMALIZED":
                raise KSError(ErrorKind.PARSE_ERROR, f"посылка «{p}» не формализована: {pv.formalization.reason}")
            assertions += [n.assertion for n in pv.formalization.selected.nodes if hasattr(n, "assertion")]
        d = self.ans.derive_from(assertions)
        return Response(Op.DERIVE.value, intent.rule, "DERIVED" if d["consequences"] else "NOTHING_NEW",
                        f"следствий: {len(d['consequences'])} (гипотетически, в базу не записано)",
                        {"assumptions": d["assumptions"], "consequences": d["consequences"], "derivation": d["proofs"]})

    def _split_premises(self, text: str) -> list:
        """«X и Y»: пробуем разбить по каждому «и» так, чтобы обе части формализовались."""
        words = text.split(" и ")
        if len(words) < 2:
            return [text]
        ok = []
        for k in range(1, len(words)):
            a, b = " и ".join(words[:k]), " и ".join(words[k:])
            if all(self.kb.preview(x).formalization.status == "FORMALIZED" for x in (a, b)):
                ok.append([a, b])
        if len(ok) == 1:
            return ok[0]
        if len(ok) > 1:
            raise KSError(ErrorKind.SEMANTIC_AMBIGUITY, "посылки можно разделить по-разному; разделите их «;»")
        return [text]

    def _unknowns(self, intent, yes=False, at=None, reading=None) -> Response:
        u = self.ans.unknowns()
        n = sum(len(v) for v in u.values())
        return Response(Op.UNKNOWNS.value, intent.rule, "OPEN" if n else "NONE", f"открытых вопросов: {n}",
                        {"unknowns": u})

    def _what_if(self, intent, yes=False, at=None, reading=None) -> Response:
        ref = self._resolve_target(intent.args["target"])
        rid = self.kb.resolve_id(ref if not ref.startswith("A:") else "F:" + ref[2:])
        d = self.kb.what_if_retract(rid)
        removed = sorted(self.kb.auth.items[a].key() for a in d.removed if a in self.kb.auth.items)
        kept = sorted(self.kb.auth.items[a].key() for a in d.rederived if a in self.kb.auth.items)
        return Response(Op.WHAT_IF.value, intent.rule, "SIMULATED",
                        f"будет удалено {len(removed)}, сохранится благодаря другим обоснованиям {len(kept)} "
                        "(симуляция, журнал не изменён)", {"removed": removed, "kept_by_alternative_support": kept})
