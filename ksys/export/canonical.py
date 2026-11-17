"""Канонический экспорт знания для потребителей (README, сайт, другие системы).

* JSONL — записи (принятые человеком), выводы с обоснованиями, манифест;
* RDF 1.2 Turtle — положительные бинарные факты как тройки с аннотациями
  ``{| … |}`` (provenance в духе PROV-O); отрицательные и n-арные — как triple terms
  ``<<( … )>>`` / узлы-реификации, чтобы не утверждать в RDF то, что ложно;
* Obsidian — страницы понятий со ссылками [[…]] на исходные заметки.

Манифест фиксирует ``knowledge_commit`` (голова журнала) и ``basis_hash`` —
хеш множества утверждений, на которых построен экспорт. ``stale`` сравнивает
манифест с текущим состоянием: изменились основания → артефакт устарел.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from .. import IR_VERSION, KS_VERSION
from ..core.ids import canonical_json, digest
from ..ir.terms import DATE, NUM, STR, SYM, Const


def basis(kb) -> list:
    return sorted(kb.auth.items)


def manifest(kb, kind: str, files: list) -> dict:
    b = basis(kb)
    return {"kind": kind, "ks_version": KS_VERSION, "ir_version": IR_VERSION,
            "knowledge_commit": kb.state.head, "basis": b, "basis_hash": digest(canonical_json(b)),
            "records": len(kb.state.records), "authoritative": len(kb.state.authoritative()),
            "derived": sum(1 for a in kb.auth.items if not kb.auth.is_base(a)), "files": files}


def export_jsonl(kb, out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    with (out / "records.jsonl").open("w", encoding="utf-8") as fh:
        for rid in sorted(kb.state.records):
            fh.write(canonical_json(kb.state.records[rid].to_dict()) + "\n")
    with (out / "derived.jsonl").open("w", encoding="utf-8") as fh:
        for aid in sorted(kb.auth.items):
            if kb.auth.is_base(aid):
                continue
            a = kb.auth.items[aid]
            fh.write(canonical_json({"id": "D:" + aid[2:], "assertion": a.payload(), "ksf": a.key(),
                                     "status": "DERIVED",
                                     "justifications": [{"id": j.id, "rule": j.rule, "premises": list(j.premises)}
                                                        for j in kb.auth.justifications(aid)]}) + "\n")
    m = manifest(kb, "jsonl", ["records.jsonl", "derived.jsonl"])
    (out / "manifest.json").write_text(json.dumps(m, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return m


def _iri(value: str) -> str:
    return "ks:" + re.sub(r"[^\w-]", "_", value, flags=re.UNICODE)


def _lit(c: Const) -> str:
    if c.kind == SYM:
        return _iri(c.value)
    if c.kind == NUM:
        return f'"{c.value}"^^xsd:decimal'
    if c.kind == DATE:
        return f'"{c.value}"^^xsd:date'
    if c.kind == STR:
        return json.dumps(c.value, ensure_ascii=False)
    return f'"{c.value}"^^xsd:boolean'


def export_turtle(kb, out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    lines = ["@prefix ks: <urn:ks:> .", "@prefix ksp: <urn:ks:pred:> .",
             "@prefix prov: <http://www.w3.org/ns/prov#> .", "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .",
             f"# knowledge_commit {kb.state.head}", ""]
    for aid in sorted(kb.auth.items):
        a = kb.auth.items[aid]
        base = kb.auth.is_base(aid)
        ann = [f'ks:id "{("F:" if base else "D:") + aid[2:]}"', f'ks:status "{"HUMAN_ACCEPTED" if base else "DERIVED"}"']
        if base:
            for src in sorted(kb.auth.base[aid]):
                rec = kb.state.records.get(src)
                if rec:
                    ann.append(f'ks:confidence "{rec.confidence.value}"')
                    for p in rec.provenance:
                        if p.get("journal_entry"):
                            ann.append(f"prov:wasGeneratedBy {_iri(p['journal_entry'])}")
                        if p.get("locator"):
                            ann.append(f"prov:hadPrimarySource {json.dumps(p['locator'], ensure_ascii=False)}")
        else:
            for j in kb.auth.justifications(aid):
                ann.append(f"prov:wasDerivedFrom {_iri(j.id)}")
        if a.interval.start:
            ann.append(f'ks:validFrom "{a.interval.start}"^^xsd:date')
        if a.interval.end:
            ann.append(f'ks:validTo "{a.interval.end}"^^xsd:date')
        if a.context:
            ann.append(f"ks:context {_iri(a.context)}")
        if a.modality.value != "asserted":
            ann.append(f'ks:modality "{a.modality.value}"')
        p = "ksp:" + re.sub(r"[^\w]", "_", a.atom.predicate)
        args = [_lit(x) for x in a.atom.args]
        if len(args) == 2 and not a.negated:
            lines.append(f"{args[0]} {p} {args[1]} {{| {' ; '.join(ann)} |}} .")
        elif len(args) == 2:
            lines.append(f"<<( {args[0]} {p} {args[1]} )>> ks:polarity \"negative\" ; {' ; '.join(ann)} .")
        else:
            node = f"_:{aid.replace(':', '_')}"
            lines.append(f"{node} ks:predicate {p} ; " + " ; ".join(f"ks:arg{i} {v}" for i, v in enumerate(args))
                         + f" ; ks:polarity \"{'negative' if a.negated else 'positive'}\" ; {' ; '.join(ann)} .")
    (out / "knowledge.ttl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    m = manifest(kb, "turtle", ["knowledge.ttl"])
    (out / "manifest.json").write_text(json.dumps(m, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return m


def export_obsidian(kb, out: Path) -> dict:
    """Страницы понятий: факты о понятии + обратные ссылки на заметки-источники."""
    out.mkdir(parents=True, exist_ok=True)
    by_key: dict = {}
    for aid, a in sorted(kb.auth.items.items()):
        for arg in a.atom.args:
            if arg.kind == SYM:
                by_key.setdefault(arg.value, []).append(aid)
    files = []
    for key, aids in sorted(by_key.items()):
        label = kb.state.labels.get(key, key)
        lines = [f"# {label}", "", f"Ключ: `{key}` · knowledge commit `{kb.state.head}`", "", "## Утверждения", ""]
        notes = set()
        for aid in aids:
            a = kb.auth.items[aid]
            base = kb.auth.is_base(aid)
            lines.append(f"- `{('F:' if base else 'D:') + aid[2:]}` {a.key()} — "
                         f"{'принято человеком' if base else 'выведено'}")
            for src in sorted(kb.auth.base.get(aid, ())):
                for p in kb.state.records[src].provenance if src in kb.state.records else []:
                    if p.get("document"):
                        notes.add(Path(p["document"]).stem)
        if notes:
            lines += ["", "## Источники", ""] + [f"- [[{n}]]" for n in sorted(notes)]
        name = "KS - " + re.sub(r"[^\w-]", "_", key) + ".md"
        (out / name).write_text("\n".join(lines) + "\n", encoding="utf-8")
        files.append(name)
    m = manifest(kb, "obsidian", files)
    (out / "manifest.json").write_text(json.dumps(m, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return m


def stale(kb, manifest_path: Path) -> dict:
    """Устарел ли производный артефакт: изменились ли основания с момента экспорта."""
    m = json.loads(manifest_path.read_text(encoding="utf-8"))
    old, new = set(m["basis"]), set(basis(kb))
    return {"stale": old != new, "exported_at_commit": m["knowledge_commit"], "current_commit": kb.state.head,
            "removed_basis": sorted(old - new), "added_basis": sorted(new - old)}
