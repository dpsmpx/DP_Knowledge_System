"""Offline Android bridge for the actual repository-root ksys package.

The explicitly selected vault is only read. Automatic analysis writes solely
to an app-private disposable index, never to original notes or the human
approved knowledge journal. Journal writes require a separate review flow.
"""
from __future__ import annotations

import json
import hashlib
import sqlite3
import tempfile
from ksys.store.journal import Entry, entry_id, Journal
from pathlib import Path

from ksys import KS_VERSION
from ksys.core.clock import SystemClock
from ksys.source.markdown import parse_document
from ksys.source.coverage import check_coverage
from ksys.importer.classify import classify
from ksys.importer.language_analyzer import LanguageAnalyzer
from ksys.importer.vault import import_vault, MARKDOWN_EXT
from ksys.importer.relations import related_claims, indexed_related_claims
from ksys.importer.aliases import (verified_owner_aliases, MAX_ALIAS_OPERATIONS,
                                   MAX_ALIASES_PER_CANONICAL)
from ksys.core.ids import canonical_json
from ksys.ir.terms import SYMBOL_RE
from ksys.audit.library import audit_summary, audit_page
from ksys.store.kb import KnowledgeBase, convert_role
from ksys.ir.nodes import node_from_dict
from ksys.core.errors import KSError
from ksys.semantics.formalize import Formalizer
from ksys.store.workspace import Workspace
from ksys.dialogue.session import Dialogue
from ksys.dialogue.intents import classify as classify_intent
from ksys.query.answers import Answerer
from ksys.core.enums import DialogueOp

MAX_CHARACTERS = 2_000_000
MAX_DISPLAYED = 24
MAX_EDITOR_HIGHLIGHTS = 128
MAX_EDITOR_SOURCE_RECORDS = 16
MAX_EDITOR_EVIDENCE_ORIGINS = 12
MAX_EDITOR_EVIDENCE_LINKS = 16
MAX_EDITOR_EVIDENCE_EVENTS = 16
MAX_IMPORT_BYTES = 2_000_000
MAX_IMPORT_FILES = 200_000
MAX_RELATION_PREVIEW_CHARS = 240
MAX_RELATION_PREVIEW_FRAGMENTS = 200  # 2 refs × at most 100 relations


