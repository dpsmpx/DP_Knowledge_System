"""Human-approved, event-specific local translations of foreign metadata.

These helpers do not replay remote journal lines. The caller must re-open the
Keystore-encrypted inbox and verify the paired ECDSA signer before *both*
preview and commit. Every commit creates a NEW local event with explicit
foreign causal references and a compare-and-swap against both journal heads.
"""
from __future__ import annotations

import copy
import hashlib
import unicodedata
from typing import Any

from ..core.ids import canonical_json
from ..validation.schema import Schema, TYPE_OK
from .journal_sync import verified_foreign_event

MANUAL_METADATA_OPS = frozenset({"alias", "label", "schema"})
MAX_METADATA_BYTES = 4096


def _bounded_text(value: Any, title: str, *, maximum: int = 256) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum \
            or any(unicodedata.category(c) in {"Cc", "Cf", "Cs"} for c in value):
        raise ValueError(f"Invalid foreign {title}")
    return value


def _strict_schema(value: Any) -> dict:
    if not isinstance(value, dict) or not value or set(value) - {
            "predicates", "attributes", "classes", "disjoint", "constraints"}:
        raise ValueError("Unsupported foreign schema fields")
    for field in ("predicates", "attributes", "classes"):
        specs = value.get(field, {})
        if not isinstance(specs, dict) or len(specs) > 30 or any(
                not isinstance(k, str) or not k or len(k) > 128
                or not isinstance(v, dict) for k, v in specs.items()):
            raise ValueError("Invalid foreign schema specification")
        for spec in specs.values():
            if field == "predicates":
                if set(spec) - {"arity", "args", "domain", "range", "functional",
                                "irreflexive", "asymmetric", "symmetric"}:
                    raise ValueError("Unsupported predicate schema options")
                if "arity" in spec and (type(spec["arity"]) is not int
                        or not 1 <= spec["arity"] <= 8):
                    raise ValueError("Invalid predicate arity")
                if "args" in spec and (not isinstance(spec["args"], list)
                        or len(spec["args"]) > 8
                        or any(not isinstance(t, str) or t not in TYPE_OK
                               for t in spec["args"])):
                    raise ValueError("Invalid predicate argument types")
                for key in ("domain", "range"):
                    if key in spec:
                        _bounded_text(spec[key], key, maximum=128)
                for key in ("functional", "irreflexive", "asymmetric", "symmetric"):
                    if key in spec and type(spec[key]) is not bool:
                        raise ValueError("Invalid predicate rule")
            elif field == "attributes":
                if set(spec) - {"datatype", "min", "max", "functional", "enum"}:
                    raise ValueError("Unsupported attribute schema options")
                if "datatype" in spec and (not isinstance(spec["datatype"], str)
                        or spec["datatype"] not in TYPE_OK):
                    raise ValueError("Invalid attribute datatype")
                for key in ("min", "max"):
                    if key in spec and (type(spec[key]) not in (int, float)
                            or not -1_000_000 <= spec[key] <= 1_000_000):
                        raise ValueError("Invalid attribute bound")
                if "functional" in spec and type(spec["functional"]) is not bool:
                    raise ValueError("Invalid attribute functional flag")
                if "enum" in spec and (not isinstance(spec["enum"], list)
                        or len(spec["enum"]) > 50
                        or any(not isinstance(item, str) or len(item) > 128
                               for item in spec["enum"])):
                    raise ValueError("Invalid attribute enumeration")
            else:
                if set(spec) - {"required"} or ("required" in spec and (
                        not isinstance(spec["required"], list)
                        or len(spec["required"]) > 30 or any(
                            not isinstance(k, str) or not k or len(k) > 128
                            for k in spec["required"]))):
                    raise ValueError("Invalid class schema requirements")
    disjoint = value.get("disjoint", [])
    if not isinstance(disjoint, list) or len(disjoint) > 30 or any(
            not isinstance(pair, list) or len(pair) != 2 or
            any(not isinstance(key, str) or not key or len(key) > 128
                for key in pair) for pair in disjoint):
        raise ValueError("Invalid foreign schema disjointness")
    constraints = value.get("constraints", [])
    if not isinstance(constraints, list) or len(constraints) > 20 or any(
            not isinstance(src, str) or not src or len(src) > 512
            for src in constraints):
        raise ValueError("Invalid foreign schema constraint")
    # Parsing KSF constraints on a detached object catches malformed grammar
    # before exposing an approval button, without touching the local KB.
    Schema.from_dict(copy.deepcopy(value))
    return copy.deepcopy(value)


