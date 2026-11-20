"""Strict, read-only causal comparison of independently edited journals.

This module NEVER rewrites a local journal, replays foreign decisions, or
modifies Markdown. A signed, explicitly paired transport must be layered on
top before importing an untrusted peer's event branch. The current v1 journal
is a *linear* hash chain: two offline continuations cannot be concatenated.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any

from ..core.ids import canonical_json
from .journal import Entry, OPS, entry_id

MAX_EXCHANGE_BYTES = 8_000_000
MAX_EXCHANGE_EVENTS = 100_000


@dataclass(frozen=True)
class JournalBranchPreview:
    relation: str
    common_events: int
    local_events: int
    remote_events: int
    local_only: int
    remote_only: int
    local_head: str | None
    remote_head: str | None
    common_head: str | None
    overlapping_targets: tuple[str, ...]
    has_unclassified_operations: bool
    local_sha256: str
    remote_sha256: str

    def summary(self) -> dict[str, Any]:
        # Intentionally omit event payloads, paths, private note excerpts and
        # even target identifiers: only counts and hash commitments are public.
        return {
            "protocol": "dpksys.sync-preview.v1",
            "relation": self.relation,
            "common_events": self.common_events,
            "local_events": self.local_events,
            "remote_events": self.remote_events,
            "local_only": self.local_only,
            "remote_only": self.remote_only,
            "local_head": self.local_head,
            "remote_head": self.remote_head,
            "common_head": self.common_head,
            "possible_target_conflicts": len(self.overlapping_targets),
            "has_unclassified_operations": self.has_unclassified_operations,
            "local_sha256": self.local_sha256,
            "remote_sha256": self.remote_sha256,
            "read_only": True,
            "automatic_merge": False,
        }


def validated_entries(content: str) -> list[Entry]:
    """Reject malformed/edited/oversized hash chains before comparing them."""
    if not isinstance(content, str):
        raise ValueError("Journal exchange must be UTF-8 text")
    if len(content.encode("utf-8")) > MAX_EXCHANGE_BYTES:
        raise ValueError("Journal exchange size limit exceeded")
    entries: list[Entry] = []
    previous = None
    for raw in content.splitlines():
        if not raw.strip():
            continue
        if len(entries) >= MAX_EXCHANGE_EVENTS:
            raise ValueError("Too many journal events")
        event = json.loads(raw)
        if not isinstance(event, dict) or set(event) - {
            "seq", "id", "prev", "ts", "actor", "op", "data", "note"
        }:
            raise ValueError("Unsupported journal event fields")
        if not isinstance(event.get("data"), dict) or not isinstance(event.get("actor"), str) \
                or not isinstance(event.get("ts"), str) or event.get("op") not in OPS:
            raise ValueError("Unsupported journal event content")
        seq = event.get("seq")
        if type(seq) is not int or seq != len(entries) + 1:
            raise ValueError("Journal sequence gap")
        note = event.get("note")
        if note is not None and not isinstance(note, str):
            raise ValueError("Malformed journal note")
        item = Entry(seq, event.get("id"), event.get("prev"), event["ts"],
                     event["actor"], event["op"], event["data"], note)
        if item.prev != previous or item.id != entry_id(
                item.prev, item.seq, item.ts, item.actor, item.op, item.data, item.note):
            raise ValueError("Journal hash chain is not authentic")
        entries.append(item)
        previous = item.id
    return entries


def _targets(event: Entry) -> set[str] | None:
    data = event.data
    if event.op in {"retract", "revise", "split"}:
        return {data["target"]} if isinstance(data.get("target"), str) else None
    if event.op == "merge":
        targets = data.get("targets")
        return set(targets) if isinstance(targets, list) and all(
            isinstance(value, str) for value in targets) else None
    if event.op == "accept":
        node = data.get("node")
        return {node["id"]} if isinstance(node, dict) and isinstance(
            node.get("id"), str) else None
    if event.op == "batch_accept":
        nodes = data.get("nodes")
        return {node["id"] for node in nodes} if isinstance(nodes, list) and all(
            isinstance(node, dict) and isinstance(node.get("id"), str)
            for node in nodes) else None
    # A schema/alias/label/policy event may affect arbitrary other knowledge;
    # never declare it safely commutative merely because target sets differ.
    return None


def preview_journals(local_content: str, remote_content: str) -> JournalBranchPreview:
    local = validated_entries(local_content)
    remote = validated_entries(remote_content)
    common = 0
    while common < min(len(local), len(remote)) \
            and local[common].id == remote[common].id:
        common += 1
    if common == len(local) == len(remote):
        relation = "identical"
    elif common == len(local):
        relation = "local_prefix"
    elif common == len(remote):
        relation = "remote_prefix"
    elif common == 0:
        relation = "unrelated"
    else:
        relation = "diverged"
    local_changes = [_targets(e) for e in local[common:]]
    remote_changes = [_targets(e) for e in remote[common:]]
    all_known = all(change is not None for change in local_changes + remote_changes)
    local_targets = set().union(*(change for change in local_changes if change is not None))
    remote_targets = set().union(*(change for change in remote_changes if change is not None))
    conflicts = tuple(sorted(local_targets & remote_targets))
    return JournalBranchPreview(
        relation=relation, common_events=common,
        local_events=len(local), remote_events=len(remote),
        local_only=len(local) - common, remote_only=len(remote) - common,
        local_head=local[-1].id if local else None,
        remote_head=remote[-1].id if remote else None,
        common_head=local[common - 1].id if common else None,
        overlapping_targets=conflicts, has_unclassified_operations=not all_known,
        local_sha256=hashlib.sha256(local_content.encode("utf-8")).hexdigest(),
        remote_sha256=hashlib.sha256(remote_content.encode("utf-8")).hexdigest(),
    )
