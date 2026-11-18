"""Whole-library audit of indexed sources and human-approved knowledge.

All calculations are deterministic and local. Unapproved candidate conflicts
are kept separate from contradictions established in the authoritative
knowledge layer. No negative conclusion follows from absent evidence.
"""
from __future__ import annotations

from ..core.clock import SystemClock
from ..query import smt
from ..query.answers import Answerer
from ..store.db import connect
from ..store.kb import KnowledgeBase
from ..store.workspace import Workspace

PAGE_SIZE = 40
AUDIT_CATEGORIES = (
    "authoritative_conflicts", "source_conflicts", "ambiguous",
    "unresolved", "unparsed", "source_problems", "knowledge_gaps",
)

_FRAGMENT_FILTERS = {
    "ambiguous": "f.cues LIKE '%cnl:ambiguous%'",
    "unresolved": "(f.kind='UNRESOLVED' OR f.cues LIKE '%cnl:unresolved%')",
    "unparsed": "f.cues LIKE '%cnl:no_parse:%'",
}
_SOURCE_PROBLEMS = (
    "d.status<>'PARSED' OR (d.error IS NOT NULL AND d.error NOT IN ('', '[]'))"
)
_SOURCE_CONFLICT_GROUPS = (
    "FROM candidate_atoms a JOIN documents d ON d.path=a.doc "
    "WHERE d.status='PARSED' GROUP BY a.atom_key "
    "HAVING MIN(a.negated)=0 AND MAX(a.negated)=1"
)


def _verified_kb(workspace: Workspace) -> KnowledgeBase:
    kb = KnowledgeBase(workspace, SystemClock(), use_cache=False)
    if kb.journal.verify():
        raise ValueError("Private knowledge journal integrity check failed")
    return kb


def _journal_gaps(answerer: Answerer) -> list[dict]:
    gaps = []
    for item in answerer.schema_issues():
        gaps.append({"type": "schema", "reference": str(item.get("location") or ""),
                     "detail": str(item.get("kind", "")) + ": " + str(item.get("message", ""))[:180]})
    unknown = answerer.unknowns()
    for item in unknown["open_hypotheses"]:
        gaps.append({"type": "open_hypothesis", "reference": item["id"],
                     "detail": item["status"] + ": " + item["ksf"][:180]})
    for item in unknown["unsupported_decision_bases"]:
        gaps.append({"type": "unsupported_decision_basis", "reference": item["decision"],
                     "detail": item["unsupported_basis"]})
    for field, kind in (("unrated_confidence", "unrated_confidence"),
                        ("claims_not_reviewed", "unreviewed_claim"),
                        ("deferred_candidates", "deferred_candidate")):
        for reference in unknown[field]:
            gaps.append({"type": kind, "reference": str(reference), "detail": ""})
    return gaps


def _source_conflict_count(con) -> int:
    return con.execute(
        "SELECT COUNT(*) FROM (SELECT a.atom_key " + _SOURCE_CONFLICT_GROUPS + ")"
    ).fetchone()[0]


def audit_summary(workspace: Workspace, manifest: dict, seen_paths: set[str]) -> dict:
    """Full indexed-source review, with explicit incomplete-scan disclosures."""
    con = connect(workspace.db_path)
    try:
        indexed = {row[0] for row in con.execute("SELECT path FROM documents")}
        statuses = dict(con.execute(
            "SELECT status, COUNT(*) FROM documents GROUP BY status ORDER BY status"
        ).fetchall())
        forms = dict(con.execute(
            "SELECT formalization, COUNT(*) FROM fragments GROUP BY formalization"
        ).fetchall())
        counts = {
            "source_conflicts": _source_conflict_count(con),
            "source_problems": con.execute(
                "SELECT COUNT(*) FROM documents d WHERE " + _SOURCE_PROBLEMS
            ).fetchone()[0],
        }
        for category, condition in _FRAGMENT_FILTERS.items():
            counts[category] = con.execute(
                "SELECT COUNT(*) FROM fragments f JOIN documents d ON d.path=f.doc "
                "WHERE d.status='PARSED' AND " + condition
            ).fetchone()[0]
        total_candidates = con.execute("SELECT COUNT(*) FROM candidates").fetchone()[0]
        total_fragments = con.execute("SELECT COUNT(*) FROM fragments").fetchone()[0]
    finally:
        con.close()

    journal_status = "VERIFIED"
    solver = {"status": "NOT_CHECKED"}
    try:
        kb = _verified_kb(workspace)
    except Exception:
        # A damaged or unreadable journal must never be silently treated as
        # empty or consistent. Source-level diagnostics remain available.
        journal_status = "INVALID_OR_UNREADABLE"
        counts["authoritative_conflicts"] = None
        counts["knowledge_gaps"] = None
    else:
        try:
            answers = Answerer(kb)
            counts["authoritative_conflicts"] = len(answers.contradictions("auth"))
            counts["knowledge_gaps"] = len(_journal_gaps(answers))
        except Exception:
            journal_status = "AUTHORITATIVE_AUDIT_FAILED"
            counts["authoritative_conflicts"] = None
            counts["knowledge_gaps"] = None
        else:
            try:
                solver = smt.consistency(kb.auth)
            except Exception:
                solver = {"status": "UNAVAILABLE"}

    skipped = int(manifest["totals"]["files"]) - int(
        manifest["totals"]["by_status"].get("PARSED", 0))
    unseen = len(indexed - seen_paths)
    scan_complete = not (manifest["truncated"] or manifest["unreadable_directories"]
                         or skipped or manifest["totals"]["documents_with_errors"] or unseen)
    return {
        "counts": {k: counts[k] for k in AUDIT_CATEGORIES},
        "indexed_documents": len(indexed),
        "indexed_fragments": total_fragments,
        "candidate_readings": total_candidates,
        "document_status": statuses,
        "formalization": forms,
        "seen_in_latest_scan": len(seen_paths),
        "not_seen_in_latest_scan": unseen,
        "skipped_this_scan": skipped,
        "skipped_examples": [
            {"path": entry["path"], "status": entry["status"]}
            for entry in manifest["files"] if entry["status"] != "PARSED"
        ][:12],
        "truncated": bool(manifest["truncated"]),
        "unreadable_directories": int(manifest["unreadable_directories"]),
        "coverage_failures": int(manifest["totals"]["documents_with_errors"]),
        "scan_complete": bool(scan_complete),
        "journal_integrity": journal_status,
        "numeric_consistency": {"status": solver.get("status", "UNKNOWN"),
                                "backend": solver.get("backend", "unknown")},
        "scope": "indexed_sources_and_human_approved_knowledge",
        "semantic_limit": "controlled_language_and_exact_structured_claim_atoms_only",
        "no_findings_does_not_mean_consistent": True,
    }


