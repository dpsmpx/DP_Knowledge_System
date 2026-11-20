"""Strict, read-only causal comparison of independently edited journals.

This module NEVER rewrites a local journal, replays foreign decisions, or
modifies Markdown. A signed, explicitly paired transport must be layered on
top before importing an untrusted peer's event branch. The current v1 journal
is a *linear* hash chain: two offline continuations cannot be concatenated.
"""
from __future__ import annotations

import hashlib
import json
import re
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
    if event.op == "peer_review":
        # A review receipt is an audit event, not a claim activation.
        return set()
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


def review_foreign_events(local_content: str, remote_content: str,
                          offset: int = 0, limit: int = 40) -> dict[str, Any]:
    """Bounded event-by-event review plan. Every event remains uncommitted."""
    if type(offset) is not int or type(limit) is not int or not 0 <= offset <= 100_000 \
            or not 1 <= limit <= 80:
        raise ValueError("Invalid sync review page")
    comparison = preview_journals(local_content, remote_content)
    local = validated_entries(local_content)
    remote = validated_entries(remote_content)
    remote_only = remote[comparison.common_events:]
    local_targets = set().union(*(
        change for event in local[comparison.common_events:]
        if (change := _targets(event)) is not None))
    local_unknown = any(_targets(e) is None for e in local[comparison.common_events:])
    rows = []
    for event in remote_only[offset:offset + limit]:
        targets = _targets(event)
        rows.append({
            "seq": event.seq,
            "event_id": event.id,
            "operation": event.op,
            "target_count": len(targets) if targets is not None else None,
            "possible_conflict": local_unknown or targets is None
            or bool(targets & local_targets),
            "review_required": True,
        })
    return {
        "protocol": "dpksys.sync-events.v1",
        "relation": comparison.relation,
        "local_sha256": comparison.local_sha256,
        "remote_sha256": comparison.remote_sha256,
        "foreign_total": len(remote_only),
        "offset": offset,
        "has_more": offset + len(rows) < len(remote_only),
        "events": rows,
        "read_only": True,
    }


def inspect_foreign_event(remote_content: str, event_id: str,
                          expected_sha256: str) -> dict[str, Any]:
    """Return one chosen event's evidence for an explicit on-device detail view.

    This result may contain private note provenance: never log/upload it or
    expose it in a summary, notification or exchange manifest.
    """
    if not isinstance(event_id, str) or len(event_id) > 160:
        raise ValueError("Invalid event identifier")
    if not isinstance(expected_sha256, str) or len(expected_sha256) != 64:
        raise ValueError("Invalid branch commitment")
    if hashlib.sha256(remote_content.encode("utf-8")).hexdigest() != expected_sha256:
        raise ValueError("Foreign journal changed since event review")
    events = validated_entries(remote_content)
    for event in events:
        if event.id == event_id:
            return {
                "protocol": "dpksys.sync-event-detail.v1",
                "seq": event.seq,
                "event_id": event.id,
                "operation": event.op,
                "data": event.data,
                "note": event.note,
                "timestamp": event.ts,
                "actor": event.actor,
                "read_only": True,
            }
    raise ValueError("Foreign event not found")


def prepare_peer_review(local_content: str, remote_content: str,
                        peer_fingerprint: str, remote_event_id: str,
                        expected_local_head: str | None, expected_remote_sha256: str,
                        verdict: str) -> dict[str, Any]:
    """Validate a human review of one signed, paired foreign event.

    Caller MUST authenticate the peer's ECDSA signature against a locally
    paired identity before calling this function. No branch data, notes or
    remote statements are copied into the local receipt. This is *not* a merge.
    """
    if not isinstance(peer_fingerprint, str) or not re.fullmatch(
            r"[0-9a-f]{64}", peer_fingerprint):
        raise ValueError("Invalid trusted peer fingerprint")
    if verdict not in ("hold", "reject"):
        raise ValueError("A review receipt cannot authorize remote knowledge")
    if not isinstance(remote_event_id, str) or len(remote_event_id) > 160:
        raise ValueError("Invalid remote event ID")
    if not isinstance(expected_remote_sha256, str) or not re.fullmatch(
            r"[0-9a-f]{64}", expected_remote_sha256):
        raise ValueError("Invalid remote branch commitment")
    comparison = preview_journals(local_content, remote_content)
    if comparison.remote_sha256 != expected_remote_sha256:
        raise ValueError("Foreign branch changed since review")
    if comparison.local_head != expected_local_head:
        raise ValueError("Local journal changed since review")
    remote = validated_entries(remote_content)
    foreign = {event.id for event in remote[comparison.common_events:]}
    if remote_event_id not in foreign:
        raise ValueError("Review event is not a foreign-only event")
    return {
        "peer_fingerprint": peer_fingerprint,
        "remote_sha256": expected_remote_sha256,
        "remote_head": comparison.remote_head,
        "remote_event_id": remote_event_id,
        "common_head": comparison.common_head,
        "local_head_at_review": expected_local_head,
        "verdict": verdict,
        "activates_knowledge": False,
        "protocol_version": 1,
    }
