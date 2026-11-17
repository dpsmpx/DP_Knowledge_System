"""Журнал решений человека — единственный авторитетный артефакт (append-only JSONL).

Каждая строка — knowledge commit:
    {"seq", "id": "J:…", "prev", "ts", "actor", "op", "data", "note"}
id = J:hash(prev, seq, ts, actor, op, data, note) — цепочка хешей: любая правка
задним числом обнаруживается проверкой ``verify``.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from ..core.clock import Clock
from ..core.errors import Diagnostic, ErrorKind, KSError
from ..core.ids import canonical_json, digest

OPS = {"accept", "reject", "defer", "retract", "revise", "resolve", "bind", "label", "alias", "schema"}


@dataclass(frozen=True)
class Entry:
    seq: int
    id: str
    prev: str | None
    ts: str
    actor: str
    op: str
    data: dict
    note: str | None = None

    def to_dict(self) -> dict:
        d = {"seq": self.seq, "id": self.id, "prev": self.prev, "ts": self.ts, "actor": self.actor,
             "op": self.op, "data": self.data}
        if self.note:
            d["note"] = self.note
        return d


def entry_id(prev: str | None, seq: int, ts: str, actor: str, op: str, data: dict, note: str | None) -> str:
    return "J:" + digest(canonical_json({"prev": prev, "seq": seq, "ts": ts, "actor": actor, "op": op,
                                         "data": data, "note": note}))


class Journal:
    def __init__(self, path: Path, clock: Clock, actor: str = "human:me"):
        self.path = path
        self.clock = clock
        self.actor = actor
        self._entries: list[Entry] | None = None

    def entries(self) -> list[Entry]:
        if self._entries is None:
            self._entries = []
            if self.path.exists():
                for n, line in enumerate(self.path.read_text(encoding="utf-8").splitlines(), 1):
                    if not line.strip():
                        continue
                    try:
                        d = json.loads(line)
                        self._entries.append(Entry(d["seq"], d["id"], d.get("prev"), d["ts"], d["actor"], d["op"],
                                                   d["data"], d.get("note")))
                    except (ValueError, KeyError) as exc:
                        raise KSError(ErrorKind.JOURNAL_ERROR, f"malformed journal line {n}: {exc}") from exc
        return self._entries

    @property
    def head(self) -> str | None:
        e = self.entries()
        return e[-1].id if e else None

    def append(self, op: str, data: dict, note: str | None = None, actor: str | None = None) -> Entry:
        if op not in OPS:
            raise KSError(ErrorKind.JOURNAL_ERROR, f"unknown journal op {op!r}")
        entries = self.entries()
        seq = entries[-1].seq + 1 if entries else 1
        prev = entries[-1].id if entries else None
        ts = self.clock.now()
        actor = actor or self.actor
        data = json.loads(canonical_json(data))  # каноническая форма, без несериализуемого
        e = Entry(seq, entry_id(prev, seq, ts, actor, op, data, note), prev, ts, actor, op, data, note)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(canonical_json(e.to_dict()) + "\n")
        entries.append(e)
        return e

    def verify(self) -> list[Diagnostic]:
        out = []
        prev = None
        for i, e in enumerate(self.entries(), 1):
            if e.seq != i:
                out.append(Diagnostic(ErrorKind.JOURNAL_ERROR, f"sequence gap at {e.id}: {e.seq} != {i}"))
            if e.prev != prev:
                out.append(Diagnostic(ErrorKind.JOURNAL_ERROR, f"broken chain at seq {e.seq}"))
            if entry_id(e.prev, e.seq, e.ts, e.actor, e.op, e.data, e.note) != e.id:
                out.append(Diagnostic(ErrorKind.JOURNAL_ERROR, f"hash mismatch at seq {e.seq} (edited entry?)"))
            prev = e.id
        return out

    def upto(self, commit: str | None) -> list[Entry]:
        """Префикс журнала до коммита включительно (по полному или сокращённому ID / номеру)."""
        if commit is None:
            return list(self.entries())
        out = []
        for e in self.entries():
            out.append(e)
            if e.id == commit or e.id.startswith(commit) or str(e.seq) == commit.lstrip("#"):
                return out
        raise KSError(ErrorKind.JOURNAL_ERROR, f"unknown knowledge commit {commit!r}")
