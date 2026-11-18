"""Пакетный, идемпотентный и неразрушающий импорт Obsidian vault.

Гарантии:
* исходные файлы только читаются (открытие на чтение, никаких записей);
* каждый файл vault получает статус в манифесте (ничего не пропадает молча);
* каждый листовой фрагмент получает FragmentKind и Formalization;
* инвариант покрытия проверяется для каждого разобранного документа;
* повторный импорт без изменений даёт тот же манифест и те же ID
  (неизменившиеся по хешу документы не перепарсиваются).
"""
from __future__ import annotations

import fnmatch
import json
import os
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Protocol

from .. import (CLASSIFIER_VERSION, FRAGMENTER_VERSION, GRAMMAR_VERSION, KS_VERSION,
                PARSER_VERSION, SCHEMA_VERSION)
from ..core.clock import Clock
from ..core.enums import FragmentKind, Formalization
from ..core.errors import ErrorKind
from ..core.ids import canonical_json, digest, file_hash, nfc
from ..source.coverage import check_coverage
from ..source.markdown import parse_code_file, parse_document, parse_plain_text
from ..source.model import Fragment
from ..store.db import connect
from ..store.workspace import Workspace
from .classify import Classification, classify
from .relations import projected_atoms, RELATIONS_VERSION

SERVICE_DIRS = {".git", ".obsidian", ".makemd", ".space", ".stversions", ".stfolder", ".stfolder (1)",
                ".trash", ".smart-env", ".smart-connections"}
SELF_DIRS = {"KNOWLEDGE_SYSTEM"}
MARKDOWN_EXT = {".md", ".markdown"}
PLAIN_EXT = {".txt", ".org"}
CODE_EXT = {".py", ".pas", ".js", ".css", ".html", ".htm", ".json", ".sh", ".code-workspace", ".csv",
            ".yml", ".yaml", ".toml", ".ini", ".log", ".b"}
UNSUPPORTED = {".pwb": "PureWriter backup (7-zip archive) — нет распаковщика в stdlib"}

VERSIONS = {"ks": KS_VERSION, "fragmenter": FRAGMENTER_VERSION, "classifier": CLASSIFIER_VERSION,
            "grammar": GRAMMAR_VERSION, "parser": PARSER_VERSION, "schema": SCHEMA_VERSION}


@dataclass
class FragmentAnalysis:
    classification: Classification
    candidates: list = field(default_factory=list)  # dict-строки кандидатов (см. semantics)


class Analyzer(Protocol):
    def analyze(self, fragment: Fragment, doc_context: dict) -> FragmentAnalysis: ...


class StructuralAnalyzer:
    """Только классификация (без грамматики). Языковой анализатор — ``semantics.analyzer``."""
    version = "structural-1"

    def analyze(self, fragment: Fragment, doc_context: dict) -> FragmentAnalysis:
        return FragmentAnalysis(classify(fragment))


def _gitignore_patterns(vault: Path) -> list[str]:
    gi = vault / ".gitignore"
    if not gi.exists():
        return []
    out = []
    for line in gi.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and not line.startswith("!"):
            out.append(line.rstrip("/"))
    return out


def _ignored(rel: str, patterns: list[str]) -> bool:
    parts = rel.split("/")
    for pat in patterns:
        if "/" in pat:
            if fnmatch.fnmatch(rel, pat) or fnmatch.fnmatch(rel, pat + "/*"):
                return True
        elif any(fnmatch.fnmatch(p, pat) for p in parts):
            return True
    return False


def walk_vault(vault: Path, *, skip_hidden: bool = False,
               onerror: Callable | None = None) -> Iterable[tuple[str, Path]]:
    """Vault paths in stable order; hidden metadata can be pruned on Android."""
    for dirpath, dirnames, filenames in os.walk(vault, onerror=onerror):
        rel_dir = os.path.relpath(dirpath, vault)
        dirnames[:] = sorted(d for d in dirnames
                             if not (rel_dir == "." and (d == ".git" or d in SELF_DIRS))
                             and (not skip_hidden or not d.startswith(".")))
        for name in sorted(filenames):
            if skip_hidden and name.startswith("."):
                continue
            full = Path(dirpath) / name
            rel = nfc(os.path.relpath(full, vault).replace(os.sep, "/"))
            yield rel, full


