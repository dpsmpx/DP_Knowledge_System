"""Машинные отчёты: все числа вычисляются программой из индекса, манифеста и журнала.

IMPORT_REPORT, COVERAGE_REPORT, KNOWLEDGE_STATS, UNRESOLVED_REPORT, CONTRADICTIONS,
HYPOTHESES, PROVENANCE_REPORT. Отчёты детерминированы (без меток времени, кроме
import_id/knowledge commit), поэтому их diff в git показывает реальные изменения.
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from ..core.enums import FragmentKind as K
from ..core.enums import Formalization as Fm
from ..inference.engine import Engine
from ..ir.nodes import node_from_dict
from ..query import smt
from ..inference.engine import complement_support
from ..query.answers import Answerer
from ..query.hypothesis import HypothesisEngine
from ..store.db import connect

REVIEW_KINDS = {K.CLAIM.value, K.RULE.value, K.DEFINITION.value, K.HYPOTHESIS.value, K.OPINION.value,
                K.PREFERENCE.value, K.DECISION.value, K.EVENT.value, K.PLAN.value, K.PROCEDURE.value,
                K.QUESTION.value, K.QUOTE.value, K.RELATION.value, K.FACT.value}


def pct(n: int, total: int) -> str:
    return f"{(100.0 * n / total):.2f}%" if total else "—"


def _table(headers, rows) -> list:
    out = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return out


class Reporter:
    def __init__(self, kb, manifest: dict | None):
        self.kb = kb
        self.manifest = manifest or {}
        self.con = connect(kb.ws.db_path)
        self.header = [f"> Сгенерировано программой. import_id `{self.manifest.get('import_id', '—')}` · "
                       f"knowledge commit `{kb.state.head or '—'}`", ""]

    # ------------------------------------------------------------------ helpers
    def human_overlay(self) -> dict:
        """fragment_id → решение человека (accepted / rejected / deferred) по кандидатам."""
        st = self.kb.state
        out = {}
        for cid, origin in self.con.execute("SELECT id, origin FROM candidates"):
            if cid in st.accepted_candidates:
                out[origin] = "accepted"
            elif cid in st.rejected_candidates:
                out.setdefault(origin, "rejected")
            elif cid in st.deferred:
                out.setdefault(origin, "deferred")
        return out

    # ------------------------------------------------------------------ IMPORT
    def import_report(self) -> str:
        m = self.manifest
        t = m.get("totals", {})
        lines = ["# IMPORT_REPORT", ""] + self.header
        lines += ["## Версии конвейера", ""] + _table(["компонент", "версия"], sorted(m.get("versions", {}).items()))
        lines += ["", "## Файлы vault (каждый файл получил статус)", ""]
        lines += _table(["статус", "файлов"], sorted(t.get("by_status", {}).items()))
        lines += ["", f"Всего файлов: **{t.get('files', 0)}**; документов с ошибками: **{t.get('documents_with_errors', 0)}**", ""]
        folders: dict = {}
        for f in m.get("files", []):
            top = f["path"].split("/")[0] if "/" in f["path"] else "(корень)"
            agg = folders.setdefault(top, Counter())
            agg["files"] += 1
            agg["fragments"] += f.get("fragments", 0)
            agg["unresolved"] += f.get("unresolved", 0)
            agg["candidates"] += f.get("candidates", 0)
        lines += ["## По папкам верхнего уровня", ""]
        lines += _table(["папка", "файлов", "фрагментов", "UNRESOLVED", "кандидатов IR"],
                        [(k, v["files"], v["fragments"], v["unresolved"], v["candidates"]) for k, v in sorted(folders.items())])
        flags = Counter(fl for f in m.get("files", []) for fl in f.get("flags", []))
        lines += ["", "## Флаги документов", ""] + _table(["флаг", "документов"], sorted(flags.items()))
        errors = [f for f in m.get("files", []) if f.get("errors")]
        lines += ["", "## Ошибки импорта", ""]
        lines += [f"- `{f['path']}`: {json.dumps(f['errors'], ensure_ascii=False)}" for f in errors] or ["Нет."]
        skipped = [f for f in m.get("files", []) if f["status"] == "SKIPPED_UNSUPPORTED_FORMAT"]
        if skipped:
            lines += ["", f"## Неподдерживаемые форматы ({len(skipped)})", "",
                      f"Причина: {skipped[0].get('reason')}. Файлы учтены по хешу, содержимое не разобрано."]
        return "\n".join(lines) + "\n"

    # ------------------------------------------------------------------ COVERAGE
    def coverage(self) -> dict:
        total = self.con.execute("SELECT COUNT(*) FROM fragments").fetchone()[0]
        by_form = dict(self.con.execute("SELECT formalization, COUNT(*) FROM fragments GROUP BY formalization"))
        by_kind = dict(self.con.execute("SELECT kind, COUNT(*) FROM fragments GROUP BY kind"))
        overlay = self.human_overlay()
        decided = Counter(overlay.values())
        classified = sum(by_kind.values())
        rows = self.con.execute("SELECT id, kind, formalization FROM fragments").fetchall()
        buckets = Counter()
        for fid, kind, form in rows:
            h = overlay.get(fid)
            if h == "rejected":
                buckets["explicitly rejected by human"] += 1
            elif h == "accepted":
                buckets["accepted by human (authoritative)"] += 1
            elif form in (Fm.FULL.value, Fm.PARTIAL.value):
                buckets["formally represented (machine candidate / structure)"] += 1
            elif form == Fm.NON_KNOWLEDGE.value:
                buckets["classified as non-knowledge (structure/format/code)"] += 1
            elif form == Fm.AMBIGUOUS.value:
                buckets["ambiguous: human choice required"] += 1
            elif kind == K.UNRESOLVED.value:
                buckets["explicitly UNRESOLVED"] += 1
            else:
                buckets["classified, not formalized: human formalization required"] += 1
        lost = sum(1 for f in self.manifest.get("files", []) for e in f.get("errors", [])
                   if "coverage" in e.get("message", ""))
        semantic = Counter()
        for (cues,) in self.con.execute("SELECT cues FROM fragments WHERE cues LIKE '%cnl:%'"):
            for x in json.loads(cues):
                if x.startswith("cnl:"):
                    semantic["NO_PARSE" if x.startswith("cnl:no_parse") else x.split(":")[1].upper()] += 1
        sentences = self.con.execute("SELECT COUNT(*) FROM fragments WHERE unit_role='sentence'").fetchone()[0]
        return {"total": total, "classified": classified, "by_form": by_form, "by_kind": by_kind,
                "buckets": buckets, "decided": decided, "silently_lost_docs": lost, "semantic": semantic,
                "sentences": sentences}

    def coverage_report(self) -> str:
        c = self.coverage()
        total = c["total"]
        lines = ["# COVERAGE_REPORT", ""] + self.header
        lines += ["## Итог (каждый листовой фрагмент → статус)", "", "```",
                  "IMPORT COVERAGE", "---------------",
                  f"source fragments:          {total}  (100% разбито без потерь: нарушений покрытия в документах — "
                  f"{c['silently_lost_docs']})",
                  f"classified:                {pct(c['classified'], total)}",
                  f"formally represented:      {pct(c['by_form'].get('FULL', 0) + c['by_form'].get('PARTIAL', 0), total)}"
                  f"  (FULL {pct(c['by_form'].get('FULL', 0), total)}, PARTIAL {pct(c['by_form'].get('PARTIAL', 0), total)})",
                  f"ambiguous (human choice):  {pct(c['by_form'].get('AMBIGUOUS', 0), total)}",
                  f"non-knowledge (format):    {pct(c['by_form'].get('NON_KNOWLEDGE', 0), total)}",
                  f"not formalized (NONE):     {pct(c['by_form'].get('NONE', 0), total)}",
                  f"human decisions:           accepted {c['decided'].get('accepted', 0)}, rejected "
                  f"{c['decided'].get('rejected', 0)}, deferred {c['decided'].get('deferred', 0)}",
                  "```", "",
                  "## Критерий «ничего не потеряли» (XLVII)", "",
                  "Каждый фрагмент попадает ровно в одну корзину; сумма = 100%:", ""]
        lines += _table(["корзина", "фрагментов", "доля"],
                        [(k, v, pct(v, total)) for k, v in sorted(c["buckets"].items())])
        lines += ["", f"Сумма корзин: {sum(c['buckets'].values())} = {total} фрагментов.", ""]
        sem, sent = c["semantic"], c["sentences"]
        tried = sum(sem.values())
        lines += ["## Смысловая формализация (грамматика CNL) — отдельно от структурной", "",
                  "Структурная формализация (ссылки, теги, frontmatter, заголовки, таблицы, задачи) не означает понимания "
                  "смысла. Смысловой текст формализует только грамматика контролируемого языка:", ""]
        lines += _table(["показатель", "предложений", "доля от проверенных грамматикой"], [
            ("предложений прозы всего (листы-предложения)", sent, ""),
            ("проверено грамматикой (проза, не код/структура)", tried, "100%"),
            ("формализовано однозначно (FORMALIZED)", sem.get("FORMALIZED", 0), pct(sem.get("FORMALIZED", 0), tried)),
            ("несколько прочтений (AMBIGUOUS)", sem.get("AMBIGUOUS", 0), pct(sem.get("AMBIGUOUS", 0), tried)),
            ("неразрешённая ссылка (UNRESOLVED)", sem.get("UNRESOLVED", 0), pct(sem.get("UNRESOLVED", 0), tried)),
            ("вне контролируемого языка (NO_PARSE, с причиной)", sem.get("NO_PARSE", 0), pct(sem.get("NO_PARSE", 0), tried)),
            ("ошибка анализа (типизированная)", sem.get("ERROR", 0), pct(sem.get("ERROR", 0), tried))])
        lines += ["", "**Ограничение (честно):** свободная проза заметок почти целиком вне контролируемого языка; "
                  "причины по типам — в `UNRESOLVED_REPORT.md`. Это не потеря: фрагменты сохранены, классифицированы и "
                  "доступны человеку для формализации.", ""]
        lines += ["## Пояснения", "",
                  "- FULL — всё содержание выражено атомами IR: смысловые предложения на контролируемом языке, "
                  "а также строки только из ссылок или тегов и пары frontmatter;",
                  "- PARTIAL — формальна структура (заголовок, задача, строка таблицы, пункт перечня, термин определения), "
                  "смысловой текст — нет;",
                  "- NONE — фрагмент классифицирован (CLAIM, PLAN, EVENT…), но вне контролируемого языка: ждёт человека;",
                  "- кандидаты из импорта — **заявления источника**: до решения человека они не являются знанием.", ""]
        lines += ["## Классы фрагментов (FragmentKind)", ""]
        lines += _table(["класс", "фрагментов", "доля"],
                        [(k, v, pct(v, total)) for k, v in sorted(c["by_kind"].items())])
        return "\n".join(lines) + "\n"

    # ------------------------------------------------------------------ STATS
    def knowledge_stats(self) -> str:
        kb = self.kb
        st = kb.state
        docs = self.con.execute("SELECT COUNT(*) FROM documents WHERE status='PARSED'").fetchone()[0]
        frags = self.con.execute("SELECT COUNT(*) FROM fragments").fetchone()[0]
        cand_types = Counter()
        for (nodes,) in self.con.execute("SELECT nodes FROM candidates"):
            for n in json.loads(nodes):
                cand_types[n["type"]] += 1
        roles = Counter((r.role, r.status.value) for r in st.records.values())
        derived = [a for a in kb.auth.items if not kb.auth.is_base(a)]
        relations = sum(1 for a in kb.auth.items.values() if a.atom.arity == 2)
        contra = len(Answerer(kb).contradictions("auth"))
        unresolved = self.con.execute("SELECT COUNT(*) FROM fragments WHERE kind='UNRESOLVED'").fetchone()[0]
        syms: Counter = Counter()
        for a in kb.auth.items.values():
            for x in a.atom.args:
                if x.kind == "sym":
                    syms[x.value] += 1
        orphans = [aid for aid in kb.auth.base if not kb.auth.by_prem.get(aid)
                   and all(syms[x.value] <= 1 for x in kb.auth.items[aid].atom.args if x.kind == "sym")]
        lines = ["# KNOWLEDGE_STATS", ""] + self.header
        lines += _table(["показатель", "значение"], [
            ("документов (разобрано)", docs), ("листовых фрагментов", frags),
            ("кандидатов IR из импорта (по типам узлов)", dict(sorted(cand_types.items())) or 0),
            ("решений в журнале", len(st.entries)),
            ("записей знания всего", len(st.records)),
            ("фактов (HUMAN_ACCEPTED)", len(st.authoritative("fact"))),
            ("правил (HUMAN_ACCEPTED)", len(st.authoritative("rule"))),
            ("ограничений", len(st.authoritative("constraint"))),
            ("определений", len(st.authoritative("definition"))),
            ("решений (Decision)", len(st.authoritative("decision"))),
            ("заявлений (CLAIMED)", len(st.claims())),
            ("гипотез (сохранённых)", len(st.hypotheses())),
            ("отозвано/замещено (OBSOLETE)", sum(1 for r in st.records.values() if r.status.value == "OBSOLETE")),
            ("выведенных фактов (DERIVED)", len(derived)),
            ("обоснований (justifications)", len(kb.auth.just)),
            ("бинарных отношений в авторитетном слое", relations),
            ("неразрешённых фрагментов (UNRESOLVED)", unresolved),
            ("противоречий (auth)", contra),
            ("изолированных фактов (orphan: не посылка вывода и без общих сущностей)", len(orphans))])
        lines += ["", "## Записи по роли и статусу", ""]
        lines += _table(["роль", "статус", "записей"], [(r, s, n) for (r, s), n in sorted(roles.items())])
        lines += ["", "## Решения в журнале по операциям", ""]
        lines += _table(["операция", "решений"], sorted(Counter(e.op for e in st.entries).items()))
        return "\n".join(lines) + "\n"

    # ------------------------------------------------------------------ UNRESOLVED
    def unresolved_report(self, sample: int = 40) -> str:
        lines = ["# UNRESOLVED_REPORT", ""] + self.header
        by_folder = Counter()
        by_doc = Counter()
        for doc, n in self.con.execute("SELECT doc, COUNT(*) FROM fragments WHERE kind='UNRESOLVED' GROUP BY doc"):
            by_doc[doc] = n
            by_folder[doc.split("/")[0] if "/" in doc else "(корень)"] += n
        lines += ["## UNRESOLVED по папкам", ""] + _table(["папка", "фрагментов"], by_folder.most_common())
        lines += ["", "## Документы с наибольшим числом UNRESOLVED", ""] + _table(["документ", "фрагментов"], by_doc.most_common(20))
        reasons = Counter()
        for (cues,) in self.con.execute("SELECT cues FROM fragments WHERE cues LIKE '%cnl:no_parse%'"):
            for c in json.loads(cues):
                if c.startswith("cnl:no_parse:"):
                    reasons[c.split(":", 2)[2].split("(")[0].split("'")[0].strip()[:60]] += 1
        lines += ["", "## Почему грамматика не формализовала предложение (NO_PARSE)", ""]
        lines += _table(["причина", "предложений"], reasons.most_common(25))
        amb = self.con.execute(
            "SELECT f.locator, f.plain, c.id, c.paraphrase, c.ambiguity FROM candidates c JOIN fragments f ON f.id=c.origin "
            "WHERE f.formalization='AMBIGUOUS' ORDER BY f.doc, f.ordinal, c.id LIMIT ?", (sample * 3,)).fetchall()
        lines += ["", f"## Неоднозначные и неразрешённые (первые {sample} фрагментов)", ""]
        seen = set()
        for loc, plain, cid, para, ambig in amb:
            a = json.loads(ambig)
            if loc not in seen:
                if len(seen) >= sample:
                    break
                seen.add(loc)
                lines.append(f"- `{loc}` «{plain[:120]}»")
            q = "; ".join(u["question"] for u in a.get("unresolved", []))
            lines.append(f"  - `{cid[:13]}` {', '.join(a.get('labels', [])) or ''} → {para[:140]}" + (f" ❓ {q}" if q else ""))
        return "\n".join(lines) + "\n"

    # ------------------------------------------------------------------ CONTRADICTIONS
    def source_claim_contradictions(self, limit: int = 50) -> list:
        """Анализ для ревью: прямые противоречия между однозначными заявлениями из заметок (не знание)."""
        eng = Engine()
        loc: dict = {}
        for cid, nodes, locator, plain in self.con.execute(
                "SELECT c.id, c.nodes, f.locator, f.plain FROM candidates c JOIN fragments f ON f.id=c.origin "
                "WHERE f.formalization='FULL' ORDER BY c.id"):
            for n in json.loads(nodes):
                node = node_from_dict(n)
                if hasattr(node, "assertion"):
                    eng.add_base(node.assertion, cid)
                    loc.setdefault(node.assertion.id, []).append((locator, plain))
        out = []
        for aid, a in sorted(eng.items.items()):
            if a.negated:
                continue
            for nid in complement_support(eng, a):
                out.append({"positive": a.key(), "negative": eng.items[nid].key(),
                            "positive_sources": loc.get(aid, [])[:3], "negative_sources": loc.get(nid, [])[:3]})
                if len(out) >= limit:
                    return out
        return out

    def contradictions_report(self) -> str:
        ans = Answerer(self.kb)
        lines = ["# CONTRADICTIONS", ""] + self.header
        for layer in ("auth", "full"):
            cs = ans.contradictions(layer)
            lines += [f"## Прямые противоречия, слой `{layer}` ({len(cs)})", ""]
            for c in cs:
                lines.append(f"- **{c['positive']['ksf']}** ⟂ **{c['negative']['ksf']}**"
                             f" (пересечение {c['overlap']}, контекст {c['context']})")
                for side in ("positive_basis", "negative_basis"):
                    for b in c[side]:
                        srcs = "; ".join(f"{p.get('locator') or p.get('source_type')} «{(p.get('text') or '')[:80]}»"
                                         for p in b.get("provenance", []))
                        lines.append(f"  - {side.split('_')[0]}: {b['record']} [{b['status']}] ← {srcs}")
                if c["dependents"]:
                    lines.append(f"  - зависимые выводы: {', '.join(c['dependents'][:10])}")
            if not cs:
                lines.append("Нет.")
            lines.append("")
        issues = [d for d in ans.schema_issues() if d["severity"] == "error"]
        lines += [f"## Нарушения схемы и ограничений ({len(issues)})", ""]
        lines += [f"- {d['kind']}: {d['message']} @ {d.get('location')}" for d in issues] or ["Нет."]
        c = smt.consistency(self.kb.auth)
        lines += ["", "## Числовая согласованность (SMT)", "",
                  f"Статус: **{c['status']}**, ограничений: {c['constraints']}, бэкенд: {c.get('backend', 'z3' if smt.available() else 'intervals')}"
                  + (f", unsat core: {c['core']}" if c.get("core") else "") + (f", {c['error']}" if c.get("error") else ""), ""]
        sc = self.source_claim_contradictions()
        lines += [f"## Противоречия между заявлениями в заметках (анализ для ревью, не знание) ({len(sc)})", ""]
        for x in sc:
            lines.append(f"- {x['positive']} ⟂ {x['negative']}")
            for l, p in x["positive_sources"] + x["negative_sources"]:
                lines.append(f"  - `{l}` «{p[:100]}»")
        if not sc:
            lines.append("Не найдено среди однозначно формализованных заявлений.")
        return "\n".join(lines) + "\n"

    # ------------------------------------------------------------------ HYPOTHESES
    def hypotheses_report(self) -> str:
        H = HypothesisEngine(self.kb)
        lines = ["# HYPOTHESES", ""] + self.header
        hs = self.kb.state.hypotheses()
        lines += [f"## Сохранённые гипотезы ({len(hs)})", ""]
        for rec in hs:
            r = H.evaluate_assertion(rec.node.assertion, rec.id)
            lines.append(f"- `{rec.id}` {rec.node.ksf()} → **{r.status}** "
                         f"(за: {len(r.supporting)}, против: {len(r.counter)})")
            for m in r.missing_premises[:3]:
                lines.append(f"  - недостающие посылки по {m['rule']}: {m['missing']}")
        if not hs:
            lines.append("Нет.")
        n = self.con.execute("SELECT COUNT(*) FROM fragments WHERE kind='HYPOTHESIS'").fetchone()[0]
        rows = self.con.execute(
            "SELECT f.locator, f.plain, c.paraphrase FROM fragments f LEFT JOIN candidates c ON c.origin=f.id "
            "WHERE f.kind='HYPOTHESIS' ORDER BY (c.id IS NULL), f.doc, f.ordinal LIMIT 30").fetchall()
        lines += ["", f"## Фрагменты заметок, классифицированные как HYPOTHESIS ({n})", ""]
        for loc, plain, para in rows:
            lines.append(f"- `{loc}` «{plain[:120]}»" + (f" → кандидат: {para[:120]}" if para else ""))
        return "\n".join(lines) + "\n"

    # ------------------------------------------------------------------ PROVENANCE
    def provenance_report(self) -> str:
        kb = self.kb
        st = kb.state
        status = Counter()
        rows = []
        for rec in sorted(st.records.values(), key=lambda r: r.id):
            for p in rec.provenance:
                if p.get("source_type") != "vault_note":
                    status[p.get("source_type", "unknown")] += 1
                    continue
                fid = p.get("fragment_id")
                here = self.con.execute("SELECT locator FROM fragments WHERE id=?", (fid,)).fetchone()
                if here:
                    s = "present"
                else:
                    moved = self.con.execute("SELECT locator FROM fragments WHERE text_hash=?", (p.get("text_hash"),)).fetchone()
                    s = "moved" if moved else "missing"
                status[f"vault_note:{s}"] += 1
                if s != "present":
                    rows.append((rec.id, p.get("locator"), s))
        no_prov = [r.id for r in st.authoritative() if not r.provenance]
        no_just = [a for a in kb.auth.items if not kb.auth.is_base(a) and not kb.auth.by_concl.get(a)]
        chain = kb.journal.verify()
        lines = ["# PROVENANCE_REPORT", ""] + self.header
        lines += ["## Инварианты", ""]
        lines += _table(["инвариант", "нарушений"], [
            ("авторитетная запись без provenance", len(no_prov)),
            ("выведенный факт без обоснования", len(no_just)),
            ("целостность цепочки журнала", len(chain)),
            ("отклонённое человеком стало авторитетным без нового решения",
             sum(1 for r in st.authoritative() if r.id in st.rejected))])
        lines += ["", "## Источники provenance записей", ""] + _table(["тип / состояние", "записей provenance"],
                                                                      sorted(status.items()))
        if rows:
            lines += ["", "## Источник изменился", ""] + [f"- {r} `{l}` — {s}" for r, l, s in rows]
        lines += ["", "## Записи и их происхождение", ""]
        for rec in sorted(st.records.values(), key=lambda r: r.id):
            p = rec.provenance[-1] if rec.provenance else {}
            src = p.get("locator") or p.get("document") or p.get("source_type")
            lines.append(f"- `{rec.id}` [{rec.status.value}, {rec.confidence.value}] {rec.node.ksf()} ← "
                         f"{p.get('journal_entry')} {p.get('verifier')} · {src}")
        return "\n".join(lines) + "\n"

    def write_all(self, out: Path) -> list:
        out.mkdir(parents=True, exist_ok=True)
        files = {"IMPORT_REPORT.md": self.import_report, "COVERAGE_REPORT.md": self.coverage_report,
                 "KNOWLEDGE_STATS.md": self.knowledge_stats, "UNRESOLVED_REPORT.md": self.unresolved_report,
                 "CONTRADICTIONS.md": self.contradictions_report, "HYPOTHESES.md": self.hypotheses_report,
                 "PROVENANCE_REPORT.md": self.provenance_report}
        for name, fn in files.items():
            (out / name).write_text(fn(), encoding="utf-8")
        return sorted(files)