def _review_metadata(kb: Any, op: str, data: dict) -> dict:
    if op == "alias":
        if set(data) != {"alias", "canonical"}:
            raise ValueError("Unsupported foreign alias payload")
        alias = _bounded_text(data["alias"], "alias", maximum=128)
        canonical = _bounded_text(data["canonical"], "canonical", maximum=128)
        if alias == canonical or alias in kb.state.aliases:
            raise ValueError("Foreign alias conflicts with an existing local binding")
        seen = {alias}
        cursor = canonical
        while cursor in kb.state.aliases:
            if cursor in seen:
                raise ValueError("Foreign alias would introduce a cycle")
            seen.add(cursor)
            cursor = kb.state.aliases[cursor]
        if cursor == alias:
            raise ValueError("Foreign alias would introduce a cycle")
        return {"alias": alias, "canonical": canonical}
    if op == "label":
        if set(data) != {"key", "label"}:
            raise ValueError("Unsupported foreign label payload")
        key = _bounded_text(data["key"], "label key", maximum=128)
        label = _bounded_text(data["label"], "label", maximum=256)
        if key in kb.state.labels:
            raise ValueError("Existing local label requires a different human decision")
        return {"key": key, "label": label}
    if op == "schema":
        if set(data) != {"schema"}:
            raise ValueError("Unsupported foreign schema payload")
        schema = _strict_schema(data["schema"])
        if schema in kb.state.schema_ext:
            raise ValueError("Identical schema already applied locally")
        return {"schema": schema}
    raise ValueError("Foreign operation has no reviewed local translation")


def plan_foreign_metadata(kb: Any, local_text: str, foreign_text: str,
                          fingerprint: str, event_id: str, local_head: str | None,
                          remote_sha: str) -> dict:
    event, comparison = verified_foreign_event(
        local_text, foreign_text, fingerprint, event_id, local_head, remote_sha)
    if event.op not in MANUAL_METADATA_OPS:
        raise ValueError("Foreign operation requires a different review workflow")
    data = _review_metadata(kb, event.op, event.data)
    canonical = canonical_json({"op": event.op, "data": data, "event_id": event.id,
                                "local_head": local_head, "remote_sha": remote_sha})
    if len(canonical.encode("utf-8")) > MAX_METADATA_BYTES:
        raise ValueError("Foreign metadata exceeds human review size bound")
    return {
        "operation": event.op,
        "data": data,
        "plan_sha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
        "remote_event_id": event.id,
        "remote_sha256": comparison.remote_sha256,
        "remote_head": comparison.remote_head,
        "local_head": local_head,
        "common_head": comparison.common_head,
        "risk": "global_schema_change" if event.op == "schema" else "metadata_semantics",
        "requires_human_confirmation": True,
        "read_only": True,
    }


def commit_foreign_metadata(kb: Any, local_text: str, foreign_text: str,
                            fingerprint: str, event_id: str, local_head: str | None,
                            remote_sha: str, expected_plan_sha: str) -> dict:
    if not isinstance(expected_plan_sha, str) or len(expected_plan_sha) != 64:
        raise ValueError("Missing exact preview commitment")
    plan = plan_foreign_metadata(kb, local_text, foreign_text, fingerprint,
                                 event_id, local_head, remote_sha)
    if plan["plan_sha256"] != expected_plan_sha or kb.journal.head != local_head:
        raise ValueError("Manual review went stale; preview again")
    data = copy.deepcopy(plan["data"])
    data["foreign_causal"] = {
        "peer_fingerprint": fingerprint,
        "remote_sha256": remote_sha,
        "remote_head": plan["remote_head"],
        "remote_event_id": event_id,
        "common_head": plan["common_head"],
        "local_head_at_review": local_head,
        "review_kind": "individual_manual_metadata_approval_v1",
    }
    kb.ws.init()
    saved, _, _ = kb._commit(plan["operation"], data)
    if kb.journal.verify():
        raise ValueError("Local metadata translation did not verify")
    return {"operation": plan["operation"], "journal_id": saved.id,
            "journal_head": kb.journal.head, "foreign_causal": data["foreign_causal"],
            "applied": True, "automatic_replay": False}
