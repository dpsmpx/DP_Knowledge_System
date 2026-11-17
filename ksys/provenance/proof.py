"""Деревья доказательства и трассировка до источников («Почему система считает X?»).

Формат proof trace:
    вывод (D:h) ── по правилу R:… ── посылки (каждая — вывод или базовый факт)
    базовый факт (F:h) ── решение человека J:… ── provenance (документ, фрагмент, текст)
Обход ограничен глубиной и числом узлов; общие подграфы показываются один раз
(повторные ссылки помечены «см. выше»). Ни один вывод не показывается без обоснования:
если обоснований нет, это ошибка PROVENANCE_ERROR (инвариант 3).
"""
from __future__ import annotations

from dataclasses import dataclass, field

from ..core.enums import CONFIDENCE_RANK, Confidence
from ..core.errors import ErrorKind, KSError

RANK_TO_CONF = {v: k for k, v in CONFIDENCE_RANK.items()}


def short(aid: str, base: bool) -> str:
    return ("F:" if base else "D:") + aid.split(":", 1)[1]


class LocalConfidence:
    """Уверенность «по самому слабому звену», вычисляемая лениво только для узлов доказательства."""

    def __init__(self, engine, state):
        self.engine = engine
        self.state = state
        self.memo: dict = {}

    def rank(self, aid: str, active=frozenset()) -> int:
        if aid in self.memo:
            return self.memo[aid]
        eng = self.engine
        if eng.is_base(aid):
            ranks = [CONFIDENCE_RANK[self.state.records[s].confidence] for s in eng.base[aid] if s in self.state.records]
            r = max(ranks) if ranks else CONFIDENCE_RANK[Confidence.UNRATED]
            self.memo[aid] = r
            return r
        best = 0
        for j in eng.justifications(aid):
            if set(j.premises) & (active | {aid}):
                continue  # обоснование через самого себя — не опора
            rule = self.state.records.get(j.rule)
            rc = CONFIDENCE_RANK[rule.confidence] if rule else CONFIDENCE_RANK[Confidence.UNRATED]
            val = min([rc] + [self.rank(p, active | {aid}) for p in j.premises])
            best = max(best, val)
        if not active:
            self.memo[aid] = best
        return best

    def get(self, aid: str, default=None):
        return RANK_TO_CONF.get(max(self.rank(aid), 1), Confidence.UNRATED)


def confidence_map(engine, state) -> dict:
    """Уверенность «по самому слабому звену» с лучшим из альтернативных обоснований (детерминированно)."""
    conf: dict = {}
    for aid, srcs in engine.base.items():
        ranks = [CONFIDENCE_RANK[state.records[s].confidence] for s in srcs if s in state.records]
        conf[aid] = max(ranks) if ranks else CONFIDENCE_RANK[Confidence.UNRATED]
    rule_conf = {rid: CONFIDENCE_RANK[state.records[rid].confidence] if rid in state.records
                 else CONFIDENCE_RANK[Confidence.UNRATED] for rid in engine.rules}
    for aid in engine.items:
        conf.setdefault(aid, 0)
    changed = True
    while changed:
        changed = False
        for aid in sorted(engine.items):
            best = conf[aid]
            for j in engine.justifications(aid):
                val = min([rule_conf.get(j.rule, 1)] + [conf.get(p, 0) for p in j.premises])
                if val > best:
                    best = val
            if best != conf[aid]:
                conf[aid] = best
                changed = True
    return {aid: RANK_TO_CONF.get(max(v, 1), Confidence.UNRATED) for aid, v in conf.items()}


@dataclass
class ProofNode:
    aid: str
    id: str
    ksf: str
    kind: str                       # ASSERTED | DERIVED
    confidence: str
    sources: list = field(default_factory=list)      # для ASSERTED: записи + provenance
    derivations: list = field(default_factory=list)  # для DERIVED: [{justification, rule, rule_ksf, premises}]
    repeated: bool = False
    truncated: bool = False

    def to_dict(self) -> dict:
        return {"id": self.id, "aid": self.aid, "ksf": self.ksf, "kind": self.kind, "confidence": self.confidence,
                "sources": self.sources, "repeated": self.repeated, "truncated": self.truncated,
                "derivations": [{**{k: v for k, v in d.items() if k != "premises"},
                                 "premises": [p.to_dict() for p in d["premises"]]} for d in self.derivations]}


def source_summary(state, record_id: str) -> dict:
    rec = state.records.get(record_id)
    if rec is None:
        return {"record": record_id, "status": "MISSING"}
    provs = []
    for p in rec.provenance:
        provs.append({k: p.get(k) for k in ("source_type", "document", "locator", "fragment_id", "text",
                                             "verifier", "verified_at", "journal_entry", "author") if p.get(k)})
    return {"record": record_id, "status": rec.status.value, "confidence": rec.confidence.value,
            "knowledge_type": rec.knowledge_type, "provenance": provs}