def analyze_vault(workspace_dir: str, vault_dir: str, callback=None) -> str:
    """Classify every accessible Markdown note and extract unapproved candidates.

    The callback receives only a counter, never note bodies or private paths.
    It may return False to abort the transaction without publishing partial
    analysis. Missing documents are retained because an Android storage scan
    cannot prove that files were deleted rather than temporarily inaccessible.
    """
    result = _import_selected(workspace_dir, vault_dir, callback)
    return json.dumps({
        "protocol": "dpksys.bulk.v1",
        "engine_version": KS_VERSION,
        "totals": result.manifest["totals"],
        "truncated": result.manifest["truncated"],
        "unreadable_directories": result.manifest["unreadable_directories"],
        "coverage_failures": len(result.coverage_failures),
        "read_only_sources": True,
        "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _import_selected(workspace_dir: str, vault_dir: str, callback=None):
    root = Path(vault_dir).resolve()
    workspace = Path(workspace_dir).resolve()
    if not Path(vault_dir).is_absolute() or not Path(workspace_dir).is_absolute() \
            or not root.is_dir() or root == workspace \
            or workspace.is_relative_to(root) or root.is_relative_to(workspace):
        raise ValueError("Invalid selected notebook or app-private workspace")
    seen = 0

    def report(_path: str) -> None:
        nonlocal seen
        seen += 1
        if callback is not None and not callback.onProgress(seen):
            raise InterruptedError("Semantic notebook analysis cancelled")

    result = import_vault(
        Workspace(workspace), root, SystemClock(), LanguageAnalyzer(),
        progress=report, only_extensions=MARKDOWN_EXT, skip_hidden=True,
        max_bytes=MAX_IMPORT_BYTES, max_files=MAX_IMPORT_FILES,
        reconcile_missing=False,
        cancelled=(lambda: callback is not None and not callback.onProgress(seen)),
    )
    return result


def full_library_audit(workspace_dir: str, vault_dir: str, callback=None) -> str:
    """Refresh selected source notes then assess the entire private index."""
    imported = _import_selected(workspace_dir, vault_dir, callback)
    seen = {entry["path"] for entry in imported.manifest["files"]}
    result = audit_summary(Workspace(Path(workspace_dir)), imported.manifest, seen)
    return json.dumps({
        "protocol": "dpksys.audit.v1",
        "summary": result,
        "read_only_sources": True,
        "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def full_audit_page(workspace_dir: str, category: str, offset: int) -> str:
    """Query actionable issues in bounded pages, never export to public storage."""
    workspace = Path(workspace_dir)
    if not workspace.is_absolute():
        raise ValueError("Knowledge workspace must be absolute")
    return json.dumps({
        "protocol": "dpksys.audit.v1",
        **audit_page(Workspace(workspace), category, offset),
        "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def review_queue(workspace_dir: str, offset: int = 0) -> str:
    """Page through unapproved semantic suggestions, without a journal write."""
    workspace = Path(workspace_dir)
    if not workspace.is_absolute() or not isinstance(offset, int) \
            or offset < 0 or offset > 1_000_000:
        raise ValueError("Invalid workspace or queue offset")
    con = _decision_index(workspace)
    if con is None:
        return json.dumps({"protocol": "dpksys.bulk.v1", "total": 0, "offset": offset,
                           "candidates": [], "requires_human_review": True})
    kb = _kb(workspace_dir)
    resolved = _resolved_fragments(kb)
    total = 0
    candidates = []
    try:
        rows = con.execute(
            "SELECT c.id, f.doc, f.id, f.locator, c.paraphrase, c.reading_id, c.nodes "
            "FROM candidates c JOIN fragments f ON f.id=c.origin "
            "JOIN documents d ON d.path=f.doc WHERE d.status='PARSED' "
            "ORDER BY f.doc COLLATE NOCASE, f.ordinal, c.id")
        for row in rows:
            if not _candidate_pending(row, resolved, kb.state.rejected):
                continue
            if offset <= total < offset + 80:
                candidates.append({"path": row[1], "fragment_id": row[2],
                                   "locator": row[3], "preview": row[4][:180],
                                   "reading_id": row[5]})
            total += 1
    finally:
        con.close()
    return json.dumps({
        "protocol": "dpksys.bulk.v1",
        "total": total, "offset": offset,
        "candidates": candidates,
        "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def note_relationships(workspace_dir: str, relative_path: str,
                       vault_dir: str | None = None) -> str:
    """Compare unapproved structured assertions between different notes.

    A shared atom is evidence of a candidate relation, never evidence that
    either original claim is true. A temporal overlap is a separate review
    hint, never a conflict verdict. No private note text leaves this process.
    """
    workspace = Path(workspace_dir)
    if not workspace.is_absolute():
        raise ValueError("Knowledge workspace must be absolute")
    ws = Workspace(workspace)
    journal = Journal(ws.journal_path, SystemClock(),
                      actor=ws.config().get("actor", "human:me"))
    if journal.verify():
        raise ValueError("Knowledge journal integrity check failed")
    aliases = verified_owner_aliases(journal.entries(), journal.actor)
    matches = related_claims(ws, relative_path, aliases=aliases)
    evidence_checks_limited = True
    modal_checks_limited = False
    temporal_checks_limited = False
    relation_checks_limited = False
    source_lineage_limited = False
    source_assessment_limited = False
    if matches["evidence_groups"] or matches["matches"]:
        con = _decision_index(workspace)
        if con is not None:
            try:
                (evidence_checks_limited, modal_checks_limited,
                 temporal_checks_limited, relation_checks_limited) = _verify_evidence_freshness(
                    con, vault_dir, relative_path, matches["evidence_groups"],
                    matches["matches"])
                _annotate_relation_previews(con, relative_path, matches["matches"])
                source_lineage_limited = _annotate_reviewed_source_links(
                    con, journal, relative_path, matches["evidence_groups"])
                source_assessment_limited = _annotate_source_assessments(
                    con, journal, relative_path, matches["evidence_groups"])
            finally:
                con.close()
    return json.dumps({
        "protocol": "dpksys.relations.v1",
        "path": relative_path,
        "matches": matches["matches"],
        "truncated": matches["truncated"],
        "alias_search_limited": matches["alias_search_limited"],
        "modal_search_limited": matches["modal_search_limited"],
        "modal_checks_limited": modal_checks_limited,
        "temporal_checks_limited": temporal_checks_limited,
        "relation_checks_limited": relation_checks_limited,
        "evidence_groups": matches["evidence_groups"],
        "evidence_limited": matches["evidence_limited"],
        "evidence_checks_limited": evidence_checks_limited,
        "source_lineage_limited": source_lineage_limited,
        "source_assessment_limited": source_assessment_limited,
        "source": "unapproved_candidate_assertions",
        "read_only": True,
        "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


# Stage 18: deliberate owner-only entity decisions. Sources come from an
# unapproved, disposable projection, so every source must be rechecked against
# the original file's SHA before a preview or journal append.
ENTITY_SOURCE_PAGE = 24
MAX_EVIDENCE_LIVE_CHECKS = 16  # at most 32 MiB of live Markdown I/O per query


def _entity_symbol(symbol: str) -> str:
    if (not isinstance(symbol, str) or not 1 <= len(symbol) <= 128
            or not SYMBOL_RE.fullmatch(symbol)):
        raise ValueError("Use an exact formal symbol (1..128 characters)")
    return symbol


def _entity_index(workspace_dir: str, vault_dir: str):
    workspace, vault = Path(workspace_dir), Path(vault_dir)
    if not workspace.is_absolute() or not vault.is_absolute() or not vault.is_dir():
        raise ValueError("Select an accessible notebook and private knowledge workspace")
    con = _decision_index(workspace)
    if con is None:
        raise ValueError("Analyze the notebook before reviewing entity sources")
    indexed_root = con.execute(
        "SELECT value FROM meta WHERE key='vault_root'").fetchone()
    if not indexed_root or indexed_root[0] != str(vault.resolve()):
        con.close()
        raise ValueError("Notebook changed since analysis; analyze again")
    return con, vault.resolve()


def _entity_live_hash(root: Path, relative_path: str) -> str | None:
    if (not isinstance(relative_path, str) or not relative_path
            or relative_path.startswith("/") or "\\" in relative_path
            or ".." in relative_path.split("/")
            or not relative_path.lower().endswith((".md", ".markdown"))):
        return None
    source = (root / relative_path).resolve()
    if not source.is_relative_to(root) or not source.is_file():
        return None
    try:
        if source.stat().st_size > MAX_IMPORT_BYTES:
            return None
        return hashlib.sha256(source.read_bytes()).hexdigest()
    except (OSError, PermissionError):
        return None


def _verify_evidence_freshness(con, vault_dir: str | None,
                               source_path: str, groups: list[dict],
                               matches: list[dict] | None = None) -> tuple[bool, bool, bool, bool]:
    """Verify a bounded set of current file hashes without indexing or writes.

    The supplied selected notebook must match the import's recorded root;
    otherwise *every* status stays unchecked (never silently current). Cached
    fragment hashes allow only exact-text-repeat notices, never claims of
    independent authorship, truth, or factual corroboration. Each copy family
    marks *every* verified member, including the inspected source and the
    first occurrence. Modality, temporal, exact and reviewed-alias hints reuse
    the *same* 16-file budget, including checks already done for exact evidence.
    Neither new file hashes nor inferred links are stored. SHA freshness is
    not semantic approval, even when a journal-backed alias is active.
    """
    root = None
    if isinstance(vault_dir, str) and Path(vault_dir).is_absolute():
        selected = Path(vault_dir).resolve()
        stored = con.execute("SELECT value FROM meta WHERE key='vault_root'").fetchone()
        if selected.is_dir() and stored and stored[0] == str(selected):
            root = selected
    cache: dict[str, tuple[str, str | None]] = {}
    limited = root is None

    def check(path: str) -> tuple[str, str | None]:
        nonlocal limited
        if path in cache:
            return cache[path]
        if root is None or len(cache) >= MAX_EVIDENCE_LIVE_CHECKS:
            limited = True
            return ("not_checked", None)
        row = con.execute("SELECT sha256 FROM documents WHERE path=? AND status='PARSED'",
                          (path,)).fetchone()
        expected = row[0] if row else None
        live = _entity_live_hash(root, path)
        state = ("unavailable" if live is None or expected is None else
                 "current" if live == expected else "stale")
        cache[path] = (state, expected)
        return cache[path]

    source_state, source_sha = check(source_path)
    for group in groups:
        group["source_verification"] = source_state
        verified = {"same_polarity": 0, "opposite_polarity": 0, "mixed": 0}
        statuses = {"current": 0, "stale": 0, "unavailable": 0, "not_checked": 0}
        current = []
        if source_state == "current":
            current.append({"path": source_path, "source": True,
                            "excerpt_hash": group.get("source_excerpt_hash"),
                            "sha": source_sha})
        for evidence in group["other_sources"]:
            state, sha = check(evidence["path"])
            evidence["verification"] = state
            statuses[state] += 1
            if state != "current":
                continue
            current.append({"path": evidence["path"], "source": False,
                            "item": evidence, "excerpt_hash": evidence.get("excerpt_hash"),
                            "sha": sha})
            if source_state == "current":
                kind = evidence["relation"]
                if kind in verified:
                    verified[kind] += 1

        # Deterministic labels group all current files that match, rather than
        # assigning a copy warning only to the second member of a pair. The
        # representative fragment may be one of several parser readings; no
        # independence estimate is justified from a single such hash.
        def mark_families(hash_key: str, flag: str, label_key: str,
                          source_flag: str, source_label: str, prefix: str):
            buckets: dict[str, list[dict]] = {}
            for record in current:
                value = record[hash_key]
                if isinstance(value, str) and value:
                    buckets.setdefault(value, []).append(record)
            repeating = [members for members in buckets.values() if len(members) > 1]
            repeating.sort(key=lambda members: tuple(sorted(
                (member["path"].casefold(), member["path"]) for member in members)))
            families = []
            for number, members in enumerate(repeating, 1):
                label = f"{prefix}{number}"
                paths = sorted((member["path"] for member in members),
                               key=lambda name: (name.casefold(), name))
                families.append({"label": label, "paths": paths})
                for member in members:
                    if member["source"]:
                        group[source_flag] = True
                        group[source_label] = label
                    else:
                        member["item"][flag] = True
                        member["item"][label_key] = label
            # All current nonmatching members explicitly remain unclustered.
            for record in current:
                if record["source"]:
                    group.setdefault(source_flag, False)
                else:
                    record["item"].setdefault(flag, False)
            return families, sum(1 for record in current if not record["source"]
                                 and record["item"][flag])

        excerpt_families, repeats = mark_families(
            "excerpt_hash", "repeated_excerpt", "excerpt_family",
            "source_repeated_excerpt", "source_excerpt_family", "R")
        document_families, duplicates = mark_families(
            "sha", "identical_document", "document_family",
            "source_identical_document", "source_document_family", "D")
        checked_excerpts = {record["excerpt_hash"] for record in current
                            if isinstance(record["excerpt_hash"], str)
                            and record["excerpt_hash"]}
        group.update({
            "verified_same_polarity_files": verified["same_polarity"],
            "verified_opposite_polarity_files": verified["opposite_polarity"],
            "verified_mixed_polarity_files": verified["mixed"],
            "current_files": statuses["current"],
            "stale_files": statuses["stale"],
            "unavailable_files": statuses["unavailable"],
            "unchecked_files": statuses["not_checked"],
            "repeated_excerpt_files": repeats,
            "identical_document_files": duplicates,
            "current_excerpt_files": sum(1 for record in current
                                         if isinstance(record["excerpt_hash"], str)
                                         and record["excerpt_hash"]),
            "distinct_representative_excerpts": len(checked_excerpts),
            "excerpt_families": excerpt_families,
            "document_families": document_families,
            "independence_assessed": False,
        })
    evidence_limited = limited
    modal_limited = False
    temporal_limited = False
    relation_limited = False
    # Evidence, modality, dated, and remaining hints share one SHA budget.
    # Cached exact sources are free to recheck; alias partner files cannot
    # escape the cap or silently be called current after its exhaustion.
    for kinds in (("modal_contrast",), ("temporal_overlap", "temporal_separation"),
                  ("possible_agreement", "possible_conflict", "alias_candidate")):
        for match in matches or []:
            if match.get("kind") not in kinds:
                continue
            source_state, _ = check(source_path)
            other_state, _ = check(match["path"])
            match["source_verification"] = source_state
            match["other_verification"] = other_state
            # A stale, missing or unchecked source leaves only the historical
            # indexed reading. Dates, polarity and modality are never votes.
            match["live_source_pair"] = source_state == other_state == "current"
            unchecked = "not_checked" in (source_state, other_state)
            if kinds == ("modal_contrast",):
                modal_limited = modal_limited or unchecked
            elif kinds == ("temporal_overlap", "temporal_separation"):
                temporal_limited = temporal_limited or unchecked
            else:
                relation_limited = relation_limited or unchecked
    return evidence_limited, modal_limited, temporal_limited, relation_limited


def _annotate_relation_previews(con, source_path: str, matches: list[dict]) -> None:
    """Attach bounded *indexed snapshots*, never text obtained from live notes.

    One batch looks up each referenced fragment at most once. A fragment is
    shown only when its actual document and locator agree with the relation's
    provenance fields. A stale/unchecked SHA never converts a historical
    index excerpt into verified present-day prose. No SQLite or Markdown write.
    """
    ids = sorted({fragment for match in matches for fragment in
                  (match.get("source_fragment"), match.get("other_fragment"))
                  if isinstance(fragment, str) and fragment})
    if len(ids) > MAX_RELATION_PREVIEW_FRAGMENTS:
        raise ValueError("Relation fragment preview bound exceeded")
    previews = {}
    if ids:
        placeholders = ",".join("?" for _ in ids)
        rows = con.execute(
            "SELECT id, doc, locator, substr(plain,1,?), length(plain), text_hash "
            "FROM fragments WHERE id IN (" + placeholders + ")",
            (MAX_RELATION_PREVIEW_CHARS, *ids)).fetchall()
        previews = {row[0]: row[1:] for row in rows}
    for match in matches:
        for side, path in (("source", source_path), ("other", match["path"])):
            row = previews.get(match.get(side + "_fragment"))
            valid = (row is not None and row[0] == path
                     and row[1] == match.get(side + "_locator"))
            match[side + "_excerpt_available"] = valid
            match[side + "_excerpt"] = row[2] if valid else None
            match[side + "_excerpt_truncated"] = (
                row[3] > MAX_RELATION_PREVIEW_CHARS if valid else False)
            match[side + "_excerpt_hash"] = row[4] if valid else None


def _annotate_reviewed_source_links(con, journal: Journal,
                                    source_path: str, groups: list[dict]) -> bool:
    """Add only *direct* owner-reviewed provenance links to live evidence rows.

    These annotations are separate from evidence counts and the claim graph.
    An old human link remains visible as historical/unverified if either file
    differs from the SHA saved at approval, is unavailable, or was not checked.
    No transitive propagation or automatic independence verdict is permitted.
    """
    try:
        active, _ = _source_lineage_state(journal)
    except ValueError:
        return True
    direct = {}
    for decision in active:
        paths = decision["paths"]
        if source_path not in paths:
            continue
        partner = paths[0] if paths[1] == source_path else paths[1]
        stored = {source["path"]: source.get("source_sha256")
                  for source in decision["sources"]}
        direct[partner] = (decision, stored)
    if not direct:
        for group in groups:
            group["reviewed_source_links_current"] = 0
            group["reviewed_source_links_historical"] = 0
        return False
    source_row = con.execute("SELECT sha256 FROM documents WHERE path=? AND status='PARSED'",
                             (source_path,)).fetchone()
    source_indexed_sha = source_row[0] if source_row else None
    for group in groups:
        current_count = historical_count = 0
        for evidence in group["other_sources"]:
            pair = direct.get(evidence["path"])
            if pair is None:
                continue
            decision, stored = pair
            other_row = con.execute("SELECT sha256 FROM documents "
                                    "WHERE path=? AND status='PARSED'",
                                    (evidence["path"],)).fetchone()
            verified = (group.get("source_verification") == "current" and
                        evidence.get("verification") == "current" and
                        source_indexed_sha == stored.get(source_path) and
                        other_row is not None and other_row[0] == stored.get(evidence["path"]))
            state = "current" if verified else "historical"
            evidence["reviewed_source_link_state"] = state
            evidence["reviewed_source_link_id"] = decision["journal_id"]
            evidence["reviewed_source_link_reason"] = str(decision["reason"])[:180]
            if verified:
                current_count += 1
            else:
                historical_count += 1
        group["reviewed_source_links_current"] = current_count
        group["reviewed_source_links_historical"] = historical_count
    return False


def _entity_sources(con, root: Path, symbol: str) -> tuple[list[dict], bool]:
    # Exact canonical JSON argument, not substring/name similarity. A candidate
    # atom is only a proposal: UI explicitly names this epistemic status.
    needle = canonical_json({"k": "sym", "v": symbol})
    rows = con.execute(
        "SELECT DISTINCT a.doc, a.fragment_id, f.locator, f.text, d.sha256 "
        "FROM candidate_atoms a JOIN fragments f ON f.id=a.fragment_id "
        "JOIN documents d ON d.path=a.doc AND d.status='PARSED' "
        "WHERE instr(a.atom_key, ?) > 0 "
        "ORDER BY a.doc COLLATE NOCASE, f.locator, a.fragment_id LIMIT ?",
        (needle, ENTITY_SOURCE_PAGE + 1)).fetchall()
    sources = []
    for path, fragment, locator, excerpt, sha in rows[:ENTITY_SOURCE_PAGE]:
        sources.append({"path": path, "fragment_id": fragment,
                        "locator": locator, "excerpt": excerpt[:360],
                        "source_sha256": sha,
                        "current": _entity_live_hash(root, path) == sha})
    return sources, len(rows) > ENTITY_SOURCE_PAGE


def _owner_aliases(kb: KnowledgeBase) -> tuple[dict, bool]:
    snapshot = verified_owner_aliases(kb.journal.entries(), kb.journal.actor)
    by_id = {e.id: e for e in kb.journal.entries()}
    active = {}
    for name, evidence in snapshot.by_symbol.items():
        if evidence.journal_id is None:
            continue
        event = by_id[evidence.journal_id]
        active[name] = {"alias": name, "canonical": evidence.canonical,
                        "journal_id": event.id, "time": event.ts,
                        "reason": event.note or "",
                        "sources": event.data.get("evidence", [])[:2]
                        if isinstance(event.data.get("evidence"), list) else []}
    return active, snapshot.limited


def entity_alias_workspace(workspace_dir: str, vault_dir: str,
                           alias: str, canonical: str) -> str:
    """Read-only source choices and approved history for the Android screen."""
    a, c = _entity_symbol(alias), _entity_symbol(canonical)
    kb = _kb(workspace_dir)
    active, limited = _owner_aliases(kb)
    con, root = _entity_index(workspace_dir, vault_dir)
    try:
        left, left_limited = _entity_sources(con, root, a)
        right, right_limited = _entity_sources(con, root, c)
    finally:
        con.close()
    return json.dumps({
        "protocol": "dpksys.entities.v1", "alias": a, "canonical": c,
        "alias_sources": left, "canonical_sources": right,
        "sources_limited": left_limited or right_limited,
        "active": [active[k] for k in sorted(active)],
        "journal_limited": limited, "journal_head": kb.journal.head,
        "read_only": True, "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def entity_alias_decisions(workspace_dir: str) -> str:
    """List reversible owner decisions even when the notebook is unavailable."""
    kb = _kb(workspace_dir)
    active, limited = _owner_aliases(kb)
    recent = [{"op": e.op, "alias": e.data.get("alias"),
               "canonical": e.data.get("canonical"), "journal_id": e.id,
               "time": e.ts} for e in reversed(kb.journal.entries())
              if e.actor == kb.journal.actor and e.op in ("alias", "unalias")][:40]
    return json.dumps({"protocol": "dpksys.entities.v1",
                       "active": [active[k] for k in sorted(active)],
                       "recent": recent, "journal_limited": limited,
                       "read_only": True}, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"))


def _entity_selected(con, root: Path, symbol: str, path: str, fragment_id: str) -> dict:
    if not isinstance(fragment_id, str) or len(fragment_id) > 120:
        raise ValueError("Invalid source fragment")
    sources, limited = _entity_sources(con, root, symbol)
    selected = next((s for s in sources if s["path"] == path
                     and s["fragment_id"] == fragment_id), None)
    if selected is None or not selected["current"]:
        raise ValueError("Source missing, changed or not in the reviewed list; analyze again")
    return selected


def _entity_preview(workspace_dir: str, vault_dir: str,
                    alias: str, canonical: str, alias_path: str,
                    alias_fragment: str, canonical_path: str,
                    canonical_fragment: str, reason: str) -> dict:
    a, c = _entity_symbol(alias), _entity_symbol(canonical)
    if a == c or not isinstance(reason, str) or len(reason.strip()) > 500:
        raise ValueError("Specify two different symbols and a reason of at most 500 characters")
    kb = _kb(workspace_dir)
    active, limited = _owner_aliases(kb)
    snapshot = verified_owner_aliases(kb.journal.entries(), kb.journal.actor)
    count = sum(e.op in ("alias", "unalias") and e.actor == kb.journal.actor
                for e in kb.journal.entries())
    if (limited or count >= MAX_ALIAS_OPERATIONS or a in snapshot.by_symbol
            or a in kb.state.aliases or c in kb.state.aliases
            or c in active or len(snapshot.groups.get(c, ())) > MAX_ALIASES_PER_CANONICAL):
        raise ValueError("Alias is already mapped, would form a chain, or journal limit reached")
    if (alias_path, alias_fragment) == (canonical_path, canonical_fragment):
        raise ValueError("Choose distinct source fragments for the two symbols")
    con, root = _entity_index(workspace_dir, vault_dir)
    try:
        left = _entity_selected(con, root, a, alias_path, alias_fragment)
        right = _entity_selected(con, root, c, canonical_path, canonical_fragment)
    finally:
        con.close()
    evidence = [{"symbol": symbol, "path": src["path"],
                 "fragment_id": src["fragment_id"], "locator": src["locator"],
                 "source_sha256": src["source_sha256"], "excerpt": src["excerpt"]}
                for symbol, src in ((a, left), (c, right))]
    payload = {"alias": a, "canonical": c, "evidence": evidence,
               "reason": reason.strip(), "journal_head": kb.journal.head}
    token = hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()
    return {"protocol": "dpksys.entities.v1", **payload, "preview_token": token,
            "read_only": True, "requires_human_confirmation": True,
            "changes_original_markdown": False}


def preview_entity_alias(workspace_dir: str, vault_dir: str, alias: str,
                         canonical: str, alias_path: str, alias_fragment: str,
                         canonical_path: str, canonical_fragment: str,
                         reason: str) -> str:
    return json.dumps(_entity_preview(workspace_dir, vault_dir, alias, canonical,
                      alias_path, alias_fragment, canonical_path,
                      canonical_fragment, reason), ensure_ascii=False,
                      sort_keys=True, separators=(",", ":"))


def record_entity_alias(workspace_dir: str, vault_dir: str, alias: str,
                        canonical: str, alias_path: str, alias_fragment: str,
                        canonical_path: str, canonical_fragment: str, reason: str,
                        expected_token: str, expected_head: str | None) -> str:
    preview = _entity_preview(workspace_dir, vault_dir, alias, canonical,
                              alias_path, alias_fragment, canonical_path,
                              canonical_fragment, reason)
    if preview["preview_token"] != expected_token or preview["journal_head"] != expected_head:
        raise ValueError("Sources or journal changed after review; start a new preview")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed before confirmation")
    kb.ws.init()
    event = kb.alias_reviewed(alias, canonical, preview["evidence"], reason.strip())
    if kb.journal.verify():
        raise ValueError("Journal integrity check failed")
    return json.dumps({"protocol": "dpksys.entities.v1", "decision": "alias",
                       "journal_ids": [event.id], "journal_head": kb.journal.head},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def preview_entity_unalias(workspace_dir: str, alias: str) -> str:
    kb = _kb(workspace_dir)
    active, limited = _owner_aliases(kb)
    if limited or alias not in active:
        raise ValueError("No unambiguous active owner alias to revoke")
    mapping = active[alias]
    token = hashlib.sha256(canonical_json({"mapping": mapping,
                     "head": kb.journal.head}).encode("utf-8")).hexdigest()
    return json.dumps({"protocol": "dpksys.entities.v1", **mapping,
                       "journal_head": kb.journal.head, "preview_token": token,
                       "read_only": True, "requires_human_confirmation": True},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def record_entity_unalias(workspace_dir: str, alias: str,
                          expected_token: str, expected_head: str | None) -> str:
    preview = json.loads(preview_entity_unalias(workspace_dir, alias))
    if preview["journal_head"] != expected_head or preview["preview_token"] != expected_token:
        raise ValueError("Alias or journal changed after revocation preview")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed before revocation")
    kb.ws.init()
    event = kb.unalias(alias, preview["canonical"], preview["journal_id"])
    if kb.journal.verify():
        raise ValueError("Journal integrity check failed")
    return json.dumps({"protocol": "dpksys.entities.v1", "decision": "unalias",
                       "journal_ids": [event.id], "journal_head": kb.journal.head},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


# Source-lineage decisions are deliberately distinct from semantic aliases and
# accepted claims. They assert only a person's *assessment of provenance*, not
# independent corroboration, truth, or a shared factual identity.
MAX_SOURCE_LINEAGE_OPERATIONS = 512


def _source_lineage_state(journal: Journal) -> tuple[list[dict], list[dict]]:
    if journal.verify():
        raise ValueError("Knowledge journal integrity check failed")
    entries = [entry for entry in journal.entries()
               if entry.actor == journal.actor and
               entry.op in ("source_link", "source_unlink")]
    if len(entries) > MAX_SOURCE_LINEAGE_OPERATIONS:
        raise ValueError("Source lineage journal limit reached")
    active: dict[tuple[str, str], dict] = {}
    recent = []
    for entry in entries:
        data = entry.data
        paths = data.get("paths") if isinstance(data, dict) else None
        if (not isinstance(paths, list) or len(paths) != 2 or
                any(not isinstance(p, str) for p in paths) or
                paths != sorted(paths, key=lambda p: (p.casefold(), p)) or
                paths[0] == paths[1]):
            continue
        key = tuple(paths)
        if entry.op == "source_link":
            sources = data.get("sources")
            if (key in active or not isinstance(sources, list) or len(sources) != 2 or
                    [s.get("path") for s in sources if isinstance(s, dict)] != paths):
                continue
            active[key] = {"paths": paths, "sources": sources,
                           "reason": data.get("reason", ""),
                           "journal_id": entry.id, "time": entry.ts}
        elif entry.op == "source_unlink":
            previous = active.get(key)
            if previous and previous["journal_id"] == data.get("decision_id"):
                del active[key]
        recent.append({"op": entry.op, "paths": paths, "journal_id": entry.id,
                       "decision_id": data.get("decision_id"), "time": entry.ts})
    return [active[key] for key in sorted(active)], list(reversed(recent[-40:]))


def source_lineage_decisions(workspace_dir: str) -> str:
    """Read human provenance annotations even with the notebook disconnected."""
    kb = _kb(workspace_dir)
    active, recent = _source_lineage_state(kb.journal)
    return json.dumps({"protocol": "dpksys.source_lineage.v1",
                       "active": active, "recent": recent,
                       "journal_head": kb.journal.head, "read_only": True,
                       "does_not_establish_independence": True},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _lineage_paths(first_path: str, second_path: str) -> list[str]:
    for path in (first_path, second_path):
        if (not isinstance(path, str) or not path or path.startswith("/") or
                "\\" in path or ".." in path.split("/") or
                not path.lower().endswith((".md", ".markdown")) or len(path) > 500):
            raise ValueError("Select two different relative Markdown files")
    if first_path == second_path:
        raise ValueError("Select two different relative Markdown files")
    return sorted((first_path, second_path), key=lambda p: (p.casefold(), p))


def _lineage_source(con, root: Path, path: str) -> dict:
    row = con.execute("SELECT sha256 FROM documents WHERE path=? AND status='PARSED'",
                      (path,)).fetchone()
    actual = _entity_live_hash(root, path)
    if not row or not actual or actual != row[0]:
        raise ValueError("Source missing or modified since indexing; analyze again")
    excerpt = con.execute(
        "SELECT locator,text FROM fragments WHERE doc=? "
        "ORDER BY CASE WHEN unit_role='sentence' THEN 0 ELSE 1 END, ordinal LIMIT 1",
        (path,)).fetchone()
    if not excerpt:
        raise ValueError("No indexed source excerpt to review")
    return {"path": path, "source_sha256": actual,
            "locator": excerpt[0], "excerpt": excerpt[1][:320]}


def _source_lineage_preview(workspace_dir: str, vault_dir: str,
                            first_path: str, second_path: str, reason: str) -> dict:
    paths = _lineage_paths(first_path, second_path)
    if not isinstance(reason, str) or not 1 <= len(reason.strip()) <= 500:
        raise ValueError("Explain the common upstream source in 1..500 characters")
    kb = _kb(workspace_dir)
    active, _ = _source_lineage_state(kb.journal)
    if any(item["paths"] == paths for item in active):
        raise ValueError("This source-pair decision is already active; revoke it first")
    owner_ops = sum(entry.actor == kb.journal.actor and
                    entry.op in ("source_link", "source_unlink")
                    for entry in kb.journal.entries())
    if owner_ops >= MAX_SOURCE_LINEAGE_OPERATIONS:
        raise ValueError("Source lineage journal limit reached")
    con, root = _entity_index(workspace_dir, vault_dir)
    try:
        sources = [_lineage_source(con, root, path) for path in paths]
    finally:
        con.close()
    payload = {"paths": paths, "sources": sources, "reason": reason.strip(),
               "journal_head": kb.journal.head}
    token = hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()
    return {"protocol": "dpksys.source_lineage.v1", **payload,
            "preview_token": token, "read_only": True,
            "requires_human_confirmation": True, "does_not_establish_independence": True,
            "changes_original_markdown": False}


def preview_source_link(workspace_dir: str, vault_dir: str,
                        first_path: str, second_path: str, reason: str) -> str:
    return json.dumps(_source_lineage_preview(workspace_dir, vault_dir,
                      first_path, second_path, reason), ensure_ascii=False,
                      sort_keys=True, separators=(",", ":"))


def record_source_link(workspace_dir: str, vault_dir: str, first_path: str,
                       second_path: str, reason: str, expected_token: str,
                       expected_head: str | None) -> str:
    preview = _source_lineage_preview(workspace_dir, vault_dir,
                                     first_path, second_path, reason)
    if preview["preview_token"] != expected_token or preview["journal_head"] != expected_head:
        raise ValueError("Source or journal changed after review")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed before confirmation")
    kb.ws.init()
    event = kb.journal.append("source_link", {key: preview[key]
                    for key in ("paths", "sources", "reason")})
    if kb.journal.verify():
        raise ValueError("Knowledge journal integrity check failed")
    return json.dumps({"protocol": "dpksys.source_lineage.v1", "decision": "source_link",
                       "journal_ids": [event.id], "journal_head": kb.journal.head},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def preview_source_unlink(workspace_dir: str, decision_id: str) -> str:
    kb = _kb(workspace_dir)
    active, _ = _source_lineage_state(kb.journal)
    selected = next((item for item in active if item["journal_id"] == decision_id), None)
    if selected is None:
        raise ValueError("No active owner source link for this decision")
    payload = {"paths": selected["paths"], "decision_id": decision_id,
               "journal_head": kb.journal.head}
    token = hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()
    return json.dumps({"protocol": "dpksys.source_lineage.v1", **payload,
                       "preview_token": token, "read_only": True,
                       "requires_human_confirmation": True},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def record_source_unlink(workspace_dir: str, decision_id: str,
                         expected_token: str, expected_head: str | None) -> str:
    preview = json.loads(preview_source_unlink(workspace_dir, decision_id))
    if preview["preview_token"] != expected_token or preview["journal_head"] != expected_head:
        raise ValueError("Source link or journal changed before revocation")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed before revocation")
    kb.ws.init()
    event = kb.journal.append("source_unlink",
                              {"paths": preview["paths"], "decision_id": decision_id})
    if kb.journal.verify():
        raise ValueError("Knowledge journal integrity check failed")
    return json.dumps({"protocol": "dpksys.source_lineage.v1", "decision": "source_unlink",
                       "journal_ids": [event.id], "journal_head": kb.journal.head},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


# An owner's descriptive source-type annotation is *not* a quality score.
# In particular, "primary_record" never promotes a parser candidate to fact.
SOURCE_PROVENANCE_KINDS = ("primary_record", "secondary_summary",
                           "republication", "unclassified")
MAX_SOURCE_ASSESSMENT_OPERATIONS = 512


def _source_assessments(journal: Journal) -> tuple[list[dict], list[dict]]:
    if journal.verify():
        raise ValueError("Knowledge journal integrity check failed")
    entries = [entry for entry in journal.entries()
               if entry.actor == journal.actor and
               entry.op in ("source_assess", "source_unassess")]
    if len(entries) > MAX_SOURCE_ASSESSMENT_OPERATIONS:
        raise ValueError("Source assessment journal limit reached")
    active: dict[str, dict] = {}
    recent = []
    for entry in entries:
        data = entry.data
        path = data.get("path") if isinstance(data, dict) else None
        if not isinstance(path, str) or not path:
            continue
        if entry.op == "source_assess":
            source = data.get("source")
            if (path in active or data.get("kind") not in SOURCE_PROVENANCE_KINDS or
                    not isinstance(source, dict) or source.get("path") != path):
                continue
            active[path] = {"path": path, "kind": data["kind"],
                            "reason": str(data.get("reason", ""))[:500],
                            "source": source, "journal_id": entry.id, "time": entry.ts}
        else:
            previous = active.get(path)
            if previous and previous["journal_id"] == data.get("decision_id"):
                del active[path]
        recent.append({"op": entry.op, "path": path, "journal_id": entry.id,
                       "decision_id": data.get("decision_id"), "time": entry.ts})
    return [active[path] for path in sorted(active)], list(reversed(recent[-40:]))


def _annotate_source_assessments(con, journal: Journal,
                                 source_path: str, groups: list[dict]) -> bool:
    """Show owner labels beside evidence; never turn those labels into scoring.

    'Current' requires a live file hash check and the exact indexed SHA saved
    at approval. A changed, missing or unchecked note only has a historical
    annotation. Revoked decisions are absent even though the journal retains
    their history. This neither reads additional note bytes nor changes SQL.
    """
    try:
        active, _ = _source_assessments(journal)
    except ValueError:
        return True
    by_path = {decision["path"]: decision for decision in active}
    indexed_shas: dict[str, str | None] = {}

    def annotate(target: dict, path: str, verification: str) -> bool | None:
        decision = by_path.get(path)
        if decision is None:
            return None
        if path not in indexed_shas:
            row = con.execute(
                "SELECT sha256 FROM documents WHERE path=? AND status='PARSED'",
                (path,)).fetchone()
            indexed_shas[path] = row[0] if row else None
        current = (verification == "current" and indexed_shas[path] is not None
                   and indexed_shas[path] == decision["source"].get("source_sha256"))
        target["source_assessment_kind"] = decision["kind"]
        target["source_assessment_state"] = "current" if current else "historical"
        target["source_assessment_id"] = decision["journal_id"]
        target["source_assessment_reason"] = decision["reason"][:180]
        return current

    for group in groups:
        annotate(group, source_path, group.get("source_verification", "not_checked"))
        current_count = historical_count = 0
        for evidence in group["other_sources"]:
            current = annotate(evidence, evidence["path"],
                               evidence.get("verification", "not_checked"))
            if current is True:
                current_count += 1
            elif current is False:
                historical_count += 1
        group["source_assessment_current_files"] = current_count
        group["source_assessment_historical_files"] = historical_count
    return False


def source_assessment_decisions(workspace_dir: str) -> str:
    """Read owner source-type choices without a notebook or internet access."""
    kb = _kb(workspace_dir)
    active, recent = _source_assessments(kb.journal)
    return json.dumps({"protocol": "dpksys.source_assessment.v1",
                       "active": active, "recent": recent, "journal_head": kb.journal.head,
                       "read_only": True, "changes_claim_authority": False,
                       "reliability_score": None}, ensure_ascii=False,
                      sort_keys=True, separators=(",", ":"))


def _assessment_preview(workspace_dir: str, vault_dir: str, path: str,
                        kind: str, reason: str) -> dict:
    if (not isinstance(path, str) or not path or path.startswith("/") or
            "\\" in path or ".." in path.split("/") or len(path) > 500 or
            not path.lower().endswith((".md", ".markdown"))):
        raise ValueError("Choose an exact relative Markdown source path")
    if kind not in SOURCE_PROVENANCE_KINDS:
        raise ValueError("Choose a descriptive source type")
    if not isinstance(reason, str) or not 1 <= len(reason.strip()) <= 500:
        raise ValueError("Explain this source-type choice in 1..500 characters")
    kb = _kb(workspace_dir)
    active, _ = _source_assessments(kb.journal)
    if any(record["path"] == path for record in active):
        raise ValueError("Source has an active assessment; revoke it before reassessing")
    owner_ops = sum(entry.actor == kb.journal.actor and
                    entry.op in ("source_assess", "source_unassess")
                    for entry in kb.journal.entries())
    if owner_ops >= MAX_SOURCE_ASSESSMENT_OPERATIONS:
        raise ValueError("Source assessment journal limit reached")
    con, root = _entity_index(workspace_dir, vault_dir)
    try:
        source = _lineage_source(con, root, path)
    finally:
        con.close()
    payload = {"path": path, "kind": kind, "source": source,
               "reason": reason.strip(), "journal_head": kb.journal.head}
    token = hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()
    return {"protocol": "dpksys.source_assessment.v1", **payload,
            "preview_token": token, "read_only": True,
            "requires_human_confirmation": True, "changes_claim_authority": False,
            "reliability_score": None, "changes_original_markdown": False}


def preview_source_assessment(workspace_dir: str, vault_dir: str, path: str,
                              kind: str, reason: str) -> str:
    return json.dumps(_assessment_preview(workspace_dir, vault_dir, path, kind, reason),
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def record_source_assessment(workspace_dir: str, vault_dir: str, path: str,
                             kind: str, reason: str, expected_token: str,
                             expected_head: str | None) -> str:
    preview = _assessment_preview(workspace_dir, vault_dir, path, kind, reason)
    if preview["preview_token"] != expected_token or preview["journal_head"] != expected_head:
        raise ValueError("Source or journal changed after assessment preview")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed before confirmation")
    kb.ws.init()
    event = kb.journal.append("source_assess", {key: preview[key]
                          for key in ("path", "kind", "source", "reason")})
    if kb.journal.verify():
        raise ValueError("Knowledge journal integrity check failed")
    return json.dumps({"protocol": "dpksys.source_assessment.v1",
                       "decision": "source_assess", "journal_ids": [event.id],
                       "journal_head": kb.journal.head}, ensure_ascii=False,
                      sort_keys=True, separators=(",", ":"))


def preview_source_unassessment(workspace_dir: str, decision_id: str) -> str:
    kb = _kb(workspace_dir)
    active, _ = _source_assessments(kb.journal)
    selected = next((record for record in active if record["journal_id"] == decision_id), None)
    if selected is None:
        raise ValueError("No active owner source assessment with this decision ID")
    payload = {"path": selected["path"], "decision_id": decision_id,
               "journal_head": kb.journal.head}
    token = hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()
    return json.dumps({"protocol": "dpksys.source_assessment.v1", **payload,
                       "preview_token": token, "read_only": True,
                       "requires_human_confirmation": True},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def record_source_unassessment(workspace_dir: str, decision_id: str,
                               expected_token: str, expected_head: str | None) -> str:
    preview = json.loads(preview_source_unassessment(workspace_dir, decision_id))
    if preview["preview_token"] != expected_token or preview["journal_head"] != expected_head:
        raise ValueError("Assessment or journal changed before revocation")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed before revocation")
    kb.ws.init()
    event = kb.journal.append("source_unassess",
                              {"path": preview["path"], "decision_id": decision_id})
    if kb.journal.verify():
        raise ValueError("Knowledge journal integrity check failed")
    return json.dumps({"protocol": "dpksys.source_assessment.v1",
                       "decision": "source_unassess", "journal_ids": [event.id],
                       "journal_head": kb.journal.head}, ensure_ascii=False,
                      sort_keys=True, separators=(",", ":"))


def _validate_note_input(relative_path: str, markdown: str) -> None:
    if not isinstance(relative_path, str) or not isinstance(markdown, str):
        raise ValueError("Expected relative path and Markdown content")
    if not relative_path or relative_path.startswith("/") or "\\" in relative_path \
            or ".." in relative_path.split("/") \
            or not relative_path.lower().endswith((".md", ".markdown")):
        raise ValueError("Invalid relative Markdown path")
    if len(markdown) > MAX_CHARACTERS:
        raise ValueError("Note exceeds read-only inspection limit")


def _verified_source_span(source: dict, markdown: str, source_sha: str,
                          *, with_location: bool = True) -> dict:
    """Locate approved provenance by its stored Unicode offsets, never text search.

    A matching document SHA alone does not prove that historical/legacy span
    metadata is usable. Do not manufacture an offset for a repeated excerpt.
    """
    if source.get("document_sha") != source_sha:
        return {"source_span_state": "stale_source"}
    span = source.get("span")
    if (not isinstance(span, (tuple, list)) or len(span) != 2
            or any(not isinstance(v, int) or isinstance(v, bool) for v in span)):
        return {"source_span_state": "missing"}
    start, end = span
    if (start < 0 or end <= start or end > len(markdown)
            or not isinstance(source.get("text"), str)
            or markdown[start:end] != source["text"]):
        return {"source_span_state": "mismatch"}
    aligned = {"source_span_state": "verified", "source_span": [start, end]}
    if with_location:
        before = markdown[:start]
        aligned.update({"source_line": before.count("\n") + 1,
                        "source_column": start - before.rfind("\n")})
    return aligned


def _structural_editor_comparison(source_text: str, approved_node, verified: bool) -> dict:
    """Compare unambiguous CNL-derived IR fields, without asserting truth.

    Missing/stale provenance, long sources, unsupported syntax, ambiguous
    readings and non-assertion nodes are *unknown*, never equivalent or false.
    This is an in-memory comparison; no workspace, source or journal is opened.
    """
    result = {"status": "unavailable", "source_formalization": "NOT_CHECKED",
              "changed_fields": [], "read_only": True, "proves_truth": False}
    if not verified or not isinstance(source_text, str) or not source_text.strip() \
            or len(source_text) > 320:
        return result
    approved_assertion = getattr(approved_node, "assertion", None)
    if approved_assertion is None:
        return result
    try:
        reading = Formalizer(mode="import").formalize(source_text)
        result["source_formalization"] = reading.status
        if reading.status != "FORMALIZED" or reading.selected is None \
                or len(reading.selected.nodes) != 1:
            return result
        extracted = getattr(reading.selected.nodes[0], "assertion", None)
        if extracted is None:
            return result
        old, new = extracted.payload(), approved_assertion.payload()
        old_atom, new_atom = old["atom"], new["atom"]
        fields = (
            ("predicate", old_atom["p"], new_atom["p"]),
            ("arguments", old_atom["args"], new_atom["args"]),
            ("negation", old["neg"], new["neg"]),
            ("modality", old["mod"], new["mod"]),
            ("context", old.get("ctx"), new.get("ctx")),
            ("interval", old.get("iv"), new.get("iv")),
        )
        differences = []
        for field, source, accepted in fields:
            if source == accepted:
                continue
            # UI diagnostics are bounded; this is display, not an IR export.
            differences.append({"field": field,
                                "source": json.dumps(source, ensure_ascii=False,
                                                     sort_keys=True)[:200],
                                "accepted": json.dumps(accepted, ensure_ascii=False,
                                                       sort_keys=True)[:200]})
        result["changed_fields"] = differences
        result["status"] = "different_structure" if differences else "same_structure"
    except (KSError, ValueError, TypeError, KeyError, AttributeError):
        result["source_formalization"] = "UNAVAILABLE"
    return result


def editor_note_highlights(workspace_dir: str, relative_path: str, markdown: str) -> str:
    """Read only approved source offsets for ephemeral in-editor decoration.

    Never mix unapproved indexed candidates into these highlights. Overlapping
    approved spans form a single union: mixed roles must not inherit the color
    of an arbitrary record. Legacy/stale provenance never supplies offsets.
    """
    _validate_note_input(relative_path, markdown)
    kb = _kb(workspace_dir)
    source_sha = _source_hash(markdown)
    verified, unverified = [], 0
    for record in kb.state.records.values():
        if not kb.state.active(record.id):
            continue
        for source in record.provenance:
            if source.get("document") != relative_path:
                continue
            alignment = _verified_source_span(source, markdown, source_sha,
                                              with_location=False)
            if alignment["source_span_state"] != "verified":
                unverified += 1
                continue
            start, end = alignment["source_span"]
            verified.append((start, end, record.role))

    groups = []
    for start, end, role in sorted(verified):
        if groups and start < groups[-1]["end"]:
            group = groups[-1]
            group["end"] = max(group["end"], end)
            group["roles"].add(role)
            group["record_count"] += 1
        else:
            groups.append({"start": start, "end": end,
                           "roles": {role}, "record_count": 1})
    highlights = [{"start": group["start"], "end": group["end"],
                   "role": next(iter(group["roles"])) if len(group["roles"]) == 1
                           else "mixed", "record_count": group["record_count"]}
                  for group in groups[:MAX_EDITOR_HIGHLIGHTS]]
    return json.dumps({
        "protocol": "dpksys.editor_highlights.v1", "path": relative_path,
        "source_hash": source_sha, "source_snapshot": "saved_markdown",
        "highlights": highlights, "highlight_total": len(groups),
        "truncated": len(groups) > MAX_EDITOR_HIGHLIGHTS,
        "unverified_origins": unverified, "read_only": True,
        "only_approved_journal_sources": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def editor_source_records(workspace_dir: str, relative_path: str, markdown: str,
                          start: int, end: int) -> str:
    """Inspect one selected/cursor source location, never guess an old span.

    Selection coordinates are Python Unicode code points. A nonempty selection
    must fit entirely within an approved provenance span. A cursor must lie
    inside one, not merely adjacent to it. Obsolete records are included only
    if their original source snapshot remains byte-for-byte current.
    """
    _validate_note_input(relative_path, markdown)
    if (not isinstance(start, int) or isinstance(start, bool)
            or not isinstance(end, int) or isinstance(end, bool)
            or start < 0 or end < start or end > len(markdown)):
        raise ValueError("Invalid editor source selection")
    kb = _kb(workspace_dir)
    source_sha = _source_hash(markdown)
    matched, unverified_origins = [], 0
    for rec in kb.state.records.values():
        origins = []
        for source in rec.provenance:
            if source.get("document") != relative_path:
                continue
            verified = _verified_source_span(source, markdown, source_sha,
                                             with_location=False)
            if verified["source_span_state"] != "verified":
                unverified_origins += 1
                continue
            s, e = verified["source_span"]
            if (s <= start < e if start == end else s <= start and end <= e):
                origins.append((e - s, s, source))
        if origins:
            # If this record has multiple provenances covering the selection,
            # show the narrowest source; ties resolve by stored Unicode offset.
            _, _, origin = min(origins, key=lambda row: (row[0], row[1]))
            matched.append((rec, origin))

    matched.sort(key=lambda row: (kb.state.active(row[0].id),
                                  row[0].updated_at, row[0].id), reverse=True)
    active_total = sum(kb.state.active(rec.id) for rec, _ in matched)
    entries = kb.state.entries
    active_assertions = {}
    for existing in kb.state.records.values():
        assertion = getattr(existing.node, "assertion", None)
        if assertion is not None and kb.state.active(existing.id):
            active_assertions.setdefault(assertion.id, []).append(existing)
    records = []
    for rec, source in matched[:MAX_EDITOR_SOURCE_RECORDS]:
        span = _verified_source_span(source, markdown, source_sha)
        event_ids = set(rec.journal)
        history = [dict(id=e.id, op=e.op, time=e.ts)
                   for e in entries if e.id in event_ids]
        conflicts_available = True
        try:
            conflicts = kb.conflicts_for([rec.node])
            conflicts_with = sorted({str(c.get("conflicts_with"))
                                     for c in conflicts if c.get("conflicts_with")})[:8]
        except Exception:
            conflicts_available, conflicts_with = False, []
        conflict_records = []
        for cid in conflicts_with:
            for opposing in active_assertions.get(cid, ()):
                if opposing.id != rec.id and len(conflict_records) < 8:
                    conflict_records.append({"id": opposing.id, "role": opposing.role,
                                             "status": opposing.status.value,
                                             "statement": opposing.node.ksf()[:300]})
        text = str(source.get("text") or "")
        ksf = rec.node.ksf()
        records.append({
            "id": rec.id, "role": rec.role, "status": rec.status.value,
            "active": kb.state.active(rec.id), "statement": ksf[:1000],
            "statement_truncated": len(ksf) > 1000,
            "source_excerpt": text[:1000], "source_text_truncated": len(text) > 1000,
            "locator": str(source.get("locator") or "")[:150],
            "fragment_id": str(source.get("fragment_id") or "")[:150],
            "provenance_kind": str(source.get("source_type") or "")[:70],
            "source_current": True, "conflicts_available": conflicts_available,
            "conflict_count": len(conflicts_with) if conflicts_available else None,
            "conflicts_with": conflicts_with,
            "conflict_records": conflict_records,
            "history": history[-8:], "history_total": len(history),
            **span,
        })
    return json.dumps({
        "protocol": "dpksys.editor_source_records.v1", "path": relative_path,
        "source_hash": source_sha, "journal_head": kb.journal.head,
        "selection": [start, end], "records": records,
        "record_total": len(matched), "active_total": active_total,
        "historical_total": len(matched) - active_total,
        "truncated": len(matched) > MAX_EDITOR_SOURCE_RECORDS,
        "unverified_origins": unverified_origins, "read_only": True,
        "only_verified_journal_sources": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def editor_record_evidence(workspace_dir: str, record_id: str) -> str:
    """Bounded, read-only evidence graph for one *journal* record.

    Provenance locations are intentionally **unverified** here: only the
    Android editor may resolve a selected-vault note, re-read its current
    saved bytes and check both the document and exact fragment hashes before
    navigation. Never follow a stored provenance path inside this bridge.
    """
    if not isinstance(record_id, str) or not record_id or len(record_id) > 160:
        raise ValueError("Invalid journal record ID")
    kb = _kb(workspace_dir)
    rec = kb.state.records.get(record_id)
    if rec is None:
        raise ValueError("Journal record not found")
    origins = []
    for source in rec.provenance[-MAX_EDITOR_EVIDENCE_ORIGINS:]:
        if not isinstance(source, dict):
            continue
        text = source.get("text")
        span = source.get("span")
        valid_span = (isinstance(span, (list, tuple)) and len(span) == 2
                      and all(isinstance(n, int) and not isinstance(n, bool) for n in span)
                      and 0 <= span[0] < span[1])
        origins.append({
            "path": str(source.get("document") or "")[:500],
            "document_sha": str(source.get("document_sha") or "")[:100],
            "source_span": list(span) if valid_span else None,
            "source_text_sha": _source_hash(text) if isinstance(text, str) else None,
            "source_excerpt": text[:500] if isinstance(text, str) else "",
            "source_text_truncated": isinstance(text, str) and len(text) > 500,
            "source_type": str(source.get("source_type") or "")[:80],
            "locator": str(source.get("locator") or "")[:150],
            "navigation_verified": False,
        })

    linked = {}
    def add_link(target_id, kind):
        if target_id == rec.id or target_id not in kb.state.records:
            return
        target = kb.state.records[target_id]
        if target_id not in linked:
            linked[target_id] = {"id": target_id, "role": target.role,
                                 "status": target.status.value,
                                 "active": kb.state.active(target_id),
                                 "statement": target.node.ksf()[:300], "kinds": []}
        if kind not in linked[target_id]["kinds"]:
            linked[target_id]["kinds"].append(kind)

    for parent in rec.supersedes:
        add_link(parent, "predecessor")
    for successor in rec.superseded_by_many:
        add_link(successor, "successor")
    if rec.superseded_by:
        add_link(rec.superseded_by, "successor")
    conflicts_available = True
    try:
        conflicts = kb.conflicts_for([rec.node])
        opposing = {str(row["conflicts_with"]) for row in conflicts
                    if row.get("conflicts_with")}
        for target in kb.state.records.values():
            assertion = getattr(target.node, "assertion", None)
            if (assertion is not None and assertion.id in opposing
                    and kb.state.active(target.id)):
                add_link(target.id, "possible_conflict")
    except Exception:
        conflicts_available = False
    links = list(linked.values())
    events_by_id = set(rec.journal)
    events = [{"id": entry.id, "seq": entry.seq, "op": entry.op,
               "time": entry.ts}
              for entry in kb.state.entries if entry.id in events_by_id]
    return json.dumps({
        "protocol": "dpksys.editor_record_evidence.v1", "id": rec.id,
        "role": rec.role, "status": rec.status.value,
        "active": kb.state.active(rec.id), "statement": rec.node.ksf()[:1000],
        "journal_head": kb.journal.head,
        "origins": origins, "origins_total": len(rec.provenance),
        "origins_truncated": len(rec.provenance) > MAX_EDITOR_EVIDENCE_ORIGINS,
        "links": links[:MAX_EDITOR_EVIDENCE_LINKS], "links_total": len(links),
        "links_truncated": len(links) > MAX_EDITOR_EVIDENCE_LINKS,
        "conflicts_available": conflicts_available,
        "events": events[-MAX_EDITOR_EVIDENCE_EVENTS:], "events_total": len(events),
        "events_truncated": len(events) > MAX_EDITOR_EVIDENCE_EVENTS,
        "only_journal_records": True, "read_only": True,
        "navigation_verified": False,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def stage22_editor_selftest(cache_dir: str) -> str:
    """One-tap, count-only synthetic regression for the in-editor knowledge UI.

    The caller passes *only* its own app-private cache folder. No selected
    notebook, user journal, derived index, network endpoint or secret is read.
    This tests the actual Python bridge contract; touchscreen layout, IME,
    TalkBack, lifecycle/rotation and platform rendering are still manual QA.
    """
    cache = Path(cache_dir).resolve()
    if not Path(cache_dir).is_absolute() or not cache.is_dir():
        raise ValueError("Editor QA requires an existing private cache directory")
    checks = []

    def verify(code, check):
        try:
            check()
            checks.append({"code": code, "passed": True, "detail": "synthetic assertions passed"})
        except Exception as error:
            # Never disclose source, local paths, journal data or tracebacks.
            checks.append({"code": code, "passed": False,
                           "detail": "synthetic mismatch (" + type(error).__name__ + ")"})

    with tempfile.TemporaryDirectory(prefix="dpks-editor-qa-", dir=cache) as temp:
        root = Path(temp)
        vault = root / "synthetic_vault"
        vault.mkdir()
        ws = str(root / "private_journal")
        source = ("# 😀\nАлекс является разработчиком.\n"
                  "Алекс является разработчиком.\n")
        negative = "Алекс не является разработчиком.\n"
        note = vault / "a.md"
        note.write_text(source, encoding="utf-8")
        (vault / "b.md").write_text(negative, encoding="utf-8")
        source_hash = _source_hash(source)
        second = source.rindex("Алекс является")
        first = source.index("Алекс является")
        state = {}

        def empty():
            before = not Path(ws).exists()
            context = json.loads(editor_note_context(ws, "a.md", source))
            highlights = json.loads(editor_note_highlights(ws, "a.md", source))
            records = json.loads(editor_source_records(ws, "a.md", source, second, second))
            assert before and not Path(ws).exists()
            assert context["accepted_total"] == 0
            assert not highlights["highlights"] and not records["records"]
        verify("EDITOR-EMPTY-22", empty)

        def accept():
            fragments = json.loads(inspect_note("a.md", source))["fragments"]
            fragment_id = fragments[2]["id"]
            preview = json.loads(preview_fragment(ws, "a.md", source, fragment_id))
            assert preview["source_hash"] == source_hash
            accepted = json.loads(record_decision(
                ws, "a.md", source, fragment_id,
                preview["preview"]["options"][0]["reading_id"], "claim",
                source_hash, preview["journal_head"]))
            result = json.loads(editor_source_records(ws, "a.md", source, second, second))
            assert accepted["decision"] == "claim"
            assert result["record_total"] == 1 and result["active_total"] == 1
            state["record_id"] = result["records"][0]["id"]
        verify("EDITOR-ACCEPT-22", accept)

        def span():
            result = json.loads(editor_source_records(ws, "a.md", source, second + 5, second + 5))
            assert result["records"][0]["source_span"][0] == second
            assert result["records"][0]["source_line"] == 3
            assert not json.loads(editor_source_records(ws, "a.md", source, first, first))["records"]
            assert second != len(source[:second].encode("utf-16-le")) // 2
        verify("EDITOR-SPAN-22", span)

        def selection():
            assert json.loads(editor_source_records(ws, "a.md", source, second, second + 10))["record_total"] == 1
            assert json.loads(editor_source_records(ws, "a.md", source, first, second + 10))["record_total"] == 0
            end = json.loads(editor_source_records(ws, "a.md", source, second, second))["records"][0]["source_span"][1]
            assert json.loads(editor_source_records(ws, "a.md", source, end, end))["record_total"] == 0
        verify("EDITOR-SELECTION-22", selection)

        def highlights():
            result = json.loads(editor_note_highlights(ws, "a.md", source))
            assert result["only_approved_journal_sources"]
            assert result["highlight_total"] == 1
            assert result["highlights"][0]["start"] == second
            assert result["highlights"][0]["role"] == "claim"
        verify("EDITOR-HIGHLIGHT-22", highlights)

        def evidence():
            result = json.loads(editor_record_evidence(ws, state["record_id"]))
            origin = result["origins"][0]
            assert result["only_journal_records"] and result["events_total"] == 1
            assert not origin["navigation_verified"] and origin["path"] == "a.md"
            assert origin["document_sha"] == source_hash
            assert origin["source_span"][0] == second
            assert origin["source_text_sha"] == _source_hash(source[second:origin["source_span"][1]])
        verify("EDITOR-EVIDENCE-22", evidence)

        def conflict():
            fragment = json.loads(inspect_note("b.md", negative))["fragments"][0]["id"]
            preview = json.loads(preview_fragment(ws, "b.md", negative, fragment))
            record_decision(ws, "b.md", negative, fragment,
                            preview["preview"]["options"][0]["reading_id"],
                            "claim", preview["source_hash"], preview["journal_head"])
            result = json.loads(editor_record_evidence(ws, state["record_id"]))
            assert result["conflicts_available"]
            assert any("possible_conflict" in link["kinds"] and link["active"]
                       for link in result["links"])
        verify("EDITOR-CONFLICT-22", conflict)

        def stale():
            changed = source + "Новая строка.\n"
            result = json.loads(editor_source_records(ws, "a.md", changed, second, second))
            highlights = json.loads(editor_note_highlights(ws, "a.md", changed))
            assert not result["records"] and result["unverified_origins"] > 0
            assert not highlights["highlights"]
        verify("EDITOR-STALE-22", stale)

        def history():
            preview = json.loads(preview_retraction(ws, state["record_id"]))
            retract_decision(ws, state["record_id"], preview["journal_head"])
            selected = json.loads(editor_source_records(ws, "a.md", source, second, second))
            assert selected["historical_total"] == 1 and selected["active_total"] == 0
            events = json.loads(editor_record_evidence(ws, state["record_id"]))["events"]
            assert [event["op"] for event in events] == ["accept", "retract"]
        verify("EDITOR-HISTORY-22", history)

        def read_only():
            journal = Path(ws) / "data/formal/journal.jsonl"
            journal_before = hashlib.sha256(journal.read_bytes()).hexdigest()
            editor_source_records(ws, "a.md", source, second, second)
            editor_note_context(ws, "a.md", source)
            editor_note_highlights(ws, "a.md", source)
            editor_record_evidence(ws, state["record_id"])
            assert hashlib.sha256(journal.read_bytes()).hexdigest() == journal_before
            assert hashlib.sha256(note.read_bytes()).hexdigest() == source_hash
            assert _source_hash(note.read_text(encoding="utf-8")) == source_hash
        verify("EDITOR-READONLY-22", read_only)

    return json.dumps({
        "protocol": "dpksys.stage22_editor_selftest.v1", "checks": checks,
        "passed": sum(row["passed"] for row in checks),
        "failed": sum(not row["passed"] for row in checks),
        "synthetic_only": True, "used_selected_vault": False,
        "requires_manual_ui_qa": True, "read_only_user_data": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def editor_note_context(workspace_dir: str, relative_path: str, markdown: str,
                        vault_dir: str | None = None) -> str:
    """Read-only, bounded note context from the *saved* source snapshot.

    Approved journal records are distinct from unapproved, indexed lexical/
    formal candidate relations. The index is opened strictly read-only and
    candidate relations are hidden if this note's indexed SHA is stale.
    """
    _validate_note_input(relative_path, markdown)
    kb = _kb(workspace_dir)
    source_sha = _source_hash(markdown)
    active, historical = [], 0
    active_count, stale_count = 0, 0
    for rec in sorted(kb.state.records.values(),
                      key=lambda value: (value.updated_at, value.id), reverse=True):
        source = next((p for p in reversed(rec.provenance)
                       if p.get("document") == relative_path), None)
        if source is None:
            continue
        if not kb.state.active(rec.id):
            historical += 1
            continue
        active_count += 1
        fresh = source.get("document_sha") == source_sha
        if not fresh:
            stale_count += 1
        if len(active) >= 24:
            continue
        alignment = _verified_source_span(source, markdown, source_sha)
        conflicts_available = True
        try:
            warnings = kb.conflicts_for([rec.node])
            conflict_ids = sorted({str(c.get("conflicts_with"))
                                   for c in warnings if c.get("conflicts_with")})[:8]
        except Exception:
            conflicts_available = False
            conflict_ids = []
        excerpt = str(source.get("text") or "")
        statement = rec.node.ksf()
        active.append({
            "id": rec.id, "role": rec.role, "status": rec.status.value,
            "statement": statement[:1000], "statement_truncated": len(statement) > 1000,
            "locator": str(source.get("locator") or "")[:150],
            "fragment_id": str(source.get("fragment_id") or "")[:150],
            "source_excerpt": excerpt[:1000], "source_text_truncated": len(excerpt) > 1000,
            "source_current": fresh,
            "provenance_kind": str(source.get("source_type") or "")[:70],
            "conflicts_available": conflicts_available,
            "conflict_count": len(conflict_ids) if conflicts_available else None,
            "conflicts_with": conflict_ids,
            "structured_comparison": _structural_editor_comparison(
                excerpt, rec.node, alignment["source_span_state"] == "verified"),
            **alignment,
        })

    relations, truncated, index_state = [], False, "absent"
    evidence_groups, evidence_limited = [], False
    evidence_checks_limited = True
    modal_checks_limited = False
    temporal_checks_limited = False
    relation_checks_limited = False
    source_lineage_limited = False
    source_assessment_limited = False
    alias_limited = False
    modal_limited = False
    con = _decision_index(Path(workspace_dir))
    if con is not None:
        try:
            row = con.execute("SELECT sha256, status FROM documents WHERE path=?",
                              (relative_path,)).fetchone()
            if row is None or row[1] != "PARSED" or row[0] != source_sha:
                index_state = "stale"
            else:
                index_state = "current"
                # Shared with the library UI: exact conflicts stay separate
                # from temporal-overlap hints, even in the editor dock.
                result = indexed_related_claims(con, relative_path, 20,
                    verified_owner_aliases(kb.journal.entries(), kb.journal.actor))
                relations = result["matches"]
                truncated = result["truncated"]
                alias_limited = result["alias_search_limited"]
                modal_limited = result["modal_search_limited"]
                evidence_groups = result["evidence_groups"]
                evidence_limited = result["evidence_limited"]
                (evidence_checks_limited, modal_checks_limited,
                 temporal_checks_limited, relation_checks_limited) = _verify_evidence_freshness(
                    con, vault_dir, relative_path, evidence_groups, relations)
                _annotate_relation_previews(con, relative_path, relations)
                source_lineage_limited = _annotate_reviewed_source_links(
                    con, kb.journal, relative_path, evidence_groups)
                source_assessment_limited = _annotate_source_assessments(
                    con, kb.journal, relative_path, evidence_groups)
        except sqlite3.Error:
            index_state, relations, truncated = "unavailable", [], False
        finally:
            con.close()

    return json.dumps({
        "protocol": "dpksys.editor_context.v1",
        "path": relative_path, "source_hash": source_sha,
        "journal_head": kb.journal.head, "read_only": True,
        "source_snapshot": "saved_markdown",
        "accepted_records": active, "accepted_total": active_count,
        "accepted_truncated": active_count > len(active),
        "stale_provenance_count": stale_count,
        "historical_count": historical,
        "candidate_relations": relations, "relations_truncated": truncated,
        "evidence_groups": evidence_groups, "evidence_limited": evidence_limited,
        "evidence_checks_limited": evidence_checks_limited,
        "modal_checks_limited": modal_checks_limited,
        "temporal_checks_limited": temporal_checks_limited,
        "relation_checks_limited": relation_checks_limited,
        "source_lineage_limited": source_lineage_limited,
        "source_assessment_limited": source_assessment_limited,
        "alias_search_limited": alias_limited,
        "modal_search_limited": modal_limited,
        "relation_index_state": index_state,
        "relations_are_unapproved": True,
        "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def inspect_note(relative_path: str, markdown: str) -> str:
    _validate_note_input(relative_path, markdown)
    parsed = parse_document(relative_path, markdown)
    coverage = check_coverage(markdown, parsed.fragments)
    analyzer = LanguageAnalyzer()
    counts: dict[str, int] = {}
    fragments = []
    for f in parsed.fragments:
        classification = classify(f)
        kind = classification.kind.value
        counts[kind] = counts.get(kind, 0) + 1
        if len(fragments) < MAX_DISPLAYED:
            result = analyzer.analyze(f, {"path": relative_path})
            fragments.append({
                "id": f.id,
                "locator": f.locator,
                "kind": result.classification.kind.value,
                "formalization": result.classification.formalization.value,
                "candidate_count": len(result.candidates),
                "preview": f.plain[:160],
            })
    return json.dumps({
        "protocol": "dpksys.inspect.v1",
        "engine_version": KS_VERSION,
        "path": relative_path,
        "coverage_ok": coverage.ok,
        "block_count": len(parsed.blocks),
        "fragment_count": len(parsed.fragments),
        "counts": dict(sorted(counts.items())),
        "fragments": fragments,
        "partial_display": len(parsed.fragments) > MAX_DISPLAYED,
        "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def inspect_selection(relative_path: str, markdown: str,
                      start: int, end: int) -> str:
    """Locate one source fragment containing a user selection, read-only.

    Offsets are Unicode code points, not Java UTF-16 indices or UTF-8 bytes.
    A selection crossing fragment boundaries must never silently pick one.
    The caller rechecks the saved file before offering journal review.
    """
    if (not isinstance(start, int) or isinstance(start, bool)
            or not isinstance(end, int) or isinstance(end, bool)
            or start < 0 or start >= end or end > len(markdown)):
        raise ValueError("Invalid source selection")
    inspect_note(relative_path, markdown)  # Paths, size and coverage rules.
    fragments = [f for f in parse_document(relative_path, markdown).fragments
                 if f.span[0] <= start and end <= f.span[1] and f.plain.strip()]
    response = {"protocol": "dpksys.inspect.v1", "path": relative_path,
                "source_hash": _source_hash(markdown),
                "read_only": True, "matched": len(fragments) == 1}
    if len(fragments) == 1:
        frag = fragments[0]
        analyzed = LanguageAnalyzer().analyze(frag, {"path": relative_path})
        response.update({"fragment_id": frag.id, "locator": frag.locator,
                         "preview": frag.plain[:400],
                         "source_text": frag.text[:4000],
                         "candidate_count": len(analyzed.candidates)})
    else:
        response["reason"] = "selection_crosses_fragments"
    return json.dumps(response, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"))


def _fragment(relative_path: str, markdown: str, fragment_id: str):
    # Reuse the same input bounds before allocating semantic structures.
    inspect_note(relative_path, markdown)
    doc = parse_document(relative_path, markdown)
    fragment = next((f for f in doc.fragments if f.id == fragment_id), None)
    if fragment is None or not fragment.plain.strip():
        raise ValueError("Missing or non-semantic fragment")
    return fragment


def _kb(workspace_dir: str) -> KnowledgeBase:
    path = Path(workspace_dir)
    if not path.is_absolute():
        raise ValueError("Knowledge workspace must be an absolute app-private path")
    kb = KnowledgeBase(Workspace(path), SystemClock(), use_cache=False)
    if kb.journal.verify():
        raise ValueError("Knowledge journal integrity check failed")
    return kb


def _source_hash(markdown: str) -> str:
    return hashlib.sha256(markdown.encode("utf-8")).hexdigest()


def preview_fragment(workspace_dir: str, relative_path: str, markdown: str,
                     fragment_id: str) -> str:
    fragment = _fragment(relative_path, markdown, fragment_id)
    kb = _kb(workspace_dir)
    preview = kb.preview(fragment.plain, origin=fragment.id, mode="import")
    return json.dumps({
        "protocol": "dpksys.decision.v1",
        "source_hash": _source_hash(markdown),
        "fragment_id": fragment.id,
        "locator": fragment.locator,
        "source_text": fragment.text,
        "preview": preview.to_dict(),
        "journal_head": kb.journal.head,
        "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _checked_manual_text(value: str) -> str:
    """An explicit human statement, never a rewrite of the source Markdown."""
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= 4000 \
            or "\n" in value or "\r" in value:
        raise ValueError("Manual interpretation must be one statement of 1..4000 characters")
    return value.strip()


def preview_manual_fragment(workspace_dir: str, relative_path: str, markdown: str,
                            fragment_id: str, manual_text: str) -> str:
    """Preview a separately labeled human paraphrase of an unchanged source."""
    statement = _checked_manual_text(manual_text)
    fragment = _fragment(relative_path, markdown, fragment_id)
    kb = _kb(workspace_dir)
    preview = kb.preview(statement, origin=fragment.id, mode="import")
    return json.dumps({
        "protocol": "dpksys.decision.v1",
        "source_hash": _source_hash(markdown),
        "fragment_id": fragment.id,
        "locator": fragment.locator,
        "source_text": fragment.text,
        "manual_text": statement,
        "manual_text_hash": _source_hash(statement),
        "manual_interpretation": True,
        "preview": preview.to_dict(),
        "journal_head": kb.journal.head,
        "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def record_decision(workspace_dir: str, relative_path: str, markdown: str,
                    fragment_id: str, reading_id: str, role: str,
                    expected_hash: str, expected_head: str | None) -> str:
    """Append only after the native UI's explicit choice and confirmation.

    Recompute the interpretation from current source, checking for changed
    notes, modified journal or a different reading before authorizing a write.
    """
    return _record_decision(workspace_dir, relative_path, markdown, fragment_id,
                            reading_id, role, expected_hash, expected_head)


def record_manual_decision(workspace_dir: str, relative_path: str, markdown: str,
                           fragment_id: str, manual_text: str, expected_manual_hash: str,
                           reading_id: str, role: str, expected_hash: str,
                           expected_head: str | None) -> str:
    """Commit only the exact manually previewed reading with bound source/head."""
    statement = _checked_manual_text(manual_text)
    if _source_hash(statement) != expected_manual_hash:
        raise ValueError("Human statement changed after preview")
    return _record_decision(workspace_dir, relative_path, markdown, fragment_id,
                            reading_id, role, expected_hash, expected_head,
                            statement)


def _record_decision(workspace_dir: str, relative_path: str, markdown: str,
                     fragment_id: str, reading_id: str, role: str,
                     expected_hash: str, expected_head: str | None,
                     manual_text: str | None = None) -> str:
    if role not in ("claim", "hypothesis", "fact", "reject", "defer"):
        raise ValueError("Unknown human decision")
    if _source_hash(markdown) != expected_hash:
        raise ValueError("Source note changed after preview")
    fragment = _fragment(relative_path, markdown, fragment_id)
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed after preview")
    statement = fragment.plain if manual_text is None else manual_text
    preview = kb.preview(statement, origin=fragment.id, mode="import")
    option = next((o for o in preview.options if o["reading_id"] == reading_id), None)
    if option is None or preview.formalization.status in ("NO_PARSE", "UNRESOLVED"):
        raise ValueError("Reading is unavailable or unresolved")
    if role in ("claim", "hypothesis", "fact"):
        if any(str(d.get("severity", "")).lower() == "error" for d in option["diagnostics"]):
            raise ValueError("Candidate contains validation errors")

    # Initialization and journal append are the first persistent writes in
    # this flow. They never target the original user-selected Markdown file.
    kb.ws.init()
    if role == "reject":
        e = kb.reject(targets=option["node_ids"], reason="explicit mobile decision")
        entries = [e]
    elif role == "defer":
        e = kb.defer([fragment.id], reason="explicit mobile decision")
        entries = [e]
    else:
        provenance = {
            "source_type": "vault_note" if manual_text is None else "human_interpretation",
            "document": relative_path,
            "document_sha": expected_hash,
            "fragment_id": fragment.id,
            "locator": fragment.locator,
            "span": list(fragment.span),
            "text": fragment.text,
            "text_hash": fragment.text_hash,
            "extraction": {
                "method": "cnl" if manual_text is None else "manual_reformulation",
                "reading_id": reading_id,
            },
        }
        if manual_text is not None:
            provenance["human_statement"] = manual_text
            provenance["human_statement_hash"] = _source_hash(manual_text)
        result = kb.accept(option["nodes"], provenance=provenance, role=role,
                           note="Explicitly reviewed on Android")
        entries = result.entries

    if kb.journal.verify():
        raise ValueError("Journal failed integrity verification after decision")
    return json.dumps({
        "protocol": "dpksys.decision.v1",
        "journal_ids": [e.id for e in entries],
        "journal_head": kb.journal.head,
        "decision": role,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _prepare_claim_batch(kb: KnowledgeBase, selection_json: str):
    """Reparse each live note; only one selected, unambiguous claim per item."""
    if not isinstance(selection_json, str) or len(selection_json) > 16_000_000:
        raise ValueError("Invalid bounded batch input")
    selections = json.loads(selection_json)
    if not isinstance(selections, list) or not 2 <= len(selections) <= 8:
        raise ValueError("Batch must contain 2..8 explicitly selected claims")
    items = []
    summary = []
    unique_sources = set()
    unique_nodes = set()
    for selected in selections:
        if not isinstance(selected, dict) or set(selected) != {"path", "markdown", "fragment_id", "reading_id"}:
            raise ValueError("Unexpected batch item fields")
        path, markdown = selected["path"], selected["markdown"]
        fragment = _fragment(path, markdown, selected["fragment_id"])
        origin = (path, fragment.id)
        if origin in unique_sources:
            raise ValueError("Duplicate batch source")
        unique_sources.add(origin)
        preview = kb.preview(fragment.plain, origin=fragment.id, mode="import")
        if preview.formalization.status in ("NO_PARSE", "UNRESOLVED"):
            raise ValueError("Batch source has unresolved interpretation")
        option = next((o for o in preview.options
                       if o["reading_id"] == selected["reading_id"]), None)
        if option is None or len(option["nodes"]) != 1 or option["unresolved"]:
            raise ValueError("Batch requires one explicitly selected valid reading")
        if option["conflicts"] or option["known"] or option["rejected_before"] or any(
                str(d.get("severity", "")).lower() == "error" for d in option["diagnostics"]):
            raise ValueError("Batch contains conflicting, known or invalid candidate")
        node = convert_role(option["nodes"][0], "claim")
        if node.id in unique_nodes or node.id in kb.state.records or node.id in kb.state.rejected:
            raise ValueError("Duplicate or previously resolved batch assertion")
        unique_nodes.add(node.id)
        source_hash = _source_hash(markdown)
        provenance = {
            "source_type": "vault_note", "document": path, "document_sha": source_hash,
            "fragment_id": fragment.id, "locator": fragment.locator,
            "span": list(fragment.span), "text": fragment.text,
            "text_hash": fragment.text_hash,
            "extraction": {"method": "cnl", "reading_id": option["reading_id"]},
        }
        items.append({"node": node, "provenance": provenance})
        summary.append({"path": path, "locator": fragment.locator,
                        "reading_id": option["reading_id"], "paraphrase": option["paraphrase"],
                        "source_hash": source_hash, "fragment_id": fragment.id,
                        "node_id": node.id, "ksf": node.ksf(),
                        "diagnostic_count": len(option["diagnostics"])})
    # Batch-wide consistency, in addition to individual checks above.
    if any(d.severity.value == "error" for d in kb.check_candidate_nodes([i["node"] for i in items])):
        raise ValueError("Combined batch fails semantic validation")
    token = hashlib.sha256(json.dumps({"head": kb.journal.head, "items": summary},
                                   sort_keys=True, ensure_ascii=False,
                                   separators=(",", ":")).encode("utf-8")).hexdigest()
    return items, summary, token


def preview_claim_batch(workspace_dir: str, selection_json: str) -> str:
    """Bounded human batch preview; never creates a workspace or journal."""
    kb = _kb(workspace_dir)
    _, summary, token = _prepare_claim_batch(kb, selection_json)
    return json.dumps({
        "protocol": "dpksys.decision.v1", "decision": "batch_accept",
        "role": "claim", "items": summary, "batch_hash": token,
        "journal_head": kb.journal.head, "read_only": True,
        "requires_human_confirmation": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def accept_claim_batch(workspace_dir: str, selection_json: str, expected_batch_hash: str,
                       expected_head: str | None) -> str:
    """Explicit one-event commit; any stale note/journal/input aborts all."""
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed after batch preview")
    items, summary, token = _prepare_claim_batch(kb, selection_json)
    if token != expected_batch_hash:
        raise ValueError("Batch source/reading changed after preview")
    kb.ws.init()
    result = kb.batch_accept_claims(items, note="Explicit batch of human-reviewed Android claims")
    if len(result.entries) != 1 or kb.journal.verify():
        raise ValueError("Atomic batch journal verification failed")
    return json.dumps({
        "protocol": "dpksys.decision.v1", "decision": "batch_accept",
        "role": "claim", "count": len(summary),
        "journal_ids": [result.entries[0].id], "journal_head": kb.journal.head,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def active_decisions(workspace_dir: str, offset: int = 0) -> str:
    """Read-only, bounded active-record browser for explicit human review.

    No candidate or model is allowed to revoke knowledge via this endpoint.
    Note text is displayed only in the local app UI, never in device QA logs.
    """
    if not isinstance(offset, int) or offset < 0 or offset > 1_000_000:
        raise ValueError("Invalid decision page")
    kb = _kb(workspace_dir)
    records = [rec for rec in kb.state.records.values() if kb.state.active(rec.id)]
    records.sort(key=lambda rec: (rec.updated_at, rec.id), reverse=True)
    page = records[offset:offset + MAX_DISPLAYED]
    return json.dumps({
        "protocol": "dpksys.decision.v1",
        "journal_head": kb.journal.head,
        "total": len(records),
        "offset": offset,
        "next_offset": offset + len(page) if offset + len(page) < len(records) else None,
        "records": [{
            "record_id": rec.id,
            "role": rec.role,
            "status": rec.status.value,
            "summary": rec.node.ksf()[:220],
        } for rec in page],
        "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


# Stage 22: a read-only, navigable decision workspace. The source index is
# *disposable*; the verified journal is authoritative. Never treat a candidate
# as a fact or assume an indexed source fragment is still current on disk.
DECISION_CATEGORIES = ("pending", "active", "facts", "hypotheses",
                       "rejected", "deferred", "obsolete", "history")
DECISION_PAGE_SIZE = 30


def _decision_index(workspace: Path):
    """Open only an existing index, without connect() migrations or WAL writes."""
    db_path = Workspace(workspace).db_path
    if not db_path.is_file():
        return None
    con = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
    con.execute("PRAGMA query_only=ON")
    return con


def _resolved_fragments(kb: KnowledgeBase) -> set[str]:
    resolved = set(kb.state.deferred)
    for record in kb.state.records.values():
        for source in record.provenance:
            if source.get("fragment_id"):
                resolved.add(source["fragment_id"])
    return resolved


def _candidate_pending(row, resolved: set[str], rejected: set[str]) -> bool:
    if row[2] in resolved:
        return False
    try:
        nodes = json.loads(row[6])
    except (TypeError, ValueError):
        return True  # Invalid interpretations must remain visible for inspection.
    return not nodes or not all(node.get("id") in rejected for node in nodes)


def _journal_label(entry: Entry) -> str:
    data = entry.data
    if entry.op in ("reject", "defer"):
        return (data.get("reason") or entry.op)[:220]
    if entry.op in ("retract", "revise", "split"):
        return str(data.get("target") or entry.op)[:220]
    if entry.op == "merge":
        return ", ".join(data.get("targets", []))[:220]
    return (entry.note or entry.op)[:220]


def decision_overview(workspace_dir: str, category: str = "pending",
                      offset: int = 0, search: str = "") -> str:
    """Bounded pages across proposals, records and journal events.

    Counts are for the *entire* category; `total` is the filtered count. An
    explicit empty state is returned even when there is no workspace yet.
    """
    if category not in DECISION_CATEGORIES or not isinstance(offset, int) \
            or offset < 0 or offset > 1_000_000 or not isinstance(search, str) \
            or len(search) > 120:
        raise ValueError("Invalid decision view query")
    kb = _kb(workspace_dir)
    records = sorted(kb.state.records.values(),
                     key=lambda r: (r.updated_at, r.id), reverse=True)
    events = list(reversed(kb.state.entries))
    rejected = [e for e in events if e.op == "reject"]
    deferred = [e for e in events if e.op == "defer"]
    active = [r for r in records if kb.state.active(r.id)]
    obsolete = [r for r in records if not kb.state.active(r.id)]
    counts = {
        "pending": 0, "active": len(active),
        "facts": sum(r.role == "fact" for r in active),
        "hypotheses": sum(r.role == "hypothesis" for r in active),
        "rejected": len(rejected), "deferred": len(deferred),
        "obsolete": len(obsolete), "history": len(events),
    }
    needle = search.casefold().strip()
    filtered = []
    total = 0

    def add(row):
        nonlocal total
        if needle and needle not in " ".join(str(v) for v in row.values()).casefold():
            return
        if offset <= total < offset + DECISION_PAGE_SIZE:
            filtered.append(row)
        total += 1

    if category == "pending":
        resolved = _resolved_fragments(kb)
        con = _decision_index(Path(workspace_dir))
        if con is not None:
            try:
                rows = con.execute(
                    "SELECT c.id, f.doc, f.id, f.locator, c.paraphrase, c.reading_id, c.nodes "
                    "FROM candidates c JOIN fragments f ON f.id=c.origin "
                    "JOIN documents d ON d.path=f.doc WHERE d.status='PARSED' "
                    "ORDER BY f.doc COLLATE NOCASE, f.ordinal, c.id"
                )
                for candidate in rows:
                    if _candidate_pending(candidate, resolved, kb.state.rejected):
                        counts["pending"] += 1
                        add({"kind": "candidate", "id": candidate[0],
                             "role": "interpretation", "status": "PENDING",
                             "path": candidate[1], "locator": candidate[3],
                             "summary": candidate[4][:220]})
            finally:
                con.close()
    else:
        groups = {
            "active": active, "facts": [r for r in active if r.role == "fact"],
            "hypotheses": [r for r in active if r.role == "hypothesis"],
            "obsolete": obsolete,
        }
        if category in groups:
            for rec in groups[category]:
                provenance = rec.provenance[-1] if rec.provenance else {}
                add({"kind": "record", "id": rec.id, "role": rec.role,
                     "status": rec.status.value, "path": provenance.get("document", ""),
                     "locator": provenance.get("locator", ""),
                     "summary": rec.node.ksf()[:220]})
        else:
            for event in {"rejected": rejected, "deferred": deferred,
                          "history": events}[category]:
                add({"kind": "journal", "id": event.id, "role": event.op,
                     "status": "JOURNAL", "time": event.ts,
                     "summary": _journal_label(event)})

    return json.dumps({
        "protocol": "dpksys.decision.v1", "category": category,
        "journal_head": kb.journal.head, "offset": offset,
        "total": total, "counts": counts, "items": filtered,
        "next_offset": offset + len(filtered) if offset + DECISION_PAGE_SIZE < total else None,
        "read_only": True, "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def decision_detail(workspace_dir: str, kind: str, identifier: str) -> str:
    """Full decision context, strictly read-only; source snippets are snapshots."""
    if kind not in ("candidate", "record", "journal") or not isinstance(identifier, str) \
            or not identifier or len(identifier) > 200:
        raise ValueError("Invalid decision selection")
    kb = _kb(workspace_dir)
    detail = {"protocol": "dpksys.decision.v1", "kind": kind, "id": identifier,
              "journal_head": kb.journal.head, "read_only": True,
              "requires_human_review": True}
    if kind == "candidate":
        con = _decision_index(Path(workspace_dir))
        if con is None:
            raise ValueError("No semantic analysis available")
        try:
            row = con.execute(
                "SELECT f.doc, f.id, f.locator, f.text, c.paraphrase, c.reading_id, "
                "c.nodes, c.diagnostics, d.sha256, f.plain "
                "FROM candidates c JOIN fragments f ON f.id=c.origin "
                "JOIN documents d ON d.path=f.doc "
                "WHERE c.id=? AND d.status='PARSED'", (identifier,)).fetchone()
        finally:
            con.close()
        if row is None:
            raise ValueError("Interpretation no longer indexed")
        nodes = json.loads(row[6])
        diagnostics = json.loads(row[7])
        conflicts = []
        conflicts_available = False
        try:
            preview = kb.preview(row[9], origin=row[1], mode="import")
            reading = next((item for item in preview.options
                            if item["reading_id"] == row[5]), None)
            if reading is not None:
                conflicts_available = True
                conflicts = [str(value)[:300] for value in reading["conflicts"][:8]]
        except (ValueError, KeyError, TypeError):
            # The persisted index and active journal may no longer agree.
            # Do not invent a zero-conflict verdict in that situation.
            pass
        detail.update({"role": "interpretation", "status": "PENDING",
                       "path": row[0], "fragment_id": row[1], "locator": row[2],
                       "source_text": row[3][:4000], "source_truncated": len(row[3]) > 4000,
                       "source_hash": row[8], "interpretation": row[4][:4000],
                       "reading_id": row[5],
                       "formalization": "\n".join(node_from_dict(n).ksf()
                                                   for n in nodes)[:4000],
                       "diagnostics": diagnostics[:12], "conflicts": conflicts,
                       "conflicts_available": conflicts_available,
                       "source_snapshot": True})
        return json.dumps(detail, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    if kind == "record":
        rec = kb.state.records.get(identifier)
        if rec is None:
            raise ValueError("Knowledge record is unavailable")
        provenance = rec.provenance[-1] if rec.provenance else {}
        related_events = [e for e in kb.state.entries if e.id in rec.journal][-12:]
        detail.update({"role": rec.role, "status": rec.status.value,
                       "path": provenance.get("document", ""),
                       "locator": provenance.get("locator", ""),
                       "source_text": str(provenance.get("text") or "")[:4000],
                       "source_hash": provenance.get("document_sha", ""),
                       "source_snapshot": True,
                       "interpretation": rec.node.ksf()[:4000],
                       "formalization": rec.node.ksf()[:4000],
                       "confidence": rec.confidence.value,
                       "knowledge_type": rec.knowledge_type,
                       "verified_at": provenance.get("verified_at", ""),
                       "verifier": provenance.get("verifier", ""),
                       "supersedes": rec.supersedes[:10],
                       "superseded_by": rec.superseded_by,
                       "superseded_by_many": rec.superseded_by_many[:10],
                       "history": [{"id": e.id, "op": e.op, "time": e.ts}
                                   for e in related_events], "conflicts": [],
                       "diagnostics": []})
    else:
        event = next((e for e in kb.state.entries if e.id == identifier), None)
        if event is None:
            raise ValueError("Journal entry is unavailable")
        detail.update({"role": event.op, "status": "JOURNAL", "time": event.ts,
                       "interpretation": _journal_label(event),
                       "formalization": "", "source_text": "", "source_snapshot": False,
                       "history": [{"id": event.id, "op": event.op, "time": event.ts}],
                       "targets": event.data.get("targets",
                                                 [event.data["target"]] if "target" in event.data
                                                 else event.data.get("candidates", [])),
                       "diagnostics": [], "conflicts": []})
    return json.dumps(detail, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def preview_retraction(workspace_dir: str, record_id: str) -> str:
    """Non-mutating consequence preview, tied to the current journal head."""
    kb = _kb(workspace_dir)
    rec = kb.state.records.get(record_id)
    if rec is None or not kb.state.active(record_id):
        raise ValueError("Record is missing or no longer active")
    delta = kb.what_if_retract(record_id)
    full_summary = rec.node.ksf()
    return json.dumps({
        "protocol": "dpksys.decision.v1",
        "record_id": record_id,
        "journal_head": kb.journal.head,
        "role": rec.role,
        "status": rec.status.value,
        "summary": full_summary[:4000],
        "summary_truncated": len(full_summary) > 4000,
        "affected_derived": len(delta.removed),
        "rederived": len(delta.rederived),
        "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def retract_decision(workspace_dir: str, record_id: str, expected_head: str | None) -> str:
    """Append an explicit human retraction; never erase journal or source notes."""
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed after retraction preview")
    if record_id not in kb.state.records or not kb.state.active(record_id):
        raise ValueError("Record is missing or no longer active")
    kb.ws.init()
    result = kb.retract(record_id, reason="explicit Android human retraction")
    if kb.journal.verify():
        raise ValueError("Journal failed integrity verification after retraction")
    if kb.state.active(record_id):
        raise ValueError("Retracted record remained active")
    return json.dumps({
        "protocol": "dpksys.decision.v1",
        "record_id": record_id,
        "journal_ids": [entry.id for entry in result.entries],
        "journal_head": kb.journal.head,
        "decision": "retract",
        "affected_derived": len(result.auth.removed),
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _revision_preview(kb: KnowledgeBase, record_id: str, statement: str):
    """Validate a human-authored replacement without touching any file."""
    if not isinstance(statement, str) or not statement.strip() or len(statement) > 2000:
        raise ValueError("Replacement must contain 1–2000 characters")
    rec = kb.state.records.get(record_id)
    if rec is None or not kb.state.active(record_id) or rec.role not in (
            "claim", "hypothesis", "fact"):
        raise ValueError("Record cannot be revised in the Android editor")
    preview = kb.preview(statement.strip(), origin="android-revision:" + record_id,
                         mode="assert")
    if preview.formalization.status in ("NO_PARSE", "UNRESOLVED"):
        raise ValueError("Replacement cannot be unambiguously formalized")
    options = []
    for candidate in preview.options:
        if len(candidate["nodes"]) != 1 or candidate["unresolved"] or any(
                str(d.get("severity", "")).lower() == "error"
                for d in candidate["diagnostics"]):
            continue
        node = convert_role(candidate["nodes"][0], rec.role)
        # Duplicate IDs must be resolved through a separate merge workflow.
        if node.id == record_id or kb.state.active(node.id) or node.id in kb.state.rejected:
            continue
        options.append((candidate, node))
    if not options:
        raise ValueError("No safe replacement interpretation available")
    return rec, preview, options


def preview_revision(workspace_dir: str, record_id: str, statement: str) -> str:
    """Read-only preview; conflicts and exact interpretation remain visible."""
    kb = _kb(workspace_dir)
    rec, preview, options = _revision_preview(kb, record_id, statement)
    return json.dumps({
        "protocol": "dpksys.decision.v1",
        "record_id": record_id,
        "journal_head": kb.journal.head,
        "statement_hash": _source_hash(statement),
        "role": rec.role,
        "old_summary": rec.node.ksf()[:4000],
        "old_summary_truncated": len(rec.node.ksf()) > 4000,
        "parse_status": preview.formalization.status,
        "options": [{
            "reading_id": o[0]["reading_id"],
            "paraphrase": o[0]["paraphrase"][:1000],
            "new_summary": o[1].ksf()[:4000],
            "new_summary_truncated": len(o[1].ksf()) > 4000,
            "conflict_count": len(o[0]["conflicts"]),
            "diagnostic_count": len(o[0]["diagnostics"]),
        } for o in options],
        "requires_human_confirmation": True,
        "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def revise_decision(workspace_dir: str, record_id: str, statement: str,
                    reading_id: str, reason: str, expected_hash: str,
                    expected_head: str | None) -> str:
    """Commit only an explicitly confirmed, freshly revalidated one-node edit."""
    if _source_hash(statement) != expected_hash:
        raise ValueError("Replacement changed after preview")
    if not isinstance(reason, str) or len(reason) > 1000:
        raise ValueError("Reason is too long")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed after revision preview")
    _, _, options = _revision_preview(kb, record_id, statement)
    chosen = next((n for candidate, n in options
                   if candidate["reading_id"] == reading_id), None)
    if chosen is None:
        raise ValueError("Selected reading changed after preview")
    kb.ws.init()
    result = kb.revise(record_id, chosen,
                       reason=reason.strip() or "explicit manual Android correction",
                       provenance={
                           "source_type": "human_input",
                           "method": "manual_revision",
                           "previous_record": record_id,
                           "statement_sha": expected_hash,
                           "reading_id": reading_id,
                       })
    if kb.journal.verify() or kb.state.active(record_id) or not kb.state.active(chosen.id):
        raise ValueError("Revision journal state inconsistent")
    return json.dumps({
        "protocol": "dpksys.decision.v1",
        "decision": "revise",
        "old_record_id": record_id,
        "new_record_id": chosen.id,
        "journal_ids": [e.id for e in result.entries],
        "journal_head": kb.journal.head,
        "affected_derived": len(result.auth.removed),
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _split_hash(statements: list[str]) -> str:
    return _source_hash(json.dumps(statements, ensure_ascii=False, separators=(",", ":")))


def preview_split(workspace_dir: str, record_id: str,
                  first_statement: str, second_statement: str) -> str:
    """Preview both human-authored successors, without creating a journal."""
    kb = _kb(workspace_dir)
    first_rec, first_parse, first_options = _revision_preview(kb, record_id, first_statement)
    second_rec, second_parse, second_options = _revision_preview(kb, record_id, second_statement)
    if first_rec.id != second_rec.id or not any(
            left.id != right.id for _, left in first_options for _, right in second_options):
        raise ValueError("Split requires two different successor statements")

    def summarize(options):
        return [{
            "reading_id": option["reading_id"],
            "paraphrase": option["paraphrase"][:1000],
            "new_summary": node.ksf()[:4000],
            "conflict_count": len(option["conflicts"]),
            "diagnostic_count": len(option["diagnostics"]),
        } for option, node in options]

    return json.dumps({
        "protocol": "dpksys.decision.v1", "record_id": record_id,
        "journal_head": kb.journal.head, "role": first_rec.role,
        "old_summary": first_rec.node.ksf()[:4000],
        "old_summary_truncated": len(first_rec.node.ksf()) > 4000,
        "split_hash": _split_hash([first_statement, second_statement]),
        "first_parse_status": first_parse.formalization.status,
        "second_parse_status": second_parse.formalization.status,
        "first_options": summarize(first_options),
        "second_options": summarize(second_options),
        "requires_human_confirmation": True, "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def split_decision(workspace_dir: str, record_id: str,
                   first_statement: str, second_statement: str,
                   first_reading_id: str, second_reading_id: str,
                   reason: str, expected_hash: str, expected_head: str | None) -> str:
    """Atomic append-only split after explicit review and fresh CAS verification."""
    if _split_hash([first_statement, second_statement]) != expected_hash:
        raise ValueError("Split statements changed after preview")
    if not isinstance(reason, str) or len(reason) > 1000:
        raise ValueError("Split reason is too long")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed after split preview")
    _, _, first_options = _revision_preview(kb, record_id, first_statement)
    _, _, second_options = _revision_preview(kb, record_id, second_statement)
    first = next((node for opt, node in first_options
                  if opt["reading_id"] == first_reading_id), None)
    second = next((node for opt, node in second_options
                   if opt["reading_id"] == second_reading_id), None)
    if first is None or second is None or first.id == second.id:
        raise ValueError("Split requires two distinct selected readings")
    kb.ws.init()
    result = kb.split(record_id, [first, second],
                      reason=reason.strip() or "explicit manual Android split",
                      provenance={
                          "source_type": "human_input", "method": "manual_split",
                          "previous_record": record_id, "statements_sha": expected_hash,
                          "reading_ids": [first_reading_id, second_reading_id],
                      })
    if kb.journal.verify() or kb.state.active(record_id) \
            or not kb.state.active(first.id) or not kb.state.active(second.id):
        raise ValueError("Split journal state inconsistent")
    return json.dumps({
        "protocol": "dpksys.decision.v1", "decision": "split",
        "old_record_id": record_id,
        "new_record_ids": [first.id, second.id],
        "journal_ids": [e.id for e in result.entries],
        "journal_head": kb.journal.head,
        "affected_derived": len(result.auth.removed),
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _merge_preview(kb: KnowledgeBase, first_id: str, second_id: str, statement: str):
    if first_id == second_id:
        raise ValueError("Merge requires two distinct records")
    first, parsing, candidates = _revision_preview(kb, first_id, statement)
    second = kb.state.records.get(second_id)
    if second is None or not kb.state.active(second_id) or second.role != first.role:
        raise ValueError("Merge requires two active records of the same role")
    # Existing (even obsolete) record IDs must never be silently reactivated.
    options = [(option, node) for option, node in candidates
               if node.id not in kb.state.records]
    if not options:
        raise ValueError("No new safe merged interpretation available")
    return first, second, parsing, options


def preview_merge(workspace_dir: str, first_id: str, second_id: str, statement: str) -> str:
    """Read-only two-to-one proposal; no similarity-based auto-merging."""
    kb = _kb(workspace_dir)
    first, second, parsing, options = _merge_preview(kb, first_id, second_id, statement)
    return json.dumps({
        "protocol": "dpksys.decision.v1", "record_ids": [first_id, second_id],
        "journal_head": kb.journal.head, "role": first.role,
        "statement_hash": _source_hash(statement),
        "old_summaries": [first.node.ksf()[:4000], second.node.ksf()[:4000]],
        "old_summaries_truncated": [len(first.node.ksf()) > 4000,
                                    len(second.node.ksf()) > 4000],
        "parse_status": parsing.formalization.status,
        "options": [{
            "reading_id": option["reading_id"],
            "paraphrase": option["paraphrase"][:1000],
            "new_summary": node.ksf()[:4000],
            "conflict_count": len(option["conflicts"]),
            "diagnostic_count": len(option["diagnostics"]),
        } for option, node in options],
        "read_only": True, "requires_human_confirmation": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def merge_decisions(workspace_dir: str, first_id: str, second_id: str,
                    statement: str, reading_id: str, reason: str,
                    expected_hash: str, expected_head: str | None) -> str:
    """Atomically retire exactly two approved records after one explicit confirmation."""
    if _source_hash(statement) != expected_hash:
        raise ValueError("Merged statement changed after preview")
    if not isinstance(reason, str) or len(reason) > 1000:
        raise ValueError("Merge reason is too long")
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed after merge preview")
    _, _, _, options = _merge_preview(kb, first_id, second_id, statement)
    merged = next((node for option, node in options
                   if option["reading_id"] == reading_id), None)
    if merged is None:
        raise ValueError("Selected merged interpretation changed after preview")
    kb.ws.init()
    result = kb.merge_records([first_id, second_id], merged,
                              reason=reason.strip() or "explicit manual Android merge",
                              provenance={
                                  "source_type": "human_input", "method": "manual_merge",
                                  "merged_records": [first_id, second_id],
                                  "statement_sha": expected_hash, "reading_id": reading_id,
                              })
    if kb.journal.verify() or kb.state.active(first_id) or kb.state.active(second_id) \
            or not kb.state.active(merged.id):
        raise ValueError("Merge journal state inconsistent")
    return json.dumps({
        "protocol": "dpksys.decision.v1", "decision": "merge",
        "old_record_ids": [first_id, second_id],
        "new_record_id": merged.id,
        "journal_ids": [e.id for e in result.entries],
        "journal_head": kb.journal.head,
        "affected_derived": len(result.auth.removed),
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


_READ_ONLY_OPS = {
    DialogueOp.ASK, DialogueOp.VERIFY, DialogueOp.DEFINE, DialogueOp.HYPOTHESIZE,
    DialogueOp.EXPLAIN, DialogueOp.COMPARE, DialogueOp.FIND_CONTRADICTIONS,
    DialogueOp.DERIVE, DialogueOp.TRACE, DialogueOp.GENERALIZE,
    DialogueOp.SPECIALIZE, DialogueOp.UNKNOWNS, DialogueOp.WHAT_IF,
}


def ask_knowledge(workspace_dir: str, question: str) -> str:
    """Use the existing deterministic query engine; never authorize writes."""
    if not isinstance(question, str) or not question.strip() or len(question) > 2_000:
        raise ValueError("Query must be nonempty and at most 2000 characters")
    intent = classify_intent(question)
    if intent.op not in _READ_ONLY_OPS:
        raise ValueError("Only read-only knowledge questions are allowed")
    kb = _kb(workspace_dir)
    response = Dialogue(kb).handle(question, yes=False)
    return json.dumps({
        "protocol": "dpksys.query.v1",
        "operation": response.op,
        "status": response.status,
        "answer": response.answer,
        "sections": response.sections,
        "journal_head": kb.journal.head,
        "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def knowledge_diagnostics(workspace_dir: str) -> str:
    """Return inspectable current-state summaries, without changing the journal."""
    kb = _kb(workspace_dir)
    answers = Answerer(kb)
    contradictions = answers.contradictions()
    unknowns = answers.unknowns()
    return json.dumps({
        "protocol": "dpksys.query.v1",
        "journal_head": kb.journal.head,
        "record_count": len(kb.state.records),
        "authoritative_count": len(kb.state.authoritative()),
        "claim_count": len(kb.state.claims()),
        "contradiction_count": len(contradictions),
        "contradictions": contradictions[:12],
        "unknowns": unknowns,
        "read_only": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


MAX_JOURNAL_BYTES = 8_000_000


def journal_export_info(workspace_dir: str) -> str:
    """Verify the authoritative journal before a user-directed encrypted export."""
    kb = _kb(workspace_dir)
    path = kb.ws.journal_path
    if not path.is_file() or path.stat().st_size > MAX_JOURNAL_BYTES:
        raise ValueError("Journal is empty or too large for portable backup")
    content = path.read_bytes()
    if not content:
        raise ValueError("No decisions to export")
    return json.dumps({
        "protocol": "dpksys.backup.v1",
        "count": len(kb.journal.entries()),
        "head": kb.journal.head,
        "sha256": hashlib.sha256(content).hexdigest(),
    }, ensure_ascii=False, sort_keys=True)


def _validate_journal_text(content: str) -> dict:
    if not isinstance(content, str) or len(content.encode("utf-8")) > MAX_JOURNAL_BYTES:
        raise ValueError("Backup is too large")
    prev = None
    count = 0
    for line in content.splitlines():
        if not line.strip():
            continue
        obj = json.loads(line)
        e = Entry(obj["seq"], obj["id"], obj.get("prev"), obj["ts"], obj["actor"],
                  obj["op"], obj["data"], obj.get("note"))
        count += 1
        if e.seq != count or e.prev != prev or e.id != entry_id(
                e.prev, e.seq, e.ts, e.actor, e.op, e.data, e.note):
            raise ValueError("Backup has a broken or modified hash chain")
        prev = e.id
    if count == 0:
        raise ValueError("Backup has no decisions")
    return {"count": count, "head": prev,
            "sha256": hashlib.sha256(content.encode("utf-8")).hexdigest()}


def journal_restore_preview(workspace_dir: str, content: str) -> str:
    info = _validate_journal_text(content)
    kb = _kb(workspace_dir)
    if kb.journal.entries():
        raise ValueError("Current journal is not empty; refusing to overwrite decisions")
    return json.dumps(dict(info, protocol="dpksys.backup.v1"), sort_keys=True)


def journal_restore_commit(workspace_dir: str, content: str,
                           expected_sha256: str) -> str:
    info = _validate_journal_text(content)
    if info["sha256"] != expected_sha256:
        raise ValueError("Backup contents changed after preview")
    kb = _kb(workspace_dir)
    if kb.journal.entries():
        raise ValueError("Existing decisions must never be overwritten")
    path = kb.ws.journal_path
    path.parent.mkdir(parents=True, exist_ok=True)
    import os
    import tempfile
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=path.parent,
                prefix=".journal-import-", delete=False) as target:
            temporary = Path(target.name)
            target.write(content)
            target.flush()
            os.fsync(target.fileno())
        # Native UI serializes these operations; refuse nonempty state again.
        if path.exists() and path.stat().st_size != 0:
            raise ValueError("Journal was modified during import")
        temporary.replace(path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    restored = _kb(workspace_dir)
    if restored.journal.head != info["head"]:
        raise ValueError("Restored journal verification failed")
    return json.dumps(dict(info, protocol="dpksys.backup.v1"), sort_keys=True)
