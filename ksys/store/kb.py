"""KnowledgeBase — фасад ядра: журнал → состояние → вывод → TMS; workflow человека.

Слои вывода (оба — монотонный Datalog с сильным отрицанием):
    auth  — только авторитетное знание: факты и правила, принятые человеком;
    full  — auth + заявления (CLAIMED), записанные человеком как заявления.
            Используется только для статусов SUPPORTED/CONTESTED гипотез и анализа;
            ничего из full не становится знанием без решения человека.

Любое изменение — только через журнал (append-only) и только явным вызовом
accept/reject/defer/retract/revise/split/merge/resolve/bind/label/alias. Перед записью узлы
проверяются структурно (ir.validate) и по схеме (validation.schema).
"""
from __future__ import annotations

import copy
import gc
import json
import pickle
from dataclasses import dataclass, field
from pathlib import Path

from .. import GRAMMAR_VERSION, IR_VERSION, KS_VERSION, PARSER_VERSION
from ..core.clock import Clock
from ..core.enums import EpistemicStatus, KnowledgeType
from ..core.errors import Diagnostic, ErrorKind, KSError, Severity
from ..core.ids import canonical_json, digest
from ..inference.engine import Delta, Engine
from ..ir.nodes import Claim, Constraint, Decision, Fact, Hypothesis, Node, Rule, node_from_dict
from ..ir.validate import check_node
from ..language.lexicon import Lexicon
from ..semantics.formalize import Formalization, Formalizer
from ..semantics.paraphrase import paraphrase_nodes
from ..validation.schema import Schema, Validator, constraint_violations
from .db import connect, set_meta
from .journal import Entry, Journal
from .state import Change, KnowledgeState, Record
from .workspace import Workspace

PACKAGE_SCHEMAS = Path(__file__).resolve().parents[2] / "schemas"
_PKG = Path(__file__).resolve().parents[1]
_CODE_FINGERPRINT = digest(*(p.read_text(encoding="utf-8") for p in sorted(
    list((_PKG / "inference").glob("*.py")) + list((_PKG / "store").glob("*.py")) + list((_PKG / "ir").glob("*.py")))))
ENGINE_CACHE_VERSION = f"{KS_VERSION}/{IR_VERSION}/cache-1/{_CODE_FINGERPRINT}"  # любая правка ядра → кеш недействителен


@dataclass
class Preview:
    """Что человек видит перед решением: ORIGINAL → FORMAL → CANONICAL + проверки."""
    formalization: Formalization
    options: list = field(default_factory=list)   # [{reading_id, labels, nodes, ksf, paraphrase, diagnostics, conflicts, known}]

    def to_dict(self) -> dict:
        f = self.formalization
        return {"original": f.text, "origin": f.origin, "status": f.status, "syntax": f.syntax, "reason": f.reason,
                "options": [{k: v for k, v in o.items() if k != "nodes"} for o in self.options]}


@dataclass
class CommitResult:
    entries: list
    auth: Delta
    full: Delta
    notes: list = field(default_factory=list)


def convert_role(node: Node, role: str | None) -> Node:
    """ACCEPT как факт / MARK AS CLAIM / MARK AS HYPOTHESIS — смена роли утверждения."""
    if role is None or not hasattr(node, "assertion"):
        return node
    a = node.assertion
    kt = getattr(node, "knowledge_type", KnowledgeType.OBJECTIVE_FACT)
    if role == "fact":
        if kt in (KnowledgeType.EXTERNAL_CLAIM, KnowledgeType.HYPOTHESIS):
            kt = KnowledgeType.OBJECTIVE_FACT
        return Fact(a, kt)
    if role == "claim":
        return Claim(a, getattr(node, "author", None), kt if kt != KnowledgeType.OBJECTIVE_FACT else KnowledgeType.EXTERNAL_CLAIM)
    if role == "hypothesis":
        return Hypothesis(a)
    raise KSError(ErrorKind.QUERY_ERROR, f"unknown role {role!r}")


