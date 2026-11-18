"""Offline Android bridge for the actual repository-root ksys package.

The explicitly selected vault is only read. Automatic analysis writes solely
to an app-private disposable index, never to original notes or the human
approved knowledge journal. Journal writes require a separate review flow.
"""
from __future__ import annotations

import json
import hashlib
from ksys.store.journal import Entry, entry_id
from pathlib import Path

from ksys import KS_VERSION
from ksys.core.clock import SystemClock
from ksys.source.markdown import parse_document
from ksys.source.coverage import check_coverage
from ksys.importer.classify import classify
from ksys.importer.language_analyzer import LanguageAnalyzer
from ksys.importer.vault import import_vault, MARKDOWN_EXT
from ksys.importer.relations import related_claims
from ksys.audit.library import audit_summary, audit_page
from ksys.store.db import connect
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace
from ksys.dialogue.session import Dialogue
from ksys.dialogue.intents import classify as classify_intent
from ksys.query.answers import Answerer
from ksys.core.enums import DialogueOp

MAX_CHARACTERS = 2_000_000
MAX_DISPLAYED = 24
MAX_IMPORT_BYTES = 2_000_000
MAX_IMPORT_FILES = 200_000


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
    db_path = Workspace(workspace).db_path
    if not db_path.is_file():
        return json.dumps({"protocol": "dpksys.bulk.v1", "total": 0, "offset": offset,
                           "candidates": [], "requires_human_review": True})
    con = connect(db_path)
    try:
        total = con.execute(
            "SELECT COUNT(*) FROM candidates c JOIN fragments f ON f.id=c.origin "
            "JOIN documents d ON d.path=f.doc WHERE d.status='PARSED'"
        ).fetchone()[0]
        rows = con.execute(
            "SELECT f.doc, f.id, f.locator, c.paraphrase, c.reading_id "
            "FROM candidates c JOIN fragments f ON f.id=c.origin "
            "JOIN documents d ON d.path=f.doc WHERE d.status='PARSED' "
            "ORDER BY f.doc COLLATE NOCASE, f.ordinal, c.id LIMIT 80 OFFSET ?",
            (offset,)
        ).fetchall()
    finally:
        con.close()
    return json.dumps({
        "protocol": "dpksys.bulk.v1",
        "total": total, "offset": offset,
        "candidates": [{"path": row[0], "fragment_id": row[1],
                        "locator": row[2], "preview": row[3][:180],
                        "reading_id": row[4]} for row in rows],
        "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def note_relationships(workspace_dir: str, relative_path: str) -> str:
    """Compare unapproved structured assertions between different notes.

    A shared atom is evidence of a candidate relation, never evidence that
    either original claim is true. No private note text leaves this process.
    """
    workspace = Path(workspace_dir)
    if not workspace.is_absolute():
        raise ValueError("Knowledge workspace must be absolute")
    matches = related_claims(Workspace(workspace), relative_path)
    return json.dumps({
        "protocol": "dpksys.relations.v1",
        "path": relative_path,
        "matches": matches["matches"],
        "truncated": matches["truncated"],
        "source": "unapproved_candidate_assertions",
        "read_only": True,
        "requires_human_review": True,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def inspect_note(relative_path: str, markdown: str) -> str:
    if not isinstance(relative_path, str) or not isinstance(markdown, str):
        raise ValueError("Expected relative path and Markdown content")
    if not relative_path or relative_path.startswith("/") or "\\" in relative_path \
            or ".." in relative_path.split("/") \
            or not relative_path.lower().endswith((".md", ".markdown")):
        raise ValueError("Invalid relative Markdown path")
    if len(markdown) > MAX_CHARACTERS:
        raise ValueError("Note exceeds read-only inspection limit")

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


def record_decision(workspace_dir: str, relative_path: str, markdown: str,
                    fragment_id: str, reading_id: str, role: str,
                    expected_hash: str, expected_head: str | None) -> str:
    """Append only after the native UI's explicit choice and confirmation.

    Recompute the interpretation from current source, checking for changed
    notes, modified journal or a different reading before authorizing a write.
    """
    if role not in ("claim", "hypothesis", "fact", "reject", "defer"):
        raise ValueError("Unknown human decision")
    if _source_hash(markdown) != expected_hash:
        raise ValueError("Source note changed after preview")
    fragment = _fragment(relative_path, markdown, fragment_id)
    kb = _kb(workspace_dir)
    if kb.journal.head != expected_head:
        raise ValueError("Journal changed after preview")
    preview = kb.preview(fragment.plain, origin=fragment.id, mode="import")
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
            "source_type": "vault_note",
            "document": relative_path,
            "document_sha": expected_hash,
            "fragment_id": fragment.id,
            "locator": fragment.locator,
            "span": list(fragment.span),
            "text": fragment.text,
            "text_hash": fragment.text_hash,
            "extraction": {"method": "cnl", "reading_id": reading_id},
        }
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