def _fragment_page(con, category: str, offset: int) -> list[dict]:
    rows = con.execute(
        "SELECT f.doc, f.id, f.locator, f.plain, f.kind, f.formalization, f.cues "
        "FROM fragments f JOIN documents d ON d.path=f.doc "
        "WHERE d.status='PARSED' AND " + _FRAGMENT_FILTERS[category] +
        " ORDER BY f.doc COLLATE NOCASE, f.ordinal, f.id LIMIT ? OFFSET ?",
        (PAGE_SIZE + 1, offset),
    ).fetchall()
    return [
        {"path": doc, "fragment_id": fid, "locator": locator,
         "preview": plain[:180], "kind": kind, "formalization": form,
         "cues": cues[:240]}
        for doc, fid, locator, plain, kind, form, cues in rows
    ]


def _source_conflict_page(con, offset: int) -> list[dict]:
    rows = con.execute(
        "SELECT a.atom_key, MIN(a.predicate) " + _SOURCE_CONFLICT_GROUPS +
        " ORDER BY a.atom_key LIMIT ? OFFSET ?", (PAGE_SIZE + 1, offset)
    ).fetchall()
    out = []
    for atom_key, predicate in rows:
        sides = []
        for neg in (0, 1):
            evidence = con.execute(
                "SELECT a.doc, a.locator, a.fragment_id "
                "FROM candidate_atoms a JOIN documents d ON d.path=a.doc "
                "WHERE d.status='PARSED' AND a.atom_key=? AND a.negated=? "
                "ORDER BY a.doc COLLATE NOCASE, a.locator LIMIT 3",
                (atom_key, neg),
            ).fetchall()
            sides.append([{"path": row[0], "locator": row[1],
                           "fragment_id": row[2]} for row in evidence])
        out.append({"type": "possible_source_conflict", "predicate": predicate,
                    "atom": atom_key[:320], "positive_sources": sides[0],
                    "negative_sources": sides[1]})
    return out


def _first_document(basis: list) -> str:
    for entry in basis:
        for provenance in entry.get("provenance", []):
            path = provenance.get("document")
            if isinstance(path, str) and not path.startswith("/") \
                    and ".." not in path.split("/"):
                return path
    return ""


def _authoritative_conflicts(kb: KnowledgeBase) -> list[dict]:
    return [
        {"type": "authoritative_conflict",
         "positive": row["positive"]["ksf"], "negative": row["negative"]["ksf"],
         "path": _first_document(row["positive_basis"]),
         "other_path": _first_document(row["negative_basis"]),
         "positive_basis_count": len(row["positive_basis"]),
         "negative_basis_count": len(row["negative_basis"])}
        for row in Answerer(kb).contradictions("auth")
    ]


def audit_page(workspace: Workspace, category: str, offset: int) -> dict:
    """Read a bounded issue page with source provenance; no source mutations."""
    if category not in AUDIT_CATEGORIES or not isinstance(offset, int) \
            or offset < 0 or offset > 1_000_000:
        raise ValueError("Invalid audit category or offset")
    if not workspace.db_path.is_file():
        return {"category": category, "offset": offset, "findings": [], "has_more": False}
    if category in ("authoritative_conflicts", "knowledge_gaps"):
        kb = _verified_kb(workspace)
        findings = (_authoritative_conflicts(kb) if category == "authoritative_conflicts"
                    else _journal_gaps(Answerer(kb)))
        page = findings[offset:offset + PAGE_SIZE + 1]
    else:
        con = connect(workspace.db_path)
        try:
            if category == "source_conflicts":
                page = _source_conflict_page(con, offset)
            elif category == "source_problems":
                rows = con.execute(
                    "SELECT d.path, d.status, d.size, d.error FROM documents d "
                    "WHERE " + _SOURCE_PROBLEMS +
                    " ORDER BY d.path COLLATE NOCASE LIMIT ? OFFSET ?",
                    (PAGE_SIZE + 1, offset),
                ).fetchall()
                page = [{"path": p, "status": s, "size": n,
                         "preview": (err or "")[:180]} for p, s, n, err in rows]
            else:
                page = _fragment_page(con, category, offset)
        finally:
            con.close()
    return {"category": category, "offset": offset, "findings": page[:PAGE_SIZE],
            "has_more": len(page) > PAGE_SIZE}