def _flags(rel: str, text: str | None) -> list[str]:
    flags = []
    if ".sync-conflict-" in rel:
        flags.append("sync_conflict")
    if rel.startswith("templates/"):
        flags.append("template")
    if not unicodedata.is_normalized("NFC", rel):
        flags.append("non_nfc_name")
    if text is not None and ("Due to technical issues" in text or "/GENERATED/" in "/" + rel
                             or "by Grok" in rel):
        flags.append("ai_generated_hint")
    return flags


@dataclass
class ImportResult:
    manifest: dict
    coverage_failures: list


def import_vault(ws: Workspace, vault: Path, clock: Clock, analyzer: Analyzer | None = None,
                 progress: Callable[[str], None] | None = None, force: bool = False,
                 include: set | None = None, *,
                 only_extensions: set[str] | None = None, skip_hidden: bool = False,
                 max_bytes: int | None = None, reconcile_missing: bool = True,
                 cancelled: Callable[[], bool] | None = None,
                 max_files: int | None = None) -> ImportResult:
    """Incremental read-only source import into a disposable private index.

    Android uses Markdown-only/hidden-file/size filters and never reconciles
    missing paths: an interrupted, limited or inaccessible walk must not imply
    that previously indexed source notes have been deleted.
    """
    analyzer = analyzer or StructuralAnalyzer()
    versions = dict(VERSIONS, analyzer=getattr(analyzer, "version", "custom"),
                    relations=RELATIONS_VERSION)
    versions_key = canonical_json(versions)
    vault = vault.resolve()
    patterns = _gitignore_patterns(vault)
    con = connect(ws.db_path)
    existing = {row[0]: (row[1], row[2]) for row in con.execute("SELECT path, sha256, versions FROM documents")}
    entries: list[dict] = []
    seen_paths: set[str] = set()
    coverage_failures: list = []
    ordinal = 0
    con.execute("BEGIN")
    truncated = False
    considered = 0
    unreadable_directories = []
    for rel, full in walk_vault(vault, skip_hidden=skip_hidden,
                                onerror=unreadable_directories.append):
        if include is not None and rel not in include:
            continue
        if only_extensions is not None and full.suffix.lower() not in only_extensions:
            continue
        seen_paths.add(rel)
        parts = rel.split("/")
        if skip_hidden and any(part.startswith(".") for part in parts):
            continue
        if max_files is not None and considered >= max_files:
            truncated = True
            break
        considered += 1
        if cancelled is not None and cancelled():
            con.rollback()
            con.close()
            raise InterruptedError("Vault import cancelled")
        if progress is not None:
            try:
                progress(rel)
            except BaseException:
                con.rollback()
                con.close()
                raise
        try:
            # A symlink must never allow the Android importer to read outside
            # the notebook explicitly selected by the user.
            if not full.resolve().is_relative_to(vault):
                entries.append({"path": rel, "status": "SKIPPED_OUTSIDE_VAULT",
                                "kind": "excluded", "sha256": None})
                continue
            size = full.stat().st_size
        except (OSError, RuntimeError):
            entries.append({"path": rel, "status": "SKIPPED_UNREADABLE",
                            "kind": "excluded", "sha256": None})
            continue
        entry: dict = {"path": rel, "size": size}
        status, kind = None, None
        if any(p in SERVICE_DIRS for p in parts[:-1]) or parts[-1] in SERVICE_DIRS:
            status, kind = "EXCLUDED_SERVICE", "service"
        elif _ignored(rel, patterns):
            status, kind = "EXCLUDED_IGNORED", "ignored"  # содержимое не читается (секреты)
        if status:
            entry.update(status=status, kind=kind, sha256=None)
            con.execute("DELETE FROM candidates WHERE origin IN (SELECT id FROM fragments WHERE doc=?)", (rel,))
            con.execute("DELETE FROM candidate_atoms WHERE doc=?", (rel,))
            con.execute("DELETE FROM fragments WHERE doc=?", (rel,))
            con.execute("INSERT OR REPLACE INTO documents(path, sha256, size, status, kind, flags, fragment_count, versions)"
                        " VALUES(?,?,?,?,?,?,0,?)", (rel, None, size, status, kind, "[]", versions_key))
            entries.append(entry)
            continue
        if max_bytes is not None and size > max_bytes:
            entry.update(status="SKIPPED_SIZE_LIMIT", kind="markdown", sha256=None)
            # Never present interpretations from an earlier, smaller version.
            con.execute("DELETE FROM candidates WHERE origin IN (SELECT id FROM fragments WHERE doc=?)", (rel,))
            con.execute("DELETE FROM candidate_atoms WHERE doc=?", (rel,))
            con.execute("DELETE FROM fragments WHERE doc=?", (rel,))
            con.execute("INSERT OR REPLACE INTO documents(path, sha256, size, status, kind, flags, fragment_count, versions)"
                        " VALUES(?,?,?,?,?,?,0,?)", (rel, None, size, entry["status"], "markdown", "[]", versions_key))
            entries.append(entry)
            continue
        try:
            data = full.read_bytes()
        except OSError:
            entry.update(status="SKIPPED_UNREADABLE", kind="excluded", sha256=None)
            entries.append(entry)
            continue
        sha = file_hash(data)
        ext = Path(rel).suffix.lower()
        entry["sha256"] = sha
        if ext in UNSUPPORTED:
            entry.update(status="SKIPPED_UNSUPPORTED_FORMAT", kind="binary", reason=UNSUPPORTED[ext])
            con.execute("INSERT OR REPLACE INTO documents(path, sha256, size, status, kind, flags, fragment_count, versions, error)"
                        " VALUES(?,?,?,?,?,?,0,?,?)", (rel, sha, size, entry["status"], "binary", "[]", versions_key,
                                                      UNSUPPORTED[ext]))
            entries.append(entry)
            continue
        if ext in MARKDOWN_EXT:
            kind, parser = "markdown", parse_document
        elif ext in PLAIN_EXT:
            kind, parser = "text", parse_plain_text
        elif ext in CODE_EXT:
            kind, parser = "code", parse_code_file
        else:
            kind, parser = "binary", None
        text = None
        if parser is not None:
            try:
                text = data.decode("utf-8")
            except UnicodeDecodeError:
                kind, parser = "binary", None
        flags = _flags(rel, text)
        entry.update(kind=kind, flags=flags)
        if parser is None:
            entry.update(status="REGISTERED_BINARY")
            con.execute("DELETE FROM candidates WHERE origin IN (SELECT id FROM fragments WHERE doc=?)", (rel,))
            con.execute("DELETE FROM candidate_atoms WHERE doc=?", (rel,))
            con.execute("DELETE FROM fragments WHERE doc=?", (rel,))
            con.execute("INSERT OR REPLACE INTO documents(path, sha256, size, status, kind, flags, fragment_count, versions)"
                        " VALUES(?,?,?,?,?,?,0,?)", (rel, sha, size, "REGISTERED_BINARY", kind, json.dumps(flags), versions_key))
            entries.append(entry)
            continue
        if not force and existing.get(rel) == (sha, versions_key):
            stats = _doc_stats(con, rel)  # неизменён: переиспользуем фрагменты из индекса
            entry.update(status="PARSED", reused=True, **stats)
            entries.append(entry)
            continue
        parsed = parser(rel, text)
        cov = check_coverage(text, parsed.fragments)
        errors = []
        if not cov.ok:
            errors.append({"kind": ErrorKind.IMPORT_ERROR.value, "message": "coverage invariant violated",
                           "lost": [list(x) for x in cov.lost[:5]], "overlaps": len(cov.overlaps)})
            coverage_failures.append(rel)
        con.execute("DELETE FROM candidates WHERE origin IN (SELECT id FROM fragments WHERE doc=?)", (rel,))
        con.execute("DELETE FROM candidate_atoms WHERE doc=?", (rel,))
        con.execute("DELETE FROM fragments WHERE doc=?", (rel,))
        doc_context = {"path": rel, "frontmatter": parsed.frontmatter, "flags": flags}
        for frag in parsed.fragments:
            analysis = analyzer.analyze(frag, doc_context)
            c = analysis.classification
            ordinal += 1
            con.execute(
                "INSERT INTO fragments VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (frag.id, rel, frag.locator, frag.block_index, frag.block_type, frag.unit_index, frag.unit_role,
                 frag.span[0], frag.span[1], frag.text, frag.plain, frag.depth,
                 canonical_json(list(frag.heading_path)), canonical_json(frag.inline.to_dict()),
                 canonical_json([[k, list(v) if isinstance(v, tuple) else v] for k, v in frag.meta]),
                 frag.text_hash, c.kind.value, c.formalization.value, canonical_json(list(c.cues)), frag.span[0]))
            for cand in analysis.candidates:
                con.execute("INSERT OR REPLACE INTO candidates VALUES(?,?,?,?,?,?,?,?,?)",
                            (cand["id"], frag.id, cand["reading_id"], canonical_json(cand["nodes"]),
                             cand["paraphrase"], canonical_json(cand["diagnostics"]),
                             canonical_json(cand.get("ambiguity") or {}), cand["input_hash"], frag.span[0]))
                for row in projected_atoms(cand, rel, frag.id, frag.locator):
                    con.execute("INSERT OR IGNORE INTO candidate_atoms VALUES(?,?,?,?,?,?,?)", row)
        con.execute("INSERT OR REPLACE INTO documents(path, sha256, size, status, kind, flags, fragment_count, versions, error)"
                    " VALUES(?,?,?,?,?,?,?,?,?)", (rel, sha, size, "PARSED", kind, json.dumps(flags),
                                                   len(parsed.fragments), versions_key,
                                                   json.dumps(errors, ensure_ascii=False) if errors else None))
        stats = _doc_stats(con, rel)
        entry.update(status="PARSED", reused=False, **stats)
        entries.append(entry)
    if reconcile_missing and not truncated and include is None \
            and only_extensions is None and not skip_hidden and not unreadable_directories:
        for gone in sorted(set(existing) - seen_paths):  # complete vault walk only
            con.execute("DELETE FROM candidates WHERE origin IN (SELECT id FROM fragments WHERE doc=?)", (gone,))
            con.execute("DELETE FROM candidate_atoms WHERE doc=?", (gone,))
            con.execute("DELETE FROM fragments WHERE doc=?", (gone,))
            con.execute("DELETE FROM documents WHERE path=?", (gone,))
    if cancelled is not None and cancelled():
        con.rollback()
        con.close()
        raise InterruptedError("Vault import cancelled")
    con.execute("COMMIT")
    manifest = build_manifest(entries, versions, clock.now())
    manifest["truncated"] = truncated
    manifest["unreadable_directories"] = len(unreadable_directories)
    con.close()
    return ImportResult(manifest, coverage_failures)