class KnowledgeBase:
    def __init__(self, ws: Workspace, clock: Clock, at: str | None = None, lexicon: Lexicon | None = None,
                 use_cache: bool = True):
        self.ws = ws
        self.clock = clock
        self.journal = Journal(ws.journal_path, clock, actor=ws.config().get("actor", "human:me"))
        self.readonly = at is not None
        entries = self.journal.upto(at)
        self.lexicon = lexicon or Lexicon.load(ws.root / "lexicon.json")
        self._full: Engine | None = None  # слой «+заявления» строится лениво (нужен гипотезам и анализу)
        self.cache_used = False
        gc_was = gc.isenabled()
        gc.disable()  # массовое создание объектов: циклический GC здесь только мешает (×2–3 быстрее)
        try:
            if use_cache and not self.readonly and self._load_cache(entries):
                return
            self.state = KnowledgeState.replay(entries)
            self._load_schema()
            self.auth = Engine()
            self._build_engines()
        finally:
            if gc_was:
                gc.enable()

    # ------------------------------------------------------------------ cache of derived state
    @property
    def cache_path(self) -> Path:
        return self.ws.db_path.parent / "engine.pickle"

    def _schema_fingerprint(self) -> str:
        paths = sorted(self.ws.schemas_dir.glob("*.json")) if self.ws.schemas_dir.exists() else []
        paths = paths or sorted(PACKAGE_SCHEMAS.glob("*.json"))
        return canonical_json([(p.name, p.read_text(encoding="utf-8")) for p in paths])

    def _load_cache(self, entries) -> bool:
        """Производное состояние из кеша (голова журнала совпадает с префиксом) + инкрементальный догон."""
        p = self.cache_path
        if not p.exists():
            return False
        try:
            with p.open("rb") as fh:
                c = pickle.load(fh)
        except (OSError, pickle.UnpicklingError, EOFError, AttributeError, ImportError):
            return False
        if c.get("version") != ENGINE_CACHE_VERSION or c.get("schema") != self._schema_fingerprint():
            return False
        n = c["n_entries"]
        if n > len(entries) or (n and entries[n - 1].id != c["head"]):
            return False  # журнал переписан или усечён — кеш недействителен
        self.state, self.auth = c["state"], c["auth"]
        self._load_schema()
        for e in entries[n:]:  # новые решения — через тот же инкрементальный TMS
            ch = self.state.apply(e)
            if e.op == "schema":
                self._load_schema()
            self._apply_change(ch)
        self.cache_used = True
        return True

    def save_cache(self) -> None:
        if self.readonly:
            return
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.cache_path.with_suffix(".tmp")
        with tmp.open("wb") as fh:
            pickle.dump({"version": ENGINE_CACHE_VERSION, "schema": self._schema_fingerprint(),
                         "head": self.state.head, "n_entries": len(self.state.entries),
                         "state": self.state, "auth": self.auth}, fh, protocol=pickle.HIGHEST_PROTOCOL)
        tmp.replace(self.cache_path)

    # ------------------------------------------------------------------ build
    def _load_schema(self) -> None:
        paths = sorted(self.ws.schemas_dir.glob("*.json")) if self.ws.schemas_dir.exists() else []
        if not paths:
            paths = sorted(PACKAGE_SCHEMAS.glob("*.json"))
        self.schema = Schema.load(paths)
        for ext in self.state.schema_ext:
            self.schema.merge(ext)
        self.validator = Validator(self.schema)

    def _build_engines(self) -> None:
        rules = [(rec.id, rec.node) for rec in self.state.authoritative("rule")]
        facts = [(rec.node.assertion, rec.id) for rec in self.state.authoritative("fact")]
        self.auth.bulk_load(rules, facts)

    @property
    def full(self) -> Engine:
        if self._full is None:
            claims = self.state.claims()
            if not claims:
                self._full = copy.deepcopy(self.auth)
            else:
                self._full = Engine()
                rules = [(rec.id, rec.node) for rec in self.state.authoritative("rule")]
                facts = [(rec.node.assertion, rec.id) for rec in self.state.authoritative("fact")]
                self._full.bulk_load(rules, facts + [(rec.node.assertion, rec.id) for rec in claims])
        return self._full

    def _apply_change(self, ch: Change) -> tuple[Delta, Delta]:
        da, df = Delta(), Delta()
        full = self._full  # если слой ещё не построен — он построится из состояния при первом обращении
        for rid in ch.deactivated:
            rec = self.state.records[rid]
            if isinstance(rec.node, Rule):
                da.merge(self.auth.remove_rule(rid))
                if full is not None:
                    df.merge(full.remove_rule(rid))
            elif isinstance(rec.node, Fact):
                da.merge(self.auth.remove_base(rec.node.assertion.id, rid))
                if full is not None:
                    df.merge(full.remove_base(rec.node.assertion.id, rid))
            elif isinstance(rec.node, Claim) and full is not None:
                df.merge(full.remove_base(rec.node.assertion.id, rid))
        for rid in ch.activated:
            rec = self.state.records[rid]
            if not rec.authoritative and not isinstance(rec.node, Claim):
                continue
            if isinstance(rec.node, Rule):
                da.merge(self.auth.add_rule(rid, rec.node))
                if full is not None:
                    df.merge(full.add_rule(rid, rec.node))
            elif isinstance(rec.node, Fact):
                da.merge(self.auth.add_base(rec.node.assertion, rid))
                if full is not None:
                    df.merge(full.add_base(rec.node.assertion, rid))
            elif isinstance(rec.node, Claim) and full is not None:
                df.merge(full.add_base(rec.node.assertion, rid))
        return da, df

    def _commit(self, op: str, data: dict, note: str | None = None) -> tuple[Entry, Delta, Delta]:
        if self.readonly:
            raise KSError(ErrorKind.JOURNAL_ERROR, "historical snapshot is read-only")
        e = self.journal.append(op, data, note)
        ch = self.state.apply(e)
        if op == "schema":
            self._load_schema()
        da, df = self._apply_change(ch)
        return e, da, df

    # ------------------------------------------------------------------ language
    def formalizer(self, mode: str = "assert") -> Formalizer:
        return Formalizer(self.lexicon, self.state, mode)

    def preview(self, text: str, origin: str | None = None, mode: str = "assert") -> Preview:
        f = self.formalizer(mode).formalize(text, origin)
        pv = Preview(f)
        for interp in f.interpretations:
            diags = list(interp.diagnostics)
            for n in interp.nodes:
                diags.extend(check_node(n))
            diags.extend(self.check_candidate_nodes(interp.nodes))
            pv.options.append({
                "reading_id": interp.reading_id, "labels": list(interp.labels), "nodes": interp.nodes,
                "node_ids": [n.id for n in interp.nodes], "ksf": [n.ksf() for n in interp.nodes],
                "paraphrase": interp.paraphrase, "unresolved": interp.unresolved, "notes": interp.notes,
                "diagnostics": [d.to_dict() for d in diags], "conflicts": self.conflicts_for(interp.nodes),
                "known": [n.id for n in interp.nodes if self.state.active(n.id)],
                "rejected_before": [n.id for n in interp.nodes if n.id in self.state.rejected],
                "knowledge_type": interp.knowledge_type})
        return pv

    def check_candidate_nodes(self, nodes) -> list[Diagnostic]:
        cand = {n.assertion.id: n.assertion for n in nodes if hasattr(n, "assertion")}
        if not cand:
            return []
        items = dict(self.auth.items)
        items.update(cand)
        tmp = Engine.from_items(items)
        diags = self.validator.validate(items, tmp, focus=set(cand))
        for rec in self.state.authoritative("constraint"):
            diags.extend(constraint_violations(rec.node, tmp, set(cand)))
        return diags

    def conflicts_for(self, nodes) -> list[dict]:
        """Противоречило бы ли утверждение уже известному (прямое ¬, пересечение интервала и контекста)."""
        from ..inference.engine import complement_support
        out = []
        for n in nodes:
            if hasattr(n, "assertion"):
                for layer, eng in (("auth", self.auth), ("full", self.full)):
                    for aid in complement_support(eng, n.assertion):
                        out.append({"node": n.id, "conflicts_with": aid, "layer": layer,
                                    "ksf": eng.items[aid].key()})
        return out

    # ------------------------------------------------------------------ workflow (human decisions)
    def accept(self, nodes, *, provenance: dict | None = None, confidence: str | None = None,
               role: str | None = None, candidates=(), override_rejection: bool = False,
               knowledge_type: str | None = None, note: str | None = None, surface: dict | None = None) -> CommitResult:
        nodes = [convert_role(n, role) for n in nodes]
        for n in nodes:
            errs = [d for d in check_node(n) if d.severity == Severity.ERROR]
            if errs:
                raise KSError(errs[0].kind, f"cannot accept {n.id}: {errs[0].message}")
            if n.id in self.state.rejected and not override_rejection:
                raise KSError(ErrorKind.LOGIC_ERROR,
                              f"{n.id} was rejected by human decision {self.state.rejected[n.id]}; "
                              "a new explicit decision (override_rejection) is required")
        res = CommitResult([], Delta(), Delta())
        for n in nodes:
            data = {"node": n.to_dict(), "role": n.TYPE, "provenance": provenance or {"source_type": "human_input"},
                    "confidence": confidence, "candidates": list(candidates)}
            if knowledge_type:
                data["knowledge_type"] = knowledge_type
            if surface:
                data["surface"] = dict(sorted(surface.items()))
            if override_rejection:
                data["override_rejection"] = True
            e, da, df = self._commit("accept", data, note)
            res.entries.append(e)
            res.auth.merge(da)
            res.full.merge(df)
        return res

    def batch_accept_claims(self, items: list[dict], *, note: str | None = None) -> CommitResult:
        """One journal event for 2–8 explicitly reviewed, distinct claims.

        Caller validates live Markdown and selected readings. No individual
        accept entries may be written before the entire batch is validated.
        """
        if not 2 <= len(items) <= 8:
            raise KSError(ErrorKind.LOGIC_ERROR, "batch requires 2..8 claims")
        nodes = [convert_role(item["node"], "claim") for item in items]
        if len({node.id for node in nodes}) != len(nodes):
            raise KSError(ErrorKind.LOGIC_ERROR, "duplicate nodes in batch")
        for node in nodes:
            errors = [d for d in check_node(node) if d.severity == Severity.ERROR]
            if errors or node.id in self.state.rejected or node.id in self.state.records:
                raise KSError(ErrorKind.LOGIC_ERROR, "batch contains invalid/rejected/active claim")
        payload = [
            {"node": node.to_dict(), "role": "claim", "provenance": item["provenance"],
             "confidence": None, "candidates": []}
            for node, item in zip(nodes, items)
        ]
        e, da, df = self._commit("batch_accept", {"items": payload}, note)
        return CommitResult([e], da, df, self._decision_impact(da))

    def accept_text(self, text: str, *, reading: str | None = None, role: str | None = None,
                    confidence: str | None = None, origin: str | None = None, note: str | None = None,
                    override_rejection: bool = False) -> CommitResult:
        """ASSERT: разобрать → (выбрать прочтение) → принять. Неоднозначность без выбора — ошибка."""
        pv = self.preview(text, origin)
        f = pv.formalization
        if f.status == "NO_PARSE":
            raise KSError(ErrorKind.PARSE_ERROR, f"не удалось формализовать: {f.reason}")
        if f.status == "UNRESOLVED":
            raise KSError(ErrorKind.UNRESOLVED_REFERENCE,
                          "; ".join(u["question"] for o in pv.options for u in o["unresolved"]))
        if f.status == "AMBIGUOUS" and reading is None:
            raise KSError(ErrorKind.SEMANTIC_AMBIGUITY, "несколько прочтений: " +
                          " | ".join(f"{o['reading_id']}: {o['paraphrase']}" for o in pv.options))
        opt = pv.options[0] if reading is None else next(
            (o for o in pv.options if o["reading_id"].startswith(reading)), None)
        if opt is None:
            raise KSError(ErrorKind.QUERY_ERROR, f"no reading {reading!r}")
        if f.status == "AMBIGUOUS":
            self._commit("resolve", {"origin": f.origin, "reading_id": opt["reading_id"]})
        prov = {"source_type": "human_input", "text": text, "input_hash": f.input_hash,
                "extraction": {"method": f.syntax, "grammar_version": GRAMMAR_VERSION,
                               "parser_version": PARSER_VERSION, "reading_id": opt["reading_id"]}}
        interp = next(i for i in f.interpretations if i.reading_id == opt["reading_id"])
        return self.accept(opt["nodes"], provenance=prov, confidence=confidence, role=role, note=note,
                           override_rejection=override_rejection, surface=interp.surface)

    def candidate(self, cand_id: str) -> dict:
        con = connect(self.ws.db_path)
        row = con.execute("SELECT c.id, c.origin, c.reading_id, c.nodes, c.paraphrase, f.doc, f.locator, f.start, f.end,"
                          " f.text, f.text_hash, d.sha256 FROM candidates c JOIN fragments f ON f.id=c.origin"
                          " JOIN documents d ON d.path=f.doc WHERE c.id=? OR c.id LIKE ?",
                          (cand_id, cand_id + "%")).fetchall()
        con.close()
        if len(row) != 1:
            raise KSError(ErrorKind.QUERY_ERROR, f"candidate {cand_id!r}: {len(row)} matches")
        (cid, origin, rid, nodes, para, doc, loc, s, e, text, th, sha) = row[0]
        surface = {}
        try:
            surface = self.formalizer("import").formalize(text.strip(), origin).interpretations[0].surface
        except (KSError, IndexError):
            surface = {}
        return {"id": cid, "origin": origin, "reading_id": rid, "nodes": [node_from_dict(n) for n in json.loads(nodes)],
                "surface": surface,
                "paraphrase": para, "provenance": {
                    "source_type": "vault_note", "document": doc, "document_sha": sha, "fragment_id": origin,
                    "locator": loc, "span": [s, e], "text": text, "text_hash": th,
                    "extraction": {"method": "cnl", "grammar_version": GRAMMAR_VERSION,
                                   "parser_version": PARSER_VERSION, "reading_id": rid}}}

    def accept_candidate(self, cand_id: str, *, role: str | None = None, confidence: str | None = None,
                         edited: list | None = None, note: str | None = None, select=None) -> CommitResult:
        """ACCEPT / EDIT / SPLIT (select — подмножество узлов) / MARK AS CLAIM|HYPOTHESIS (role)."""
        c = self.candidate(cand_id)
        prov = dict(c["provenance"])
        nodes = c["nodes"]
        if edited is not None:
            nodes = edited
            prov["extraction"] = dict(prov["extraction"], method="manual_edit", edited_from=c["id"])
        if select is not None:
            nodes = [nodes[i] for i in select]
        return self.accept(nodes, provenance=prov, role=role, confidence=confidence, candidates=[c["id"]],
                           note=note, surface=c.get("surface"))

    def merge_candidates(self, cand_ids: list, nodes: list, *, role=None, confidence=None, note=None) -> CommitResult:
        """MERGE: один узел (или набор) как итог нескольких кандидатов; provenance — все источники."""
        provs = [self.candidate(c)["provenance"] for c in cand_ids]
        prov = dict(provs[0])
        prov["merged_sources"] = provs[1:]
        return self.accept(nodes, provenance=prov, role=role, confidence=confidence, candidates=cand_ids, note=note)

    def reject(self, *, candidates=(), targets=(), reason: str | None = None) -> Entry:
        return self._commit("reject", {"candidates": list(candidates), "targets": list(targets), "reason": reason})[0]

    def defer(self, candidates, reason: str | None = None) -> Entry:
        return self._commit("defer", {"candidates": list(candidates), "status": "UNKNOWN", "reason": reason})[0]

    def resolve_id(self, ref: str) -> str:
        if ref in self.state.records:
            return ref
        matches = [r for r in self.state.records if r.startswith(ref) or r.split(":", 1)[-1].startswith(ref)]
        if len(matches) != 1:
            raise KSError(ErrorKind.QUERY_ERROR, f"reference {ref!r} matches {len(matches)} records")
        return matches[0]

    def retract(self, target: str, reason: str | None = None) -> CommitResult:
        target = self.resolve_id(target)
        if not self.state.active(target):
            raise KSError(ErrorKind.LOGIC_ERROR, f"{target} is not active")
        e, da, df = self._commit("retract", {"target": target, "reason": reason})
        return CommitResult([e], da, df, self._decision_impact(da))

    def revise(self, target: str, new_node: Node, reason: str | None = None, provenance: dict | None = None,
               confidence: str | None = None) -> CommitResult:
        target = self.resolve_id(target)
        if not self.state.active(target):
            raise KSError(ErrorKind.LOGIC_ERROR, f"{target} is not active")
        errs = [d for d in check_node(new_node) if d.severity == Severity.ERROR]
        if errs:
            raise KSError(errs[0].kind, errs[0].message)
        e, da, df = self._commit("revise", {"target": target, "node": new_node.to_dict(), "reason": reason,
                                            "provenance": provenance or {"source_type": "human_input"},
                                            "confidence": confidence})
        return CommitResult([e], da, df, self._decision_impact(da))

    def split(self, target: str, new_nodes: list[Node], reason: str | None = None,
              provenance: dict | None = None) -> CommitResult:
        """One atomic, explicit journal transition: old active record → two successors."""
        target = self.resolve_id(target)
        old = self.state.records[target]
        if not self.state.active(target) or old.role not in ("fact", "claim", "hypothesis"):
            raise KSError(ErrorKind.LOGIC_ERROR, "split requires an active human-reviewed statement")
        if len(new_nodes) != 2 or len({node.id for node in new_nodes}) != 2:
            raise KSError(ErrorKind.LOGIC_ERROR, "split requires two distinct successors")
        for node in new_nodes:
            if node.TYPE != old.role or node.id in self.state.records or node.id in self.state.rejected:
                raise KSError(ErrorKind.LOGIC_ERROR, "split successor is not new and role-matched")
            errors = [d for d in check_node(node) if d.severity == Severity.ERROR]
            if errors:
                raise KSError(errors[0].kind, errors[0].message)
        e, da, df = self._commit("split", {
            "target": target, "nodes": [node.to_dict() for node in new_nodes],
            "reason": reason, "provenance": provenance or {"source_type": "human_input"},
        })
        return CommitResult([e], da, df, self._decision_impact(da))

    def merge_records(self, targets: list[str], new_node: Node, reason: str | None = None,
                      provenance: dict | None = None) -> CommitResult:
        """Two separately approved records → one explicitly human-reviewed successor."""
        if len(targets) != 2 or targets[0] == targets[1]:
            raise KSError(ErrorKind.LOGIC_ERROR, "merge requires two different records")
        targets = [self.resolve_id(target) for target in targets]
        if targets[0] == targets[1] or not all(self.state.active(target) for target in targets):
            raise KSError(ErrorKind.LOGIC_ERROR, "merge requires two active records")
        roles = {self.state.records[target].role for target in targets}
        if len(roles) != 1 or roles.pop() not in ("fact", "claim", "hypothesis") \
                or new_node.TYPE != self.state.records[targets[0]].role:
            raise KSError(ErrorKind.LOGIC_ERROR, "merge requires one human-selected role")
        if new_node.id in self.state.records or new_node.id in self.state.rejected:
            raise KSError(ErrorKind.LOGIC_ERROR, "merge successor must be new and unrejected")
        errors = [d for d in check_node(new_node) if d.severity == Severity.ERROR]
        if errors:
            raise KSError(errors[0].kind, errors[0].message)
        e, da, df = self._commit("merge", {
            "targets": targets, "node": new_node.to_dict(), "reason": reason,
            "provenance": provenance or {"source_type": "human_input"},
        })
        return CommitResult([e], da, df, self._decision_impact(da))

    def resolve(self, origin: str, reading_id: str) -> Entry:
        return self._commit("resolve", {"origin": origin, "reading_id": reading_id})[0]

    def bind(self, origin: str, slot: str, entity: str) -> Entry:
        return self._commit("bind", {"origin": origin, "slot": slot, "entity": entity})[0]

    def label(self, key: str, label: str) -> Entry:
        return self._commit("label", {"key": key, "label": label})[0]

    def alias(self, alias: str, canonical: str) -> Entry:
        return self._commit("alias", {"alias": alias, "canonical": canonical})[0]

    def add_schema(self, schema: dict) -> Entry:
        return self._commit("schema", {"schema": schema})[0]

    def load_rules_file(self, path: Path, confidence: str | None = None) -> CommitResult:
        """Библиотека правил — тоже знание: загружается только явным действием человека."""
        from ..ir.ksf import iter_rules, parse_ksf
        from ..semantics.normalize import ksf_symbol
        text = path.read_text(encoding="utf-8")
        items = parse_ksf(text, ksf_symbol)  # файл .ks — по определению KSF; ошибки типизированы
        nodes = list(iter_rules(items)) + [i for i in items if isinstance(i, Node) and not isinstance(i, Rule)]
        prov = {"source_type": "rules_file", "document": str(path.name), "text": text[:2000],
                "extraction": {"method": "ksf"}}
        nodes = [n for n in nodes if not self.state.active(n.id)]
        return self.accept(nodes, provenance=prov, confidence=confidence)

    # ------------------------------------------------------------------ impact
    def _decision_impact(self, da: Delta) -> list[str]:
        """Решения, основания которых перестали поддерживаться (решение ≠ факт, но помнит основания)."""
        notes = []
        for rec in self.state.authoritative("decision"):
            for ref in rec.node.because:
                aid = "A:" + ref.split(":", 1)[1]
                if aid in da.removed or (aid not in self.auth.items and ref.startswith("F:")):
                    notes.append(f"{rec.id} «{rec.node.action}»: основание {ref} больше не поддерживается")
        return notes

    # ------------------------------------------------------------------ what-if (без записи в журнал)
    def what_if_retract(self, target: str) -> Delta:
        target = self.resolve_id(target)
        rec = self.state.records[target]
        eng = copy.deepcopy(self.auth)
        if isinstance(rec.node, Rule):
            return eng.remove_rule(target)
        if isinstance(rec.node, Fact):
            return eng.remove_base(rec.node.assertion.id, target)
        return Delta()

    # ------------------------------------------------------------------ persistence of derived state
    def persist(self) -> None:
        con = connect(self.ws.db_path)
        con.execute("BEGIN")
        for t in ("journal", "records", "items", "justifications", "justification_premises", "labels", "aliases"):
            con.execute(f"DELETE FROM {t}")
        for e in self.state.entries:
            con.execute("INSERT INTO journal VALUES(?,?,?,?,?,?)", (e.seq, e.id, e.op, e.ts, e.actor, canonical_json(e.data)))
        for r in self.state.records.values():
            d = r.to_dict()
            con.execute("INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        (r.id, r.role, r.status.value, canonical_json(d["node"]), r.knowledge_type, r.confidence.value,
                         canonical_json(r.provenance), r.created_at, r.updated_at, canonical_json(r.journal),
                         canonical_json(r.supersedes), r.superseded_by, d["ksf"]))
        for layer, eng in (("auth", self.auth), ("full", self.full)):
            for aid, a in eng.items.items():
                if layer == "full" and aid in self.auth.items:
                    continue
                args = a.atom.args
                con.execute("INSERT OR REPLACE INTO items VALUES(?,?,?,?,?,?,?,?,?)",
                            (aid, a.atom.predicate, args[0].key(), args[1].key() if len(args) > 1 else None,
                             int(a.negated), a.key(), layer, canonical_json(sorted(eng.base.get(aid, ()))),
                             canonical_json(a.payload())))
            for jid, j in eng.just.items():
                if layer == "full" and jid in self.auth.just:
                    continue
                con.execute("INSERT OR REPLACE INTO justifications VALUES(?,?,?,?,?)",
                            (jid, j.conclusion, j.rule, canonical_json(list(j.premises)), layer))
                for p in j.premises:
                    con.execute("INSERT INTO justification_premises VALUES(?,?)", (jid, p))
        for k, v in sorted(self.state.labels.items()):
            con.execute("INSERT INTO labels VALUES(?,?,?)", (k, "default", v))
        for k, v in sorted(self.state.aliases.items()):
            con.execute("INSERT INTO aliases VALUES(?,?)", (k, v))
        set_meta(con, "kb_head", self.state.head or "")
        con.execute("COMMIT")
        con.close()
        self.save_cache()

    def paraphrase(self, nodes) -> str:
        return paraphrase_nodes(nodes, self.state)
