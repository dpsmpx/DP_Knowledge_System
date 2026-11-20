"""Conservative, source-provenanced relationships between unapproved claims.

Strict agreement/conflict candidates require the same predicate, arguments,
context and *exact* time interval. A separate, non-conflict relationship may
indicate that two different, explicitly bounded date intervals overlap.
Neither matching names nor intersecting periods prove identity or truth.

This module writes only into the disposable SQLite projection. It never reads
or mutates the human knowledge journal and never changes original Markdown.
"""
from __future__ import annotations

import json

from ..core.errors import KSError
from ..core.ids import canonical_json
from ..ir.terms import Interval
from ..store.db import connect
from ..store.workspace import Workspace

RELATIONS_VERSION = "exact-claim-context-temporal-overlap-v3"


def _qualifiers(assertion: dict) -> tuple[str | None, dict] | None:
    """Canonicalize only valid, explicitly represented claim qualifiers.

    Missing context and interval mean *unspecified*, never a wildcard for
    differently qualified claims. An invalid annotation cannot be ignored:
    doing so could create a false cross-document agreement or conflict.
    """
    context = assertion.get("ctx")
    if "ctx" in assertion and (not isinstance(context, str) or not context.strip()):
        return None
    bounds = {"from": None, "to": None}
    if "iv" in assertion:
        raw = assertion["iv"]
        if not isinstance(raw, dict) or set(raw) != {"from", "to"}:
            return None
        if any(value is not None and not isinstance(value, str) for value in raw.values()):
            return None
        try:
            interval = Interval(raw["from"], raw["to"])
        except (KSError, TypeError, ValueError):
            return None
        if interval.unbounded:
            return None  # well-formed IR omits an unbounded interval
        bounds = interval.to_dict()
    return context, bounds


def projected_atoms(candidate: dict, source_path: str, fragment_id: str,
                    locator: str) -> list[tuple]:
    """Project only fully specified asserted claim atoms from a reading."""
    rows = []
    seen = set()
    for node in candidate.get("nodes", []):
        if not isinstance(node, dict) or node.get("type") != "claim":
            continue
        assertion = node.get("assertion")
        if not isinstance(assertion, dict) or assertion.get("mod") != "asserted" \
                or not isinstance(assertion.get("neg"), bool):
            continue
        atom = assertion.get("atom")
        if not isinstance(atom, dict):
            continue
        predicate, args = atom.get("p"), atom.get("args")
        if not isinstance(predicate, str) or not predicate or not isinstance(args, list):
            continue
        if not args or any(not isinstance(a, dict) or a.get("k") not in ("sym", "num", "str")
                           or not isinstance(a.get("v"), str) for a in args):
            continue
        qualifiers = _qualifiers(assertion)
        if qualifiers is None:
            continue
        context, interval = qualifiers
        signature = canonical_json({"p": predicate, "args": args,
                                    "ctx": context, "iv": interval})
        scope_key = canonical_json({"p": predicate, "args": args, "ctx": context})
        if len(signature) > 2048 or len(scope_key) > 2048:
            continue  # never allocate unbounded SQL index keys from note text
        key = (signature, int(assertion["neg"]))
        if key in seen:
            continue
        seen.add(key)
        rows.append((candidate["id"], fragment_id, source_path, locator,
                     signature, predicate, int(assertion["neg"]), scope_key,
                     interval["from"], interval["to"]))
    return rows


def atom_qualifiers(atom_key: str) -> dict:
    """Read display-only qualifiers from a derived atom key; tolerate old keys."""
    try:
        payload = json.loads(atom_key)
    except (ValueError, TypeError):
        payload = None
    if not isinstance(payload, dict):
        return {"context": None, "interval": None}
    context = payload.get("ctx")
    interval = payload.get("iv")
    return {"context": context if isinstance(context, str) else None,
            "interval": interval if isinstance(interval, dict) else None}


def related_claims(workspace: Workspace, source_path: str, limit: int = 60) -> dict:
    """Return possible agreements/conflicts with paired provenance locators.

    The result contains no assertions of truth. Returned source paths and
    fragment IDs are app-private UI data, never telemetry or publication data.
    """
    if not isinstance(source_path, str) or not source_path \
            or source_path.startswith("/") or ".." in source_path.split("/") \
            or "\\" in source_path or not source_path.lower().endswith((".md", ".markdown")):
        raise ValueError("Invalid Markdown path")
    if not 1 <= limit <= 100:
        raise ValueError("Relation page size out of bounds")
    if not workspace.db_path.is_file():
        return {"matches": [], "truncated": False}
    con = connect(workspace.db_path)
    try:
        return indexed_related_claims(con, source_path, limit)
    finally:
        con.close()


