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
import sqlite3
from datetime import date

from ..core.errors import KSError
from ..core.ids import canonical_json
from ..ir.terms import Interval
from ..store.db import connect
from ..store.workspace import Workspace
from .aliases import (AliasSnapshot, MAX_SOURCE_ATOMS, MAX_VARIANTS,
                      one_symbol_variants)

RELATIONS_VERSION = "exact-claim-context-temporal-modality-v4"
MAX_EVIDENCE_GROUPS = 16
MAX_EVIDENCE_DOCUMENTS = 32
MAX_EVIDENCE_EXCERPT_CHARS = 240


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


def _projectable_signature(assertion: dict) -> tuple | None:
    """Shared exact key validation for disjoint assertion/modality projections."""
    if not isinstance(assertion, dict) or not isinstance(assertion.get("neg"), bool):
        return None
    atom = assertion.get("atom")
    if not isinstance(atom, dict):
        return None
    predicate, args = atom.get("p"), atom.get("args")
    if not isinstance(predicate, str) or not predicate or not isinstance(args, list):
        return None
    if not args or any(not isinstance(a, dict) or a.get("k") not in ("sym", "num", "str")
                       or not isinstance(a.get("v"), str) for a in args):
        return None
    qualifiers = _qualifiers(assertion)
    if qualifiers is None:
        return None
    context, interval = qualifiers
    signature = canonical_json({"p": predicate, "args": args,
                                "ctx": context, "iv": interval})
    scope_key = canonical_json({"p": predicate, "args": args, "ctx": context})
    if len(signature) > 2048 or len(scope_key) > 2048:
        return None
    return signature, scope_key, predicate, int(assertion["neg"]), interval


def projected_atoms(candidate: dict, source_path: str, fragment_id: str,
                    locator: str) -> list[tuple]:
    """Project only fully specified asserted claim atoms from a reading."""
    rows = []
    seen = set()
    for node in candidate.get("nodes", []):
        if not isinstance(node, dict) or node.get("type") != "claim":
            continue
        assertion = node.get("assertion")
        if not isinstance(assertion, dict) or assertion.get("mod") != "asserted":
            continue
        projected = _projectable_signature(assertion)
        if projected is None:
            continue
        signature, scope_key, predicate, negated, interval = projected
        key = (signature, negated)
        if key in seen:
            continue
        seen.add(key)
        rows.append((candidate["id"], fragment_id, source_path, locator,
                     signature, predicate, negated, scope_key,
                     interval["from"], interval["to"]))
    return rows


def projected_modal_atoms(candidate: dict, source_path: str, fragment_id: str,
                          locator: str) -> list[tuple]:
    """Separate unapproved modality/hypothesis hints, never exact-claim votes.

    Asserted `claim` nodes stay solely in `candidate_atoms`. Modal or
    explicitly hypothetical nodes are indexed for *contrast* only; these may
    not enter exact conflict, agreement, evidence-family or audit queries.
    """
    rows = []
    seen = set()
    for node in candidate.get("nodes", []):
        if not isinstance(node, dict) or node.get("type") not in ("claim", "hypothesis"):
            continue
        assertion = node.get("assertion")
        if not isinstance(assertion, dict):
            continue
        modality = assertion.get("mod")
        if modality not in ("asserted", "possible", "usually"):
            continue
        node_kind = node["type"]
        if node_kind == "claim" and modality == "asserted":
            continue
        projected = _projectable_signature(assertion)
        if projected is None:
            continue
        signature, _, predicate, negated, _ = projected
        key = (signature, negated, modality, node_kind)
        if key in seen:
            continue
        seen.add(key)
        rows.append((candidate["id"], fragment_id, source_path, locator,
                     signature, predicate, negated, modality, node_kind))
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


def related_claims(workspace: Workspace, source_path: str, limit: int = 60,
                   aliases: AliasSnapshot | None = None) -> dict:
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
        return {"matches": [], "truncated": False, "alias_search_limited": False,
                "modal_search_limited": True,
                "evidence_groups": [], "evidence_limited": False}
    con = connect(workspace.db_path)
    try:
        return indexed_related_claims(con, source_path, limit, aliases)
    finally:
        con.close()