class ProofBuilder:
    def __init__(self, engine, state, max_depth: int = 25, max_nodes: int = 400, max_alternatives: int = 3):
        self.engine = engine
        self.state = state
        self.max_depth = max_depth
        self.max_nodes = max_nodes
        self.max_alt = max_alternatives
        self.conf = LocalConfidence(engine, state)

    def build(self, aid: str) -> ProofNode:
        if aid not in self.engine.items:
            raise KSError(ErrorKind.QUERY_ERROR, f"{aid} is not known (UNKNOWN, no proof)")
        self.count = 0
        self.seen: set = set()
        return self._node(aid, 0, frozenset())

    def _node(self, aid: str, depth: int, path: frozenset) -> ProofNode:
        eng = self.engine
        base = eng.is_base(aid)
        a = eng.items[aid]
        node = ProofNode(aid, short(aid, base), a.key(), "ASSERTED" if base else "DERIVED",
                         self.conf.get(aid, Confidence.UNRATED).value)
        self.count += 1
        if aid in self.seen:
            node.repeated = True
            return node
        self.seen.add(aid)
        if base:
            node.sources = [source_summary(self.state, s) for s in sorted(eng.base[aid])]
            return node
        justs = eng.justifications(aid)
        if not justs:
            raise KSError(ErrorKind.PROVENANCE_ERROR, f"derived item {aid} has no justification")
        if depth >= self.max_depth or self.count >= self.max_nodes:
            node.truncated = True
            return node
        well = [j for j in justs if aid not in j.premises and not (set(j.premises) & path)]
        for j in (well or justs)[: self.max_alt]:
            rule = self.state.records.get(j.rule)
            node.derivations.append({
                "justification": j.id, "rule": j.rule,
                "rule_ksf": rule.node.ksf() if rule else eng.rules[j.rule].ksf(),
                "rule_source": source_summary(self.state, j.rule) if rule else {"record": j.rule},
                "premises": [self._node(p, depth + 1, path | {aid}) for p in j.premises]})
        if len(justs) > self.max_alt:
            node.derivations.append({"justification": f"+{len(justs) - self.max_alt} more", "rule": "",
                                     "rule_ksf": "", "premises": []})
        return node


def render(node: ProofNode, indent: str = "", last: bool = True, top: bool = True) -> str:
    branch = "" if top else ("└─ " if last else "├─ ")
    lines = []
    flag = " (см. выше)" if node.repeated else (" (обрезано)" if node.truncated else "")
    lines.append(f"{indent}{branch}{node.id} {node.ksf}  [{node.kind}, уверенность={node.confidence}]{flag}")
    child_indent = indent + ("" if top else ("   " if last else "│  "))
    for s in node.sources:
        for p in s.get("provenance", []):
            src = p.get("locator") or p.get("document") or p.get("source_type")
            text = f" «{p['text'][:90]}»" if p.get("text") else ""
            lines.append(f"{child_indent}   ← {s['record']} {s['status']} решение {p.get('journal_entry', '?')} "
                         f"{p.get('verifier', '')} {p.get('verified_at', '')}; источник: {src}{text}")
    for k, d in enumerate(node.derivations):
        is_last = k == len(node.derivations) - 1
        if not d["rule"]:
            lines.append(f"{child_indent}{'└─ ' if is_last else '├─ '}… ещё обоснований: {d['justification']}")
            continue
        lines.append(f"{child_indent}{'└─ ' if is_last else '├─ '}по правилу {d['rule']}: {d['rule_ksf']}")
        sub_indent = child_indent + ("   " if is_last else "│  ")
        for i, p in enumerate(d["premises"]):
            lines.append(render(p, sub_indent, i == len(d["premises"]) - 1, top=False))
    return "\n".join(lines)


def trace_sources(engine, state, aid: str, limit: int = 10_000) -> dict:
    """Плоская трассировка: все базовые факты, правила и исходные фрагменты, на которых держится aid."""
    if aid not in engine.items:
        raise KSError(ErrorKind.QUERY_ERROR, f"{aid} is not known")
    base_facts, rules, seen = set(), set(), set()
    stack = [aid]
    while stack and len(seen) < limit:
        x = stack.pop()
        if x in seen:
            continue
        seen.add(x)
        if engine.is_base(x):
            base_facts |= engine.base[x]
            continue
        for j in engine.justifications(x):
            rules.add(j.rule)
            stack.extend(j.premises)
    fragments = []
    for rid in sorted(base_facts | rules):
        rec = state.records.get(rid)
        for p in (rec.provenance if rec else []):
            fragments.append({"record": rid, "journal_entry": p.get("journal_entry"),
                              "source_type": p.get("source_type"), "document": p.get("document"),
                              "locator": p.get("locator"), "fragment_id": p.get("fragment_id"),
                              "text": p.get("text"), "verifier": p.get("verifier")})
    return {"conclusion": aid, "base_facts": sorted(base_facts), "rules": sorted(rules), "sources": fragments}