def indexed_related_claims(con, source_path: str, limit: int = 60) -> dict:
    """Bounded read-only relation query for both the library and editor.

    Exact matches retain their existing interpretation. Temporal overlaps are
    reported only as a distinct human-review hint, never as contradictions or
    agreements; the authoritative audit continues to use exact keys only.
    """
    select = (
        "SELECT DISTINCT b.doc, a.locator, b.locator, a.fragment_id, "
        "b.fragment_id, a.negated, b.negated, a.predicate, a.atom_key, b.atom_key, "
        "a.valid_from, a.valid_to, b.valid_from, b.valid_to "
    )
    documents = (
        "JOIN documents d1 ON d1.path=a.doc AND d1.status='PARSED' "
        "JOIN documents d2 ON d2.path=b.doc AND d2.status='PARSED' "
    )
    tail = ("WHERE a.doc=? AND b.doc<>? "
            "ORDER BY b.doc COLLATE NOCASE, b.locator, a.locator, "
            "a.fragment_id, b.fragment_id LIMIT ?")
    exact = con.execute(
        select + "FROM candidate_atoms a JOIN candidate_atoms b ON a.atom_key=b.atom_key "
        + documents + tail, (source_path, source_path, limit + 1)
    ).fetchall()
    # Both intervals must carry actual time bounds: absent means unspecified,
    # NOT eternity. ISO YYYY-MM-DD sorts chronologically in SQLite text order.
    temporal_filter = (
        "AND a.atom_key<>b.atom_key "
        "AND (a.valid_from IS NOT NULL OR a.valid_to IS NOT NULL) "
        "AND (b.valid_from IS NOT NULL OR b.valid_to IS NOT NULL) "
        "AND (a.valid_to IS NULL OR b.valid_from IS NULL OR a.valid_to>=b.valid_from) "
        "AND (b.valid_to IS NULL OR a.valid_from IS NULL OR b.valid_to>=a.valid_from) "
    )
    temporal = con.execute(
        select + "FROM candidate_atoms a JOIN candidate_atoms b ON a.scope_key=b.scope_key "
        + documents + "WHERE a.doc=? AND b.doc<>? " + temporal_filter
        + "ORDER BY b.doc COLLATE NOCASE, b.locator, a.locator, "
          "a.fragment_id, b.fragment_id LIMIT ?",
        (source_path, source_path, limit + 1)
    ).fetchall()

    matches = []
    for r in exact:
        matches.append({
            "path": r[0], "source_locator": r[1], "other_locator": r[2],
            "source_fragment": r[3], "other_fragment": r[4],
            "kind": "possible_conflict" if r[5] != r[6] else "possible_agreement",
            "predicate": r[7], **atom_qualifiers(r[8]),
        })
    for r in temporal:
        # Defensive validation of a possibly corrupted derived SQLite row.
        try:
            source_interval = Interval(r[10], r[11])
            other_interval = Interval(r[12], r[13])
        except (KSError, TypeError, ValueError):
            continue
        overlap = source_interval.intersect(other_interval)
        source_qual = atom_qualifiers(r[8])
        other_qual = atom_qualifiers(r[9])
        if (source_interval.unbounded or other_interval.unbounded or overlap is None
                or source_qual["context"] != other_qual["context"]
                or source_qual["interval"] != source_interval.to_dict()
                or other_qual["interval"] != other_interval.to_dict()):
            continue
        matches.append({
            "path": r[0], "source_locator": r[1], "other_locator": r[2],
            "source_fragment": r[3], "other_fragment": r[4],
            "kind": "temporal_overlap", "predicate": r[7],
            "opposing_polarity": r[5] != r[6],
            "context": source_qual["context"],
            "interval": source_interval.to_dict(),
            "other_interval": other_interval.to_dict(),
            "overlap_interval": overlap.to_dict(),
        })
    matches.sort(key=lambda m: (m["path"].casefold(), m["other_locator"],
                                m["source_locator"], m["kind"],
                                m["source_fragment"], m["other_fragment"]))
    return {"matches": matches[:limit], "truncated": len(matches) > limit}
