"""Explicit, source-target-checked translation of remote revision proposals.

Nothing here replays an incoming journal entry. Each approval creates a new
local decision only when all old records are still locally active and the new
canonical nodes pass independent structural validation.
"""
from __future__ import annotations

import copy
import hashlib
from typing import Any

from ..core.enums import Confidence
from ..core.errors import Severity
from ..core.ids import canonical_json
from ..ir.nodes import node_from_dict
from ..ir.validate import check_node
from .journal_sync import verified_foreign_event

MANUAL_STRUCTURAL_OPS = frozenset({"revise", "split", "merge"})
MAX_STRUCTURAL_BYTES = 16_384


def _candidate(kb: Any, source: Any, expected_role: str):
    if not isinstance(source, dict):
        raise ValueError("Foreign successor must be a canonical node object")
    try:
        node = node_from_dict(source)
    except (ValueError, TypeError, KeyError, AttributeError, IndexError) as exc:
        raise ValueError("Foreign successor node cannot be parsed") from exc
    if (node.to_dict() != source or node.TYPE != expected_role
            or node.id in kb.state.records or node.id in kb.state.rejected):
        raise ValueError("Foreign successor is not new, canonical and role-matched")
    if any(diag.severity == Severity.ERROR for diag in check_node(node)):
        raise ValueError("Foreign successor fails local structural validation")
    return node


def _target(kb: Any, target: Any):
    if not isinstance(target, str) or target not in kb.state.records \
            or not kb.state.active(target):
        raise ValueError("Foreign proposal target is not active on this device")
    return kb.state.records[target]


def _review(kb: Any, op: str, incoming: dict) -> tuple[dict, list[dict]]:
    allowed = ({"target", "node", "reason", "confidence", "provenance"}
               if op == "revise" else {"target", "nodes", "reason", "provenance"}
               if op == "split" else {"targets", "node", "reason", "provenance"})
    if not isinstance(incoming, dict) or set(incoming) - allowed:
        raise ValueError("Unsupported foreign structural proposal fields")
    reason = incoming.get("reason")
    if reason is not None and (not isinstance(reason, str) or len(reason) > 512
                               or any(ord(c) < 32 for c in reason)):
        raise ValueError("Invalid foreign revision reason")
    targets = incoming.get("targets") if op == "merge" else [incoming.get("target")]
    if not isinstance(targets, list) or len(targets) != (2 if op == "merge" else 1) \
            or len(set(t for t in targets if isinstance(t, str))) != len(targets):
        raise ValueError("Foreign proposal must name exact distinct local targets")
    old = [_target(kb, target) for target in targets]
    role = old[0].role
    if any(item.role != role for item in old) or (op in ("split", "merge")
                                                 and role not in {"fact", "claim", "hypothesis"}):
        raise ValueError("Foreign proposal mixes locally incompatible record roles")
    if op == "split":
        raw = incoming.get("nodes")
        if not isinstance(raw, list) or len(raw) != 2:
            raise ValueError("Foreign split requires exactly two successors")
        successors = [_candidate(kb, item, role) for item in raw]
        if successors[0].id == successors[1].id:
            raise ValueError("Foreign split successors are not distinct")
        data = {"target": targets[0], "nodes": [item.to_dict() for item in successors]}
    else:
        successor = _candidate(kb, incoming.get("node"), role)
        data = {"targets": targets, "node": successor.to_dict()} if op == "merge" else {
            "target": targets[0], "node": successor.to_dict()}
    if op == "revise":
        conf = incoming.get("confidence")
        if conf is not None and (not isinstance(conf, str)
                                 or conf not in {v.value for v in Confidence}):
            raise ValueError("Invalid foreign revision confidence")
        data["confidence"] = conf
    # An incoming provenance claim is NOT trusted as a local source citation.
    # The new local decision instead identifies itself as a human-approved
    # foreign proposal and carries its exact cryptographic causal references.
    data["reason"] = reason
    data["provenance"] = {"source_type": "human_reviewed_foreign_proposal"}
    return data, [{"id": target, "role": record.role,
                   "node": copy.deepcopy(record.node.to_dict())}
                  for target, record in zip(targets, old)]


def plan_foreign_structure(kb: Any, local_text: str, foreign_text: str,
                           fingerprint: str, event_id: str, local_head: str | None,
                           remote_sha: str) -> dict:
    event, comparison = verified_foreign_event(
        local_text, foreign_text, fingerprint, event_id, local_head, remote_sha)
    if event.op not in MANUAL_STRUCTURAL_OPS:
        raise ValueError("Foreign operation is not an individual revision proposal")
    data, existing = _review(kb, event.op, event.data)
    commitment = canonical_json({"op": event.op, "data": data, "existing": existing,
                                 "event_id": event.id, "local_head": local_head,
                                 "remote_sha": remote_sha})
    if len(commitment.encode("utf-8")) > MAX_STRUCTURAL_BYTES:
        raise ValueError("Foreign revision exceeds human review limit")
    return {
        "operation": event.op, "data": data, "existing_local": existing,
        "plan_sha256": hashlib.sha256(commitment.encode("utf-8")).hexdigest(),
        "remote_event_id": event.id, "remote_sha256": comparison.remote_sha256,
        "remote_head": comparison.remote_head, "local_head": local_head,
        "common_head": comparison.common_head, "risk": "replaces_active_knowledge",
        "requires_human_confirmation": True, "read_only": True,
    }


def commit_foreign_structure(kb: Any, local_text: str, foreign_text: str,
                             fingerprint: str, event_id: str, local_head: str | None,
                             remote_sha: str, expected_plan_sha: str) -> dict:
    if not isinstance(expected_plan_sha, str) or len(expected_plan_sha) != 64:
        raise ValueError("Missing exact structural preview commitment")
    plan = plan_foreign_structure(kb, local_text, foreign_text, fingerprint,
                                  event_id, local_head, remote_sha)
    if plan["plan_sha256"] != expected_plan_sha or kb.journal.head != local_head:
        raise ValueError("Foreign revision preview is stale")
    data = copy.deepcopy(plan["data"])
    data["foreign_causal"] = {
        "peer_fingerprint": fingerprint, "remote_sha256": remote_sha,
        "remote_head": plan["remote_head"], "remote_event_id": event_id,
        "common_head": plan["common_head"], "local_head_at_review": local_head,
        "review_kind": "individual_manual_structural_approval_v1",
    }
    kb.ws.init()
    saved, _, _ = kb._commit(plan["operation"], data)
    if kb.journal.verify():
        raise ValueError("Local structural translation did not verify")
    return {"operation": plan["operation"], "journal_id": saved.id,
            "journal_head": kb.journal.head, "foreign_causal": data["foreign_causal"],
            "applied": True, "automatic_replay": False}
