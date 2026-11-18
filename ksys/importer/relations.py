"""Conservative, source-provenanced relationships between unapproved claims.

Two extracted claims can be compared only if their structured predicate and
arguments match *exactly*. Neither lexical similarity nor a matching name
proves that two entities are identical. A differing negation is a possible
conflict, not an authoritative contradiction.

This module writes only into the disposable SQLite projection. It never reads
or mutates the human knowledge journal and never changes original Markdown.
"""
from __future__ import annotations

from ..core.ids import canonical_json
from ..store.db import connect
from ..store.workspace import Workspace

RELATIONS_VERSION = "exact-claim-atom-v1"


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
        signature = canonical_json({"p": predicate, "args": args})
        if len(signature) > 2048:
            continue  # never allocate unbounded SQL index keys from note text
        key = (signature, int(assertion["neg"]))
        if key in seen:
            continue
        seen.add(key)
        rows.append((candidate["id"], fragment_id, source_path, locator,
                     signature, predicate, int(assertion["neg"])))
    return rows


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
        rows = con.execute(
            "SELECT DISTINCT b.doc, a.locator, b.locator, a.fragment_id, "
            "b.fragment_id, a.negated, b.negated, a.predicate "
            "FROM candidate_atoms a JOIN candidate_atoms b ON a.atom_key=b.atom_key "
            "JOIN documents d1 ON d1.path=a.doc AND d1.status='PARSED' "
            "JOIN documents d2 ON d2.path=b.doc AND d2.status='PARSED' "
            "WHERE a.doc=? AND b.doc<>? "
            "ORDER BY b.doc COLLATE NOCASE, b.locator, a.locator LIMIT ?",
            (source_path, source_path, limit + 1),
        ).fetchall()
    finally:
        con.close()
    return {
        "matches": [
            {"path": r[0], "source_locator": r[1], "other_locator": r[2],
             "source_fragment": r[3], "other_fragment": r[4],
             "kind": "possible_conflict" if r[5] != r[6] else "possible_agreement",
             "predicate": r[7]}
            for r in rows[:limit]
        ],
        "truncated": len(rows) > limit,
    }
