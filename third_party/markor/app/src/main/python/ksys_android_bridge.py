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
from ksys.store.kb import KnowledgeBase, convert_role
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