def _doc_stats(con, rel: str) -> dict:
    kinds = dict(con.execute("SELECT kind, COUNT(*) FROM fragments WHERE doc=? GROUP BY kind ORDER BY kind", (rel,)))
    forms = dict(con.execute("SELECT formalization, COUNT(*) FROM fragments WHERE doc=? GROUP BY formalization"
                             " ORDER BY formalization", (rel,)))
    cands = con.execute("SELECT COUNT(*) FROM candidates c JOIN fragments f ON c.origin=f.id WHERE f.doc=?",
                        (rel,)).fetchone()[0]
    row = con.execute("SELECT error FROM documents WHERE path=?", (rel,)).fetchone()
    errors = json.loads(row[0]) if row and row[0] else []
    return {"fragments": sum(kinds.values()), "by_kind": kinds, "by_formalization": forms,
            "candidates": cands, "unresolved": kinds.get(FragmentKind.UNRESOLVED.value, 0),
            "ambiguous": forms.get(Formalization.AMBIGUOUS.value, 0), "errors": errors}


def build_manifest(entries: list[dict], versions: dict, timestamp: str) -> dict:
    files = []
    totals: dict = {"files": len(entries), "by_status": {}, "fragments": 0, "by_kind": {},
                    "by_formalization": {}, "candidates": 0, "unresolved": 0, "ambiguous": 0,
                    "documents_with_errors": 0}
    for e in entries:
        e = {k: v for k, v in e.items() if k != "reused"}
        files.append(e)
        totals["by_status"][e["status"]] = totals["by_status"].get(e["status"], 0) + 1
        totals["fragments"] += e.get("fragments", 0)
        for key in ("by_kind", "by_formalization"):
            for k, v in e.get(key, {}).items():
                totals[key][k] = totals[key].get(k, 0) + v
        totals["candidates"] += e.get("candidates", 0)
        totals["unresolved"] += e.get("unresolved", 0)
        totals["ambiguous"] += e.get("ambiguous", 0)
        if e.get("errors"):
            totals["documents_with_errors"] += 1
    for key in ("by_status", "by_kind", "by_formalization"):
        totals[key] = dict(sorted(totals[key].items()))
    body = {"versions": versions, "totals": totals, "files": files}
    return {"import_id": "IMP:" + digest(canonical_json(body)), "generated_at": timestamp, **body}
