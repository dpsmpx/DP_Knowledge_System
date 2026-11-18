"""Deterministic, read-only Android entry point for repository-root ksys.

No vault filesystem access, no journal writes, no network or model inference.
Android passes the selected Markdown text; this module uses the same imported
source parser and classifier as the desktop Python core.
"""
from __future__ import annotations

import json

from ksys import KS_VERSION
from ksys.source.markdown import parse_document
from ksys.source.coverage import check_coverage
from ksys.importer.classify import classify
from ksys.importer.language_analyzer import LanguageAnalyzer

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
