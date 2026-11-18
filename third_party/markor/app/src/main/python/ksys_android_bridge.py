"""Deterministic, read-only Android entry point for repository-root ksys.

No vault filesystem access, no journal writes, no network or model inference.
Android passes the selected Markdown text; this module uses the same imported
source parser and classifier as the desktop Python core.
"""
from __future__ import annotations

import json
import hashlib
from pathlib import Path

from ksys import KS_VERSION
from ksys.core.clock import SystemClock
from ksys.source.markdown import parse_document
from ksys.source.coverage import check_coverage
from ksys.importer.classify import classify
from ksys.importer.language_analyzer import LanguageAnalyzer
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace

MAX_CHARACTERS = 150_000
MAX_DISPLAYED = 24


def inspect_note(relative_path: str, markdown: str) -> str:
    if not isinstance(relative_path, str) or not isinstance(markdown, str):
        raise ValueError("Expected relative path and Markdown content")
    if not relative_path or relative_path.startswith("/") or "\\" in relative_path \
            or ".." in relative_path.split("/") or not relative_path.endswith((".md", ".markdown")):
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