def indexed_related_claims(con, source_path: str, limit: int = 60,
                           aliases: AliasSnapshot | None = None) -> dict:
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

    # Unlike the overlap hint, an ordering/gap requires *both* endpoints of
    # *both* date ranges to be explicitly recorded. An open or unspecified
    # side is not permission to infer the start/end of a historical version.
    separated_filter = (
        "AND a.atom_key<>b.atom_key "
        "AND a.valid_from IS NOT NULL AND a.valid_to IS NOT NULL "
        "AND b.valid_from IS NOT NULL AND b.valid_to IS NOT NULL "
        "AND (a.valid_to<b.valid_from OR b.valid_to<a.valid_from) "
    )
    separated = con.execute(
        select + "FROM candidate_atoms a JOIN candidate_atoms b ON a.scope_key=b.scope_key "
        + documents + "WHERE a.doc=? AND b.doc<>? " + separated_filter
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
    for r in separated:
        try:
            source_interval = Interval(r[10], r[11])
            other_interval = Interval(r[12], r[13])
        except (KSError, TypeError, ValueError):
            continue
        source_qual = atom_qualifiers(r[8])
        other_qual = atom_qualifiers(r[9])
        if (None in (source_interval.start, source_interval.end,
                     other_interval.start, other_interval.end)
                or source_interval.intersect(other_interval) is not None
                or source_qual["context"] != other_qual["context"]
                or source_qual["interval"] != source_interval.to_dict()
                or other_qual["interval"] != other_interval.to_dict()):
            continue
        source_first = source_interval.end < other_interval.start
        earlier_end = source_interval.end if source_first else other_interval.end
        later_start = other_interval.start if source_first else source_interval.start
        gap_days = (date.fromisoformat(later_start)
                    - date.fromisoformat(earlier_end)).days - 1
        matches.append({
            "path": r[0], "source_locator": r[1], "other_locator": r[2],
            "source_fragment": r[3], "other_fragment": r[4],
            "kind": "temporal_separation", "predicate": r[7],
            "opposing_polarity": r[5] != r[6],
            "context": source_qual["context"],
            "interval": source_interval.to_dict(),
            "other_interval": other_interval.to_dict(),
            "relative_order": ("source_before_other" if source_first
                               else "other_before_source"),
            "gap_days": gap_days,
            # A polarity shift is a question for the owner, not evidence that
            # the world changed between the two source-supplied intervals.
            "version_review": ("opposing_dated_readings" if r[5] != r[6]
                               else "consistent_dated_readings"),
            "earlier_negated": bool(r[5] if source_first else r[6]),
            "later_negated": bool(r[6] if source_first else r[5]),
            "authority": "unapproved_parser_hint",
        })
    alias_matches, alias_limited = indexed_alias_candidates(
        con, source_path, limit, aliases)
    matches.extend(alias_matches)
    modal_matches, modal_limited = indexed_modal_contrasts(con, source_path, limit)
    matches.extend(modal_matches)
    matches.sort(key=lambda m: (m["path"].casefold(), m["other_locator"],
                                m["source_locator"], m["kind"],
                                m["source_fragment"], m["other_fragment"]))
    evidence_groups, evidence_limited = indexed_source_evidence(con, source_path)
    return {"matches": matches[:limit], "truncated": len(matches) > limit,
            "alias_search_limited": alias_limited,
            "modal_search_limited": modal_limited,
            "evidence_groups": evidence_groups, "evidence_limited": evidence_limited}


def indexed_modal_contrasts(con, source_path: str, limit: int) -> tuple[list[dict], bool]:
    """Strictly equal key, differing modality/kind; never a conflict vote.

    Modal and explicit hypothesis nodes are stored apart from asserted claims.
    They are allowed only in this read-only hint, never the audit or evidence
    aggregate. A pre-migration read-only index may lack the table: report
    incomplete coverage and ask for a fresh analysis rather than silently
    treating its absence as proof that no uncertainty exists.
    """
    select = ("SELECT DISTINCT b.doc, a.locator, b.locator, a.fragment_id, "
              "b.fragment_id, {left_mode}, {right_mode}, {left_kind}, {right_kind}, "
              "a.negated, b.negated, a.predicate, a.atom_key ")
    documents = ("JOIN documents d1 ON d1.path=a.doc AND d1.status='PARSED' "
                 "JOIN documents d2 ON d2.path=b.doc AND d2.status='PARSED' ")
    tail = ("WHERE a.doc=? AND b.doc<>? {condition}"
            "ORDER BY b.doc COLLATE NOCASE, b.locator, a.locator, "
            "a.fragment_id, b.fragment_id LIMIT ?")
    joins = (
        ("candidate_modal_atoms", "candidate_atoms", "a.modality", "'asserted'",
         "a.node_kind", "'claim'", ""),
        ("candidate_atoms", "candidate_modal_atoms", "'asserted'", "b.modality",
         "'claim'", "b.node_kind", ""),
        ("candidate_modal_atoms", "candidate_modal_atoms", "a.modality", "b.modality",
         "a.node_kind", "b.node_kind",
         "AND (a.modality<>b.modality OR a.node_kind<>b.node_kind) "),
    )
    rows = []
    limited = False
    try:
        for left, right, left_mode, right_mode, left_kind, right_kind, condition in joins:
            sql = (select.format(left_mode=left_mode, right_mode=right_mode,
                                 left_kind=left_kind, right_kind=right_kind)
                   + f"FROM {left} a JOIN {right} b ON a.atom_key=b.atom_key "
                   + documents + tail.format(condition=condition))
            page = con.execute(sql, (source_path, source_path, limit + 1)).fetchall()
            limited = limited or len(page) > limit
            rows.extend(page[:limit])
    except sqlite3.Error:
        return [], True
    matches = []
    for row in rows:
        if (row[5] not in ("asserted", "possible", "usually") or
                row[6] not in ("asserted", "possible", "usually") or
                row[7] not in ("claim", "hypothesis") or
                row[8] not in ("claim", "hypothesis")):
            continue
        qualifiers = atom_qualifiers(row[12])
        if qualifiers["interval"] is None:
            continue
        matches.append({
            "path": row[0], "source_locator": row[1], "other_locator": row[2],
            "source_fragment": row[3], "other_fragment": row[4],
            "source_modality": row[5], "other_modality": row[6],
            "source_node_kind": row[7], "other_node_kind": row[8],
            "opposing_polarity": row[9] != row[10],
            "predicate": row[11], "kind": "modal_contrast",
            "authority": "unapproved_parser_hint", **qualifiers,
        })
    return matches, limited


def _evidence_excerpts(con, atom_key: str, paths: list[str]) -> dict[str, dict]:
    """Batch one bounded representative per document, never one query per file.

    The indexed text is a historical snapshot until a separate live SHA check
    succeeds. Bounded SQLite substr avoids materializing entire long fragments
    in relation responses. A corrupt cross-document fragment reference cannot
    be presented as this file's excerpt or used to claim a repeated excerpt.
    Android 11+ SQLite supports the window ordering used for deterministic
    per-document representative selection. A second join fetches text *after*
    ranking so repeated parser rows do not multiply excerpt allocations.
    """
    if not 1 <= len(paths) <= MAX_EVIDENCE_DOCUMENTS + 1:
        raise ValueError("Evidence excerpt page limit exceeded")
    placeholders = ",".join("?" for _ in paths)
    rows = con.execute(
        "WITH ranked AS ("
        "SELECT a.doc, a.locator, a.fragment_id, "
        "ROW_NUMBER() OVER (PARTITION BY a.doc "
        "ORDER BY f.ordinal, a.locator, a.fragment_id) AS position "
        "FROM candidate_atoms a JOIN fragments f "
        "ON f.id=a.fragment_id AND f.doc=a.doc "
        "WHERE a.atom_key=? AND a.doc IN (" + placeholders + ")) "
        "SELECT ranked.doc, ranked.locator, ranked.fragment_id, "
        "substr(f.plain, 1, ?), length(f.plain), f.text_hash "
        "FROM ranked JOIN fragments f "
        "ON f.id=ranked.fragment_id AND f.doc=ranked.doc "
        "WHERE ranked.position=1",
        (atom_key, *paths, MAX_EVIDENCE_EXCERPT_CHARS),
    ).fetchall()
    return {row[0]: {"locator": row[1], "fragment_id": row[2],
                     "excerpt": row[3],
                     "excerpt_truncated": row[4] > MAX_EVIDENCE_EXCERPT_CHARS,
                     "excerpt_hash": row[5]} for row in rows}


def indexed_source_evidence(con, source_path: str) -> tuple[list[dict], bool]:
    """Explain exact cross-file evidence without inventing a confidence score.

    Each Markdown path counts at most once; repeated parser readings, copies,
    and fragments in a file cannot inflate the corroboration count. Different
    paths are *not* assumed independent authors or independent observations.
    Mixed-polarity files never count as either support or opposition. The
    index is an unapproved snapshot and cannot establish live-source validity.
    """
    source = con.execute(
        "SELECT a.atom_key, MIN(a.predicate), "
        "MIN(a.negated), MAX(a.negated) "
        "FROM candidate_atoms a JOIN fragments f ON f.id=a.fragment_id "
        "JOIN documents d ON d.path=a.doc "
        "AND d.status='PARSED' WHERE a.doc=? "
        "GROUP BY a.atom_key ORDER BY a.atom_key LIMIT ?",
        (source_path, MAX_EVIDENCE_GROUPS + 1)).fetchall()
    limited = len(source) > MAX_EVIDENCE_GROUPS
    groups = []
    for atom_key, predicate, minimum, maximum in source[:MAX_EVIDENCE_GROUPS]:
        # An exact signature is the predicate + *all* args + context + the
        # precise interval. Alias and temporal-overlap hints stay separate.
        rows = con.execute(
            "SELECT a.doc, MIN(a.negated), MAX(a.negated) "
            "FROM candidate_atoms a JOIN fragments f ON f.id=a.fragment_id "
            "JOIN documents d ON d.path=a.doc "
            "AND d.status='PARSED' WHERE a.atom_key=? AND a.doc<>? "
            "GROUP BY a.doc ORDER BY a.doc COLLATE NOCASE LIMIT ?",
            (atom_key, source_path, MAX_EVIDENCE_DOCUMENTS + 1)).fetchall()
        displayed = rows[:MAX_EVIDENCE_DOCUMENTS]
        excerpts = _evidence_excerpts(con, atom_key, [source_path] +
                                      [doc for doc, _, _ in displayed])
        original = excerpts.get(source_path)
        if original is None:
            limited = True
            continue
        group_limited = len(rows) > MAX_EVIDENCE_DOCUMENTS
        limited = limited or group_limited
        mixed_source = minimum != maximum
        corroborating = opposing = mixed = 0
        evidence = []
        for doc, low, high in displayed:
            excerpt = excerpts.get(doc)
            if low != high:
                kind = "mixed"
                mixed += 1
            elif mixed_source:
                kind = "unclassified"  # no single source polarity to compare
            elif low == minimum:
                kind = "same_polarity"
                corroborating += 1
            else:
                kind = "opposite_polarity"
                opposing += 1
            evidence.append({"path": doc, "locator": excerpt["locator"] if excerpt else "",
                             "relation": kind,
                             "excerpt_available": excerpt is not None,
                             "fragment_id": excerpt["fragment_id"] if excerpt else None,
                             "excerpt": excerpt["excerpt"] if excerpt else None,
                             "excerpt_truncated": excerpt["excerpt_truncated"] if excerpt else False,
                             "excerpt_hash": excerpt["excerpt_hash"] if excerpt else None})
        kinds = sum(bool(x) for x in (corroborating, opposing, mixed))
        groups.append({
            "predicate": predicate, "source_locator": original["locator"],
            "source_fragment_id": original["fragment_id"],
            "source_excerpt": original["excerpt"],
            "source_excerpt_truncated": original["excerpt_truncated"],
            "source_excerpt_hash": original["excerpt_hash"],
            "source_polarity": "mixed" if mixed_source else (
                "negative" if minimum else "positive"),
            **atom_qualifiers(atom_key),
            "evidence_pattern": ("source_mixed" if mixed_source else
                "cross_source_mixed" if kinds > 1 else
                "opposition_only" if opposing else
                "same_polarity_only" if corroborating else
                "mixed_other_sources" if mixed else "no_exact_other_sources"),
            "same_polarity_files": corroborating,
            "opposite_polarity_files": opposing,
            "mixed_polarity_files": mixed,
            "indexed_other_files": len(evidence),
            "other_sources": evidence, "limited": group_limited,
            "confidence": "not_assessed", "authority": "unapproved_candidates",
            "source_freshness": "indexed_snapshot_unverified",
        })
    return groups, limited


def indexed_alias_candidates(con, source_path: str, limit: int,
                             aliases: AliasSnapshot | None) -> tuple[list[dict], bool]:
    """Match exactly one explicitly approved symbol substitution via index.

    No key is rewritten in SQLite, no aliases are inferred from note text,
    and neither agreement nor conflict status is assigned to these hints.
    """
    if aliases is None or not aliases.by_symbol:
        return [], aliases.limited if aliases is not None else False
    source = con.execute(
        "SELECT DISTINCT a.atom_key, a.locator, a.fragment_id, a.negated, a.predicate "
        "FROM candidate_atoms a JOIN documents d ON d.path=a.doc AND d.status='PARSED' "
        "WHERE a.doc=? ORDER BY a.locator, a.fragment_id LIMIT ?",
        (source_path, MAX_SOURCE_ATOMS + 1),
    ).fetchall()
    limited = aliases.limited or len(source) > MAX_SOURCE_ATOMS
    variants: dict[str, list] = {}
    for atom_key, locator, fragment, negated, predicate in source[:MAX_SOURCE_ATOMS]:
        for alternate, evidence in one_symbol_variants(atom_key, aliases):
            if alternate not in variants and len(variants) >= MAX_VARIANTS:
                # No arbitrary lexicographic subset masquerading as a complete
                # entity comparison. Preserve exact/temporal results unchanged.
                return [], True
            variants.setdefault(alternate, []).append(
                (atom_key, locator, fragment, negated, predicate, evidence))
    if not variants:
        return [], limited
    placeholders = ",".join("?" for _ in variants)
    other = con.execute(
        "SELECT DISTINCT b.doc, b.locator, b.fragment_id, b.atom_key, b.negated "
        "FROM candidate_atoms b JOIN documents d ON d.path=b.doc AND d.status='PARSED' "
        "WHERE b.atom_key IN (" + placeholders + ") AND b.doc<>? "
        "ORDER BY b.doc COLLATE NOCASE, b.locator, b.fragment_id, b.atom_key LIMIT ?",
        (*variants, source_path, limit + 1),
    ).fetchall()
    if len(other) > limit:
        limited = True
    matches = []
    for doc, locator, fragment, other_key, other_negated in other[:limit]:
        for source_key, source_locator, source_fragment, source_negated, predicate, evidence in variants[other_key]:
            matches.append({
                "path": doc, "source_locator": source_locator,
                "other_locator": locator, "source_fragment": source_fragment,
                "other_fragment": fragment, "kind": "alias_candidate",
                "predicate": predicate, "opposing_polarity": source_negated != other_negated,
                "alias_evidence": evidence, **atom_qualifiers(source_key),
            })
            if len(matches) > limit:
                return matches, True  # bounded even for many source fragments
    return matches, limited
