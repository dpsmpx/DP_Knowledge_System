"""Детерминированный replay журнала → состояние знаний (записи, статусы, решения).

Состояние — чистая функция от префикса журнала: replay одних и тех же записей
всегда даёт одно и то же состояние (это проверяется тестами).
Состояние реализует Resolver для семантического слоя (привязки ссылок, выборы
прочтений, алиасы, ярлыки), поэтому решения человека переиспользуются.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from ..core.enums import Confidence, EpistemicStatus
from ..core.errors import Diagnostic, ErrorKind, Severity
from ..ir.nodes import Claim, Hypothesis, Node, node_from_dict
from .journal import Entry

ROLE_STATUS = {"claim": EpistemicStatus.CLAIMED, "hypothesis": EpistemicStatus.UNKNOWN}


@dataclass
class Record:
    id: str
    node: Node
    role: str
    status: EpistemicStatus
    knowledge_type: str | None
    confidence: Confidence
    provenance: list = field(default_factory=list)
    created_at: str = ""
    updated_at: str = ""
    journal: list = field(default_factory=list)
    supersedes: list = field(default_factory=list)
    superseded_by: str | None = None
    obsoleted_by: str | None = None   # J:-решение, сделавшее запись OBSOLETE
    superseded_by_many: list = field(default_factory=list)  # split → all successor record ids

    @property
    def authoritative(self) -> bool:
        return self.status == EpistemicStatus.HUMAN_ACCEPTED

    def to_dict(self) -> dict:
        return {"id": self.id, "role": self.role, "status": self.status.value, "node": self.node.to_dict(),
                "knowledge_type": self.knowledge_type, "confidence": self.confidence.value,
                "provenance": self.provenance, "created_at": self.created_at, "updated_at": self.updated_at,
                "journal": self.journal, "supersedes": self.supersedes, "superseded_by": self.superseded_by,
                "obsoleted_by": self.obsoleted_by, "superseded_by_many": self.superseded_by_many,
                "ksf": self.node.ksf()}


@dataclass
class Change:
    """Что изменилось в авторитетном/заявленном слое — вход для инкрементального TMS."""
    entry: Entry
    activated: list = field(default_factory=list)    # id записей, ставших активными
    deactivated: list = field(default_factory=list)  # id записей, ставших неактивными


class KnowledgeState:
    def __init__(self):
        self.records: dict[str, Record] = {}
        self.rejected: dict[str, str] = {}            # node id → J:-решение отказа
        self.rejected_candidates: dict[str, str] = {}
        self.deferred: dict[str, str] = {}
        self.accepted_candidates: dict[str, list] = {}
        self.choices: dict[str, str] = {}             # origin → reading_id
        self.bindings: dict[tuple, str] = {}          # (origin, slot) → ключ сущности
        self.labels: dict[str, str] = {}
        self.auto_labels: dict[str, str] = {}       # поверхностные формы из принятых текстов
        self.aliases: dict[str, str] = {}
        self.alias_decisions: dict[str, tuple[str, str, str]] = {}
        self.schema_ext: list = []
        self.head: str | None = None
        self.entries: list[Entry] = []
        self.diagnostics: list[Diagnostic] = []

    # ------------------------------------------------------------- Resolver
    def binding(self, origin: str, slot: str) -> str | None:
        return self.bindings.get((origin, slot))

    def canonical(self, key: str) -> str:
        seen = set()
        while key in self.aliases and key not in seen:
            seen.add(key)
            key = self.aliases[key]
        return key

    def label(self, key: str) -> str | None:
        return self.labels.get(key) or self.auto_labels.get(key)

    def reading_choice(self, origin: str) -> str | None:
        return self.choices.get(origin)

    # ------------------------------------------------------------- replay
    @staticmethod
    def replay(entries) -> "KnowledgeState":
        st = KnowledgeState()
        for e in entries:
            st.apply(e)
        return st

    def active(self, record_id: str) -> bool:
        r = self.records.get(record_id)
        return r is not None and r.status in (EpistemicStatus.HUMAN_ACCEPTED, EpistemicStatus.CLAIMED,
                                              EpistemicStatus.UNKNOWN)

    def apply(self, e: Entry) -> Change:
        ch = Change(e)
        d = e.data
        if e.op == "accept":
            node = node_from_dict(d["node"])
            if node.id in self.rejected and not d.get("override_rejection"):
                self.diagnostics.append(Diagnostic(ErrorKind.JOURNAL_ERROR,
                                                   f"{node.id} was rejected by {self.rejected[node.id]}; "
                                                   "accept without override_rejection ignored", location=e.id))
            else:
                self._accept(node, d, e, ch)
                self.rejected.pop(node.id, None)
                for key, text in (d.get("surface") or {}).items():
                    self.auto_labels.setdefault(key, text)
        elif e.op == "batch_accept":
            # A single, human-authorized journal event activates the entire
            # bounded batch. Each record retains its own source provenance.
            items = d.get("items", [])
            nodes = [node_from_dict(item["node"]) for item in items]
            if (not 2 <= len(nodes) <= 8
                    or len({node.id for node in nodes}) != len(nodes)
                    or any(node.TYPE != "claim" or node.id in self.records
                           or node.id in self.rejected for node in nodes)):
                self.diagnostics.append(Diagnostic(ErrorKind.JOURNAL_ERROR,
                                                   "invalid atomic batch acceptance", location=e.id))
            else:
                for node, item in zip(nodes, items):
                    self._accept(node, item, e, ch)
        elif e.op == "reject":
            for cid in d.get("candidates", []):
                self.rejected_candidates[cid] = e.id
            for nid in d.get("targets", []):
                rec = self.records.get(nid)
                if rec is not None and rec.authoritative:
                    self.diagnostics.append(Diagnostic(ErrorKind.JOURNAL_ERROR,
                                                       f"reject of accepted {nid}: use retract", Severity.WARNING, e.id))
                self.rejected[nid] = e.id
        elif e.op == "defer":
            for cid in d.get("candidates", []):
                self.deferred[cid] = e.id
        elif e.op == "retract":
            self._obsolete(d["target"], e, ch, None)
        elif e.op == "revise":
            node = node_from_dict(d["node"])
            if d["target"] in self.records:
                self._obsolete(d["target"], e, ch, node.id)
                self._accept(node, d, e, ch, supersedes=d["target"])
            else:
                self.diagnostics.append(Diagnostic(ErrorKind.JOURNAL_ERROR, f"revise of unknown {d['target']}",
                                                   location=e.id))
        elif e.op == "split":
            target = d["target"]
            nodes = [node_from_dict(item) for item in d["nodes"]]
            if self.active(target) and len(nodes) == 2 and len({node.id for node in nodes}) == 2 \
                    and all(node.id not in self.records and node.id not in self.rejected for node in nodes):
                self._obsolete(target, e, ch, nodes[0].id)
                self.records[target].superseded_by_many = [node.id for node in nodes]
                for node in nodes:
                    self._accept(node, d, e, ch, supersedes=target)
            else:
                self.diagnostics.append(Diagnostic(ErrorKind.JOURNAL_ERROR,
                                                   "split target inactive or successors invalid", location=e.id))
        elif e.op == "merge":
            targets = d["targets"]
            node = node_from_dict(d["node"])
            if len(targets) == 2 and len(set(targets)) == 2 \
                    and all(self.active(target) for target in targets) \
                    and len({self.records[target].role for target in targets}) == 1 \
                    and node.TYPE == self.records[targets[0]].role \
                    and node.id not in self.records and node.id not in self.rejected:
                for target in targets:
                    self._obsolete(target, e, ch, node.id)
                    self.records[target].superseded_by_many = [node.id]
                self._accept(node, d, e, ch)
                self.records[node.id].supersedes.extend(targets)
            else:
                self.diagnostics.append(Diagnostic(ErrorKind.JOURNAL_ERROR,
                                                   "merge targets or successor invalid", location=e.id))
        elif e.op == "resolve":
            self.choices[d["origin"]] = d["reading_id"]
        elif e.op == "bind":
            self.bindings[(d["origin"], d["slot"])] = d["entity"]
        elif e.op == "label":
            self.labels[d["key"]] = d["label"]
        elif e.op == "alias":
            if self.aliases.get(d["alias"]) != d["canonical"]:
                self.alias_decisions[d["alias"]] = (d["canonical"], e.id, e.actor)
            self.aliases[d["alias"]] = d["canonical"]
        elif e.op == "unalias":
            # The matching owner and original decision ID are mandatory.
            # Imported/foreign or stale revocations have no effect on replay.
            if self.alias_decisions.get(d.get("alias")) == (
                    d.get("canonical"), d.get("decision_id"), e.actor):
                self.aliases.pop(d["alias"], None)
                self.alias_decisions.pop(d["alias"], None)
        elif e.op in ("source_link", "source_unlink"):
            # A separate human provenance annotation. Never change claims,
            # alias identity, authoritative facts or inference engines.
            pass
        elif e.op == "schema":
            self.schema_ext.append(d["schema"])
        self.head = e.id
        self.entries.append(e)
        return ch

    def _accept(self, node: Node, d: dict, e: Entry, ch: Change, supersedes: str | None = None) -> None:
        role = node.TYPE
        status = ROLE_STATUS.get(role, EpistemicStatus.HUMAN_ACCEPTED)
        rec = self.records.get(node.id)
        conf = Confidence(d.get("confidence") or "unrated")
        prov = dict(d.get("provenance") or {})
        prov.update({"verifier": e.actor, "verified_at": e.ts, "journal_entry": e.id})
        was_active = self.active(node.id)
        if rec is None:
            kt = d.get("knowledge_type") or getattr(node, "knowledge_type", None)
            kt = kt.value if hasattr(kt, "value") else kt
            rec = Record(node.id, node, role, status, kt, conf, [prov], e.ts, e.ts, [e.id])
            self.records[node.id] = rec
        else:
            rec.status = status
            rec.provenance.append(prov)
            rec.updated_at = e.ts
            rec.journal.append(e.id)
            rec.obsoleted_by = None
            rec.superseded_by = None
            if d.get("confidence"):
                rec.confidence = conf
        if supersedes:
            rec.supersedes.append(supersedes)
        for cid in d.get("candidates", []):
            self.accepted_candidates.setdefault(cid, []).append(node.id)
        if not was_active:
            ch.activated.append(node.id)

    def _obsolete(self, target: str, e: Entry, ch: Change, superseded_by: str | None) -> None:
        rec = self.records.get(target)
        if rec is None:
            self.diagnostics.append(Diagnostic(ErrorKind.JOURNAL_ERROR, f"retract of unknown {target}", location=e.id))
            return
        if rec.status == EpistemicStatus.OBSOLETE:
            return
        rec.status = EpistemicStatus.OBSOLETE
        rec.obsoleted_by = e.id
        rec.superseded_by = superseded_by
        rec.updated_at = e.ts
        rec.journal.append(e.id)
        ch.deactivated.append(target)

    # ------------------------------------------------------------- views
    def by_status(self, *statuses) -> list[Record]:
        return [r for r in sorted(self.records.values(), key=lambda r: r.id) if r.status in statuses]

    def authoritative(self, role: str | None = None) -> list[Record]:
        return [r for r in self.by_status(EpistemicStatus.HUMAN_ACCEPTED) if role is None or r.role == role]

    def claims(self) -> list[Record]:
        return [r for r in self.by_status(EpistemicStatus.CLAIMED) if isinstance(r.node, Claim)]

    def hypotheses(self) -> list[Record]:
        return [r for r in self.by_status(EpistemicStatus.UNKNOWN) if isinstance(r.node, Hypothesis)]
