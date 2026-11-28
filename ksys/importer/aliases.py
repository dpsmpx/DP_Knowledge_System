"""Strict, read-only suggestions backed by explicitly recorded human aliases.

This is NOT a general entity resolver. In particular, identical spellings,
wikilinks, semantic similarity, imported peer claims and alias chains cannot
authorize identity. Only a verified owner's direct, unambiguous journal alias
can explain a one-symbol substitution in an otherwise identical claim key.
"""
from __future__ import annotations

import json
from dataclasses import dataclass

from ..core.ids import canonical_json
from ..ir.terms import SYMBOL_RE

MAX_ALIAS_OPERATIONS = 128
MAX_ALIASES_PER_CANONICAL = 8
MAX_SOURCE_ATOMS = 512
MAX_VARIANTS = 256


@dataclass(frozen=True)
class AliasEvidence:
    canonical: str
    journal_id: str | None


@dataclass
class AliasSnapshot:
    by_symbol: dict[str, AliasEvidence]
    groups: dict[str, tuple[str, ...]]
    limited: bool = False


def verified_owner_aliases(entries, owner_actor: str) -> AliasSnapshot:
    """Use only entries whose hash chain was verified by the caller.

    Rebinding, cyclic/transitive references and oversized groups fail closed.
    Repeating the *same* owner decision is allowed, but its first journal ID
    remains the stable reference. Non-owner alias operations are ignored.
    """
    direct: dict[str, tuple[str, str]] = {}
    ambiguous: set[str] = set()
    limited = False
    count = 0
    for entry in entries:
        if entry.op not in ("alias", "unalias") or entry.actor != owner_actor:
            continue
        count += 1
        if count > MAX_ALIAS_OPERATIONS:
            return AliasSnapshot({}, {}, True)  # no partial trust snapshot
        data = entry.data
        if not isinstance(data, dict):
            continue
        name, target = data.get("alias"), data.get("canonical")
        if entry.op == "unalias":
            # Only the exact decision can be withdrawn. A forged/stale
            # retraction does not remove somebody else's alias decision.
            if (isinstance(name, str) and name in direct
                    and direct[name] == (target, data.get("decision_id"))):
                direct.pop(name)
            continue
        if not isinstance(name, str) or not isinstance(target, str):
            continue
        valid = (1 <= len(name) <= 128 and 1 <= len(target) <= 128
                 and SYMBOL_RE.fullmatch(name) and SYMBOL_RE.fullmatch(target)
                 and name != target and isinstance(entry.id, str)
                 and entry.id.startswith("J:") and len(entry.id) <= 96)
        if not valid:
            if isinstance(name, str):
                ambiguous.add(name)
            continue
        if name in direct and direct[name][0] != target:
            ambiguous.add(name)
        else:
            direct.setdefault(name, (target, entry.id))
    for name in ambiguous:
        direct.pop(name, None)

    # If A→B and B→C were recorded, neither step participates in this
    # conservative one-step comparison (same for cycles and longer chains).
    involved_in_chain = {name for name, (target, _) in direct.items()
                         if target in direct}
    involved_in_chain.update(direct[name][0] for name in tuple(involved_in_chain))

    grouped: dict[str, list[tuple[str, str]]] = {}
    for name, (target, journal_id) in direct.items():
        # Refuse chains (including cycles), even if all individual entries
        # were legitimate; they need a separate explicit human review.
        if name in involved_in_chain or target in direct or target in ambiguous:
            continue
        grouped.setdefault(target, []).append((name, journal_id))
    by_symbol: dict[str, AliasEvidence] = {}
    groups: dict[str, tuple[str, ...]] = {}
    for canonical, aliases in sorted(grouped.items()):
        if len(aliases) > MAX_ALIASES_PER_CANONICAL:
            limited = True
            continue
        ordered = sorted(aliases)
        groups[canonical] = (canonical, *(name for name, _ in ordered))
        by_symbol[canonical] = AliasEvidence(canonical, None)
        for name, journal_id in ordered:
            by_symbol[name] = AliasEvidence(canonical, journal_id)
    return AliasSnapshot(by_symbol, groups, limited)


def one_symbol_variants(atom_key: str, aliases: AliasSnapshot):
    """Yield alternate full keys, never rewriting sources or combining aliases.

    Each variant changes exactly one symbolic argument and preserves the
    original predicate, other arguments, context, interval and polarity.
    """
    try:
        payload = json.loads(atom_key)
    except (ValueError, TypeError):
        return
    if (not isinstance(payload, dict)
            or set(payload) != {"p", "args", "ctx", "iv"}
            or not isinstance(payload["args"], list)):
        return
    for position, arg in enumerate(payload["args"]):
        if not isinstance(arg, dict) or arg.get("k") != "sym":
            continue
        source = arg.get("v")
        evidence = aliases.by_symbol.get(source) if isinstance(source, str) else None
        if evidence is None:
            continue
        for other in aliases.groups[evidence.canonical]:
            if other == source:
                continue
            other_evidence = aliases.by_symbol[other]
            variant = dict(payload)
            args = list(payload["args"])
            args[position] = {"k": "sym", "v": other}
            variant["args"] = args
            key = canonical_json(variant)
            if len(key) > 2048:
                continue
            journal_ids = tuple(sorted({j for j in (
                evidence.journal_id, other_evidence.journal_id) if j}))
            if not journal_ids:
                continue
            yield key, {
                "argument_position": position + 1,
                "source_symbol": source,
                "other_symbol": other,
                "canonical_symbol": evidence.canonical,
                "journal_ids": journal_ids,
            }
