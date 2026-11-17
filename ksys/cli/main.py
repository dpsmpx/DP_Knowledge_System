"""knowledge — интерфейс командной строки. Только разбор аргументов и печать.

Вся логика — в слоях ядра. Каждая команда детерминирована: одинаковые vault и
журнал → одинаковый вывод (время — только из ``KS_NOW``/часов при записи решений).
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

from ..core.clock import clock_from_env
from ..core.errors import ErrorKind, KSError
from ..ir.ksf import parse_ksf
from ..semantics.normalize import entity_key, ksf_symbol
from ..store.workspace import Workspace

DEFAULT_HOME = Path(__file__).resolve().parents[2]


def out(args, payload, text: str | None = None) -> None:
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True, default=str))
    else:
        print(text if text is not None else json.dumps(payload, ensure_ascii=False, indent=2, default=str))


def workspace(args) -> Workspace:
    return Workspace(Path(args.home).resolve())


def vault_path(args, ws: Workspace) -> Path:
    if getattr(args, "vault", None):
        return Path(args.vault).resolve()
    v = ws.config().get("vault")
    return (ws.root / v).resolve() if v else ws.root.parent


def open_kb(args, at: str | None = None):
    from ..store.kb import KnowledgeBase
    ws = workspace(args)
    if not ws.journal_path.exists():
        ws.init()
    return KnowledgeBase(ws, clock_from_env(), at=at or getattr(args, "at_commit", None))


def load_manifest(ws: Workspace) -> dict | None:
    p = ws.reports_dir / "IMPORT_MANIFEST.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


# ---------------------------------------------------------------------------- commands
def cmd_init(args) -> int:
    ws = workspace(args)
    ws.init(args.vault_rel)
    out(args, {"home": str(ws.root), "journal": str(ws.journal_path), "config": ws.config()},
        f"initialized {ws.root}\n  journal: {ws.journal_path}\n  index:   {ws.db_path}")
    return 0


def _do_import(args, force: bool) -> dict:
    from ..importer.vault import StructuralAnalyzer, import_vault
    from ..importer.language_analyzer import LanguageAnalyzer
    ws = workspace(args)
    if not ws.journal_path.exists():
        ws.init()
    kb = open_kb(args)
    analyzer = StructuralAnalyzer() if args.structural_only else LanguageAnalyzer(kb.lexicon, kb.state)
    res = import_vault(ws, vault_path(args, ws), clock_from_env(), analyzer, force=force,
                       progress=(lambda p: print("  parse", p, file=sys.stderr)) if args.verbose else None)
    ws.reports_dir.mkdir(parents=True, exist_ok=True)
    (ws.reports_dir / "IMPORT_MANIFEST.json").write_text(
        json.dumps(res.manifest, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    kb.persist()
    if not args.no_reports:
        from ..export.reports import Reporter
        Reporter(kb, res.manifest).write_all(ws.reports_dir)
    return res.manifest


def cmd_import(args) -> int:
    m = _do_import(args, args.force)
    t = m["totals"]
    out(args, {"import_id": m["import_id"], "totals": t},
        f"import_id {m['import_id']}\nfiles {t['files']} {t['by_status']}\nfragments {t['fragments']}\n"
        f"formalization {t['by_formalization']}\ncandidates {t['candidates']} (ambiguous fragments {t['ambiguous']})\n"
        f"documents with errors {t['documents_with_errors']}")
    return 1 if t["documents_with_errors"] else 0


def cmd_rebuild(args) -> int:
    ws = workspace(args)
    if ws.db_path.parent.exists():
        shutil.rmtree(ws.db_path.parent)
    args.force = True
    m = _do_import(args, True)
    out(args, {"import_id": m["import_id"]}, f"rebuilt index from vault + journal; import_id {m['import_id']}")
    return 0


def cmd_scan(args) -> int:
    from collections import Counter
    from ..importer.vault import SERVICE_DIRS, UNSUPPORTED, _gitignore_patterns, _ignored, walk_vault
    ws = workspace(args)
    v = vault_path(args, ws)
    pats = _gitignore_patterns(v)
    c = Counter()
    for rel, _full in walk_vault(v):
        parts = rel.split("/")
        if any(p in SERVICE_DIRS for p in parts[:-1]):
            c["service"] += 1
        elif _ignored(rel, pats):
            c["ignored(secret)"] += 1
        elif Path(rel).suffix.lower() in UNSUPPORTED:
            c["unsupported"] += 1
        else:
            c[Path(rel).suffix.lower() or "(none)"] += 1
    out(args, dict(sorted(c.items())), "\n".join(f"{k:20} {v}" for k, v in sorted(c.items())))
    return 0


def cmd_parse(args) -> int:
    from ..importer.classify import classify
    from ..source.coverage import check_coverage
    from ..source.markdown import parse_document
    p = Path(args.file)
    text = p.read_text(encoding="utf-8")
    d = parse_document(args.file, text)
    cov = check_coverage(text, d.fragments)
    rows = []
    for f in d.fragments:
        c = classify(f)
        rows.append({"id": f.id, "locator": f.locator, "block": f.block_type, "role": f.unit_role, "kind": c.kind.value,
                     "formalization": c.formalization.value, "cues": list(c.cues), "text": f.text})
    out(args, {"coverage_ok": cov.ok, "fragments": rows},
        f"coverage_ok={cov.ok} chars={cov.chars_covered}/{cov.chars_total}\n" +
        "\n".join(f"{r['locator']:40} {r['block']:10} {r['kind']:11} {r['formalization']:13} {r['text'][:70]!r}"
                  for r in rows))
    return 0 if cov.ok else 1


def cmd_formalize(args) -> int:
    kb = open_kb(args)
    text = args.text
    origin = None
    if args.fragment:
        from ..store.db import connect
        row = connect(kb.ws.db_path).execute("SELECT id, plain FROM fragments WHERE id=? OR locator=?",
                                             (args.fragment, args.fragment)).fetchone()
        if not row:
            raise KSError(ErrorKind.QUERY_ERROR, f"fragment {args.fragment} not found")
        origin, text = row
    from ..language.tokenize import tokenize
    from ..parser.cnl import CNLParser
    toks = tokenize(text)
    parse = CNLParser(kb.lexicon).parse(text)
    pv = kb.preview(text, origin, mode="import" if origin else "assert")
    payload = {"source": text, "tokens": [f"{t.kind}:{t.text}" for t in toks], "parse_status": parse.status,
               "parse_reason": parse.reason, "readings": [r.key_repr() for r in parse.readings], **pv.to_dict()}
    lines = [f"SOURCE:      {text}", f"TOKENS:      {' '.join(payload['tokens'])}",
             f"PARSE:       {parse.status} {parse.reason}"]
    for k, r in enumerate(parse.readings):
        lines.append(f"  LF[{k}]:     {r.key_repr()}")
    lines.append(f"STATUS:      {pv.formalization.status}")
    for o in pv.options:
        lines += [f"READING {o['reading_id']} {o['labels']}", f"  FORMAL:    {' '.join(o['ksf']) or '—'}",
                  f"  CANONICAL: {o['paraphrase']}"]
        for d in o["diagnostics"]:
            lines.append(f"  CHECK:     {d['kind']} {d['message']}")
        for c in o["conflicts"]:
            lines.append(f"  CONFLICT:  {c['node']} vs {c['conflicts_with']} ({c['layer']}) {c['ksf']}")
    out(args, payload, "\n".join(lines))
    return 0


def cmd_validate(args) -> int:
    kb = open_kb(args)
    from ..query.answers import Answerer
    chain = [d.to_dict() for d in kb.journal.verify()]
    schema = Answerer(kb).schema_issues()
    replay = [d.to_dict() for d in kb.state.diagnostics]
    errors = [d for d in chain + schema + replay if d["severity"] == "error"]
    out(args, {"journal": chain, "schema": schema, "replay": replay, "errors": len(errors)},
        f"journal chain: {'OK' if not chain else chain}\nreplay: {'OK' if not replay else replay}\n"
        f"schema/constraints: {len(schema)} diagnostics, {len([d for d in schema if d['severity'] == 'error'])} errors\n" +
        "\n".join(f"  {d['severity']:7} {d['kind']}: {d['message']}" for d in schema))
    return 1 if errors else 0


def cmd_stats(args) -> int:
    kb = open_kb(args)
    from ..export.reports import Reporter
    print(Reporter(kb, load_manifest(kb.ws)).knowledge_stats())
    return 0


def _dialogue(args, text: str) -> int:
    from ..dialogue.session import Dialogue
    kb = open_kb(args)
    r = Dialogue(kb).handle(text, yes=getattr(args, "yes", False), at=getattr(args, "at", None),
                           reading=getattr(args, "reading", None))
    if r.committed:
        kb.persist()
    out(args, r.to_dict(), r.render())
    return 0


def cmd_query(args) -> int:
    return _dialogue(args, args.question)


def cmd_assert(args) -> int:
    if args.as_role or args.confidence:
        kb = open_kb(args)
        pv = kb.preview(args.statement)
        if not args.yes:
            out(args, pv.to_dict())
            return 0
        res = kb.accept_text(args.statement, reading=args.reading, role=args.as_role, confidence=args.confidence)
        kb.persist()
        out(args, {"committed": [e.id for e in res.entries]},
            "COMMITTED " + ", ".join(e.data["node"]["id"] for e in res.entries))
        return 0
    return _dialogue(args, "Добавь факт: " + args.statement)


def cmd_hypothesis(args) -> int:
    return _dialogue(args, "Проверь гипотезу, что " + args.statement)


def cmd_explain(args) -> int:
    return _dialogue(args, f"Почему {args.id}?")


def cmd_trace(args) -> int:
    return _dialogue(args, f"Откуда взялся {args.id}?")


def cmd_retract(args) -> int:
    return _dialogue(args, f"Удали факт {args.id}")


def cmd_revise(args) -> int:
    kb = open_kb(args)
    pv = kb.preview(args.statement)
    if pv.formalization.status != "FORMALIZED":
        raise KSError(ErrorKind.PARSE_ERROR, f"не формализовано: {pv.formalization.reason}")
    new = pv.formalization.selected.nodes[0]
    target = kb.resolve_id(args.id)
    if not args.yes:
        d = kb.what_if_retract(target)
        out(args, {"old": target, "new": new.id, "would_invalidate": sorted(d.removed)},
            f"PREVIEW {target} → {new.ksf()}\nwould invalidate: {sorted(d.removed)}")
        return 0
    res = kb.revise(target, new, reason=args.reason)
    kb.persist()
    out(args, {"superseded": target, "by": new.id, "invalidated": sorted(res.auth.removed), "notes": res.notes},
        f"{target} SUPERSEDED_BY {new.id}\ninvalidated: {sorted(res.auth.removed)}\nrederived: {sorted(res.auth.rederived)}"
        + ("\n" + "\n".join(res.notes) if res.notes else ""))
    return 0


def cmd_contradictions(args) -> int:
    return _dialogue(args, "Найди противоречия")


def cmd_unknowns(args) -> int:
    return _dialogue(args, "Какие неизвестные остаются?")


def cmd_whatif(args) -> int:
    return _dialogue(args, f"Что будет, если удалить {args.id}?")


def cmd_define(args) -> int:
    return _dialogue(args, f"Определи понятие {args.term}")


def cmd_export(args) -> int:
    from ..export.canonical import export_jsonl, export_obsidian, export_turtle
    kb = open_kb(args)
    target = Path(args.out) if args.out else kb.ws.root / "data" / "formal" / "export" / args.format
    fn = {"jsonl": export_jsonl, "ttl": export_turtle, "obsidian": export_obsidian}[args.format]
    m = fn(kb, target)
    out(args, {k: v for k, v in m.items() if k != "basis"},
        f"exported {args.format} → {target}\nknowledge_commit {m['knowledge_commit']} basis_hash {m['basis_hash']}")
    return 0


def cmd_stale(args) -> int:
    from ..export.canonical import stale
    kb = open_kb(args)
    s = stale(kb, Path(args.manifest))
    out(args, s, ("STALE" if s["stale"] else "UP-TO-DATE") + f" (exported at {s['exported_at_commit']}, "
                 f"now {s['current_commit']}); removed {len(s['removed_basis'])}, added {len(s['added_basis'])}")
    return 1 if s["stale"] else 0


def cmd_reports(args) -> int:
    from ..export.reports import Reporter
    kb = open_kb(args)
    kb.persist()
    files = Reporter(kb, load_manifest(kb.ws)).write_all(kb.ws.reports_dir)
    out(args, files, "\n".join(str(kb.ws.reports_dir / f) for f in files))
    return 0


def cmd_history(args) -> int:
    kb = open_kb(args)
    rows = [{"seq": e.seq, "id": e.id, "ts": e.ts, "actor": e.actor, "op": e.op,
             "target": e.data.get("node", {}).get("id") or e.data.get("target") or e.data.get("origin") or ""}
            for e in kb.journal.entries()]
    out(args, rows, "\n".join(f"#{r['seq']:<4} {r['id']} {r['ts']} {r['op']:8} {r['target']}" for r in rows))
    return 0


def cmd_diff(args) -> int:
    from ..store.kb import KnowledgeBase
    ws = workspace(args)
    a = KnowledgeBase(ws, clock_from_env(), at=args.old)
    b = KnowledgeBase(ws, clock_from_env(), at=args.new)
    ra = {r.id for r in a.state.authoritative()}
    rb = {r.id for r in b.state.authoritative()}
    da, db_ = set(a.auth.items), set(b.auth.items)
    res = {"records_added": sorted(rb - ra), "records_removed": sorted(ra - rb),
           "derived_added": sorted(b.auth.items[x].key() for x in db_ - da),
           "derived_removed": sorted(a.auth.items[x].key() for x in da - db_)}
    out(args, res)
    return 0


def cmd_review(args) -> int:
    from ..store.db import connect
    kb = open_kb(args)
    st = kb.state
    if args.action == "list":
        con = connect(kb.ws.db_path)
        q = ("SELECT c.id, f.locator, f.kind, f.formalization, c.paraphrase FROM candidates c JOIN fragments f "
             "ON f.id=c.origin WHERE 1=1")
        params: list = []
        if args.kind:
            q += " AND f.kind=?"
            params.append(args.kind)
        if args.form:
            q += " AND f.formalization=?"
            params.append(args.form)
        if args.doc:
            q += " AND f.doc LIKE ?"
            params.append(args.doc + "%")
        q += " ORDER BY f.doc, f.ordinal, c.id"
        rows = [r for r in con.execute(q, params)
                if r[0] not in st.accepted_candidates and r[0] not in st.rejected_candidates and r[0] not in st.deferred]
        rows = rows[: args.limit]
        out(args, [dict(zip(("id", "locator", "kind", "formalization", "paraphrase"), r)) for r in rows],
            "\n".join(f"{r[0][:21]} {r[3]:9} {r[2]:10} {r[1]}\n    {r[4][:150]}" for r in rows) or "нет кандидатов")
        return 0
    if args.action == "show":
        c = kb.candidate(args.ids[0])
        p = c["provenance"]
        out(args, {"candidate": c["id"], "original": p["text"], "locator": p["locator"],
                   "formal": [n.ksf() for n in c["nodes"]], "canonical": c["paraphrase"]},
            f"CANDIDATE  {c['id']}\nSOURCE     {p['locator']}\nORIGINAL   {p['text']}\n"
            f"FORMAL     {' '.join(n.ksf() for n in c['nodes'])}\nCANONICAL  {c['paraphrase']}\n"
            "ACTIONS    accept [--as fact|claim|hypothesis] [--confidence …] [--select i,j] [--edit KSF] | "
            "reject | defer | merge")
        return 0
    if args.action == "accept":
        edited = parse_ksf(args.edit, ksf_symbol) if args.edit else None
        select = [int(x) for x in args.select.split(",")] if args.select else None
        res = kb.accept_candidate(args.ids[0], role=args.as_role, confidence=args.confidence, edited=edited,
                                  select=select, note=args.note)
        kb.persist()
        out(args, {"committed": [e.id for e in res.entries]},
            "ACCEPTED " + ", ".join(e.data["node"]["id"] for e in res.entries))
        return 0
    if args.action == "reject":
        e = kb.reject(candidates=args.ids, reason=args.note)
        out(args, {"entry": e.id}, f"REJECTED {', '.join(args.ids)} ({e.id})")
        return 0
    if args.action == "defer":
        e = kb.defer(args.ids, reason=args.note)
        out(args, {"entry": e.id}, f"MARKED UNKNOWN {', '.join(args.ids)} ({e.id})")
        return 0
    if args.action == "merge":
        nodes = parse_ksf(args.edit, ksf_symbol)
        res = kb.merge_candidates(args.ids, nodes, role=args.as_role, confidence=args.confidence, note=args.note)
        out(args, {"committed": [e.id for e in res.entries]}, "MERGED → " + ", ".join(n.id for n in nodes))
        return 0
    if args.action == "resolve":
        fragment, reading = args.ids
        e = kb.resolve(fragment, reading)
        out(args, {"entry": e.id}, f"RESOLVED {fragment} → {reading} ({e.id})")
        return 0
    raise SystemExit(f"unknown review action {args.action}")


def cmd_bind(args) -> int:
    kb = open_kb(args)
    e = kb.bind(args.origin, args.slot, entity_key(args.entity) if not args.raw else args.entity)
    out(args, {"entry": e.id}, f"BOUND {args.origin}:{args.slot} → {e.data['entity']} ({e.id})")
    return 0


def cmd_label(args) -> int:
    kb = open_kb(args)
    e = kb.label(ksf_symbol(args.key), args.label)
    out(args, {"entry": e.id}, f"LABEL {e.data['key']} = {args.label}")
    return 0


def cmd_alias(args) -> int:
    kb = open_kb(args)
    e = kb.alias(ksf_symbol(args.alias), ksf_symbol(args.canonical))
    out(args, {"entry": e.id}, f"ALIAS {e.data['alias']} → {e.data['canonical']}")
    return 0


def cmd_rules(args) -> int:
    kb = open_kb(args)
    res = kb.load_rules_file(Path(args.file), confidence=args.confidence)
    kb.persist()
    out(args, {"committed": [e.id for e in res.entries]}, f"loaded {len(res.entries)} rule(s) from {args.file}")
    return 0


def cmd_demo(args) -> int:
    from ..demo.scenarios import run_all
    text = run_all()
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    print(text)
    return 0


def cmd_chat(args) -> int:
    from ..dialogue.session import Dialogue
    kb = open_kb(args)
    d = Dialogue(kb)
    print("Knowledge System — диалог. Пустая строка — выход. Префикс «!» — подтвердить запись (yes).")
    while True:
        try:
            line = input("> ").strip()
        except EOFError:
            break
        if not line:
            break
        yes = line.startswith("!")
        r = d.handle(line.lstrip("!"), yes=yes)
        if r.committed:
            kb.persist()
        print(r.render())
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="knowledge", description="Детерминированная формальная персональная база знаний")
    p.add_argument("--home", default=os.environ.get("KS_HOME", str(DEFAULT_HOME)), help="рабочее пространство")
    p.add_argument("--json", action="store_true", help="машиночитаемый вывод")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("init", help="создать рабочее пространство")
    s.add_argument("--vault", dest="vault_rel", help="путь к vault относительно home (по умолчанию ..)")
    s.set_defaults(fn=cmd_init)
    for name, fn, h in (("import", cmd_import, "импорт vault (идемпотентно)"),
                        ("rebuild", cmd_rebuild, "пересобрать индекс из vault + журнала")):
        s = sub.add_parser(name, help=h)
        s.add_argument("vault", nargs="?")
        s.add_argument("--force", action="store_true")
        s.add_argument("--structural-only", action="store_true", help="без грамматики (только классификация)")
        s.add_argument("--no-reports", action="store_true")
        s.add_argument("--verbose", action="store_true")
        s.set_defaults(fn=fn)
    s = sub.add_parser("scan", help="инвентаризация файлов vault")
    s.add_argument("vault", nargs="?")
    s.set_defaults(fn=cmd_scan)
    s = sub.add_parser("parse", help="разбор одного Markdown-файла на фрагменты")
    s.add_argument("file")
    s.set_defaults(fn=cmd_parse)
    s = sub.add_parser("formalize", help="конвейер SOURCE → TOKENS → LF → IR → PARAPHRASE")
    s.add_argument("text", nargs="?", default="")
    s.add_argument("--fragment")
    s.set_defaults(fn=cmd_formalize)
    sub.add_parser("validate", help="журнал, схема, ограничения").set_defaults(fn=cmd_validate)
    sub.add_parser("stats", help="статистика знания").set_defaults(fn=cmd_stats)
    s = sub.add_parser("query", help="вопрос на естественном языке")
    s.add_argument("question")
    s.add_argument("--at", help="оценить на дату YYYY-MM-DD")
    s.add_argument("--yes", action="store_true")
    s.add_argument("--reading")
    s.set_defaults(fn=cmd_query)
    s = sub.add_parser("assert", help="добавить знание (предпросмотр; --yes — записать)")
    s.add_argument("statement")
    s.add_argument("--yes", action="store_true")
    s.add_argument("--reading")
    s.add_argument("--as", dest="as_role", choices=["fact", "claim", "hypothesis"])
    s.add_argument("--confidence", choices=["high", "medium", "low"])
    s.set_defaults(fn=cmd_assert)
    s = sub.add_parser("hypothesis", help="проверить гипотезу (--yes — сохранить)")
    s.add_argument("statement")
    s.add_argument("--yes", action="store_true")
    s.add_argument("--reading")
    s.set_defaults(fn=cmd_hypothesis)
    for name, fn, h in (("explain", cmd_explain, "дерево доказательства"), ("trace", cmd_trace, "трассировка до источников"),
                        ("what-if", cmd_whatif, "что будет при удалении (без записи)")):
        s = sub.add_parser(name, help=h)
        s.add_argument("id")
        s.set_defaults(fn=fn)
    s = sub.add_parser("retract", help="отозвать знание (--yes)")
    s.add_argument("id")
    s.add_argument("--yes", action="store_true")
    s.set_defaults(fn=cmd_retract)
    s = sub.add_parser("revise", help="заменить знание с сохранением истории (--yes)")
    s.add_argument("id")
    s.add_argument("statement")
    s.add_argument("--reason")
    s.add_argument("--yes", action="store_true")
    s.set_defaults(fn=cmd_revise)
    sub.add_parser("contradictions", help="противоречия").set_defaults(fn=cmd_contradictions)
    sub.add_parser("unknowns", help="что остаётся неизвестным").set_defaults(fn=cmd_unknowns)
    s = sub.add_parser("define", help="определение понятия")
    s.add_argument("term")
    s.set_defaults(fn=cmd_define)
    s = sub.add_parser("export", help="канонический экспорт")
    s.add_argument("--format", choices=["jsonl", "ttl", "obsidian"], default="jsonl")
    s.add_argument("--out")
    s.set_defaults(fn=cmd_export)
    s = sub.add_parser("stale", help="устарел ли экспорт (по манифесту)")
    s.add_argument("manifest")
    s.set_defaults(fn=cmd_stale)
    sub.add_parser("reports", help="перегенерировать отчёты").set_defaults(fn=cmd_reports)
    sub.add_parser("history", help="журнал решений (knowledge commits)").set_defaults(fn=cmd_history)
    s = sub.add_parser("diff", help="что изменилось между коммитами знания")
    s.add_argument("old")
    s.add_argument("new")
    s.set_defaults(fn=cmd_diff)
    s = sub.add_parser("review", help="ревью кандидатов импорта (human-in-the-loop)")
    s.add_argument("action", choices=["list", "show", "accept", "reject", "defer", "merge", "resolve"])
    s.add_argument("ids", nargs="*")
    s.add_argument("--kind")
    s.add_argument("--form", choices=["FULL", "PARTIAL", "AMBIGUOUS"])
    s.add_argument("--doc")
    s.add_argument("--limit", type=int, default=30)
    s.add_argument("--as", dest="as_role", choices=["fact", "claim", "hypothesis"])
    s.add_argument("--confidence", choices=["high", "medium", "low"])
    s.add_argument("--select")
    s.add_argument("--edit")
    s.add_argument("--note")
    s.set_defaults(fn=cmd_review)
    s = sub.add_parser("bind", help="разрешить ссылку: origin slot сущность")
    s.add_argument("origin")
    s.add_argument("slot")
    s.add_argument("entity")
    s.add_argument("--raw", action="store_true", help="сущность — уже ключ")
    s.set_defaults(fn=cmd_bind)
    s = sub.add_parser("label", help="ярлык понятия")
    s.add_argument("key")
    s.add_argument("label")
    s.set_defaults(fn=cmd_label)
    s = sub.add_parser("alias", help="склеить ключ с каноническим")
    s.add_argument("alias")
    s.add_argument("canonical")
    s.set_defaults(fn=cmd_alias)
    s = sub.add_parser("rules", help="загрузить библиотеку правил (KSF) явным решением")
    s.add_argument("action", choices=["load"])
    s.add_argument("file")
    s.add_argument("--confidence", choices=["high", "medium", "low"])
    s.set_defaults(fn=cmd_rules)
    s = sub.add_parser("demo", help="детерминированные демо-сценарии 1–10")
    s.add_argument("--out")
    s.set_defaults(fn=cmd_demo)
    sub.add_parser("chat", help="интерактивный диалог").set_defaults(fn=cmd_chat)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.fn(args)
    except KSError as e:
        print(json.dumps(e.diagnostic().to_dict(), ensure_ascii=False) if args.json else f"ERROR {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
