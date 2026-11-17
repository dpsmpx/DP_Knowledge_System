import hashlib
import json
import os
import random
import tempfile
import unicodedata
import unittest
from pathlib import Path

from ksys.core.clock import FixedClock
from ksys.core.enums import FragmentKind as K
from ksys.core.enums import Formalization as Fm
from ksys.importer.classify import classify
from ksys.importer.vault import import_vault
from ksys.language.segment import split_line, split_sentences
from ksys.source.coverage import check_coverage
from ksys.source.markdown import extract_inline, parse_document, to_plain
from ksys.store.workspace import Workspace


def sentences(text):
    return [text[s:e] for s, e in split_sentences(text)]


class SegmentTests(unittest.TestCase):
    def test_golden_sentences(self):
        cases = {
            "Python — язык. Он популярен!": ["Python — язык.", "Он популярен!"],
            "Это т.е. пример, и т.д. Конец.": ["Это т.е. пример, и т.д. Конец."],
            "Автор — А. С. Пушкин. Год 1830.": ["Автор — А. С. Пушкин.", "Год 1830."],
            "Версия 3.11 вышла. Ура.": ["Версия 3.11 вышла.", "Ура."],
            "Он сказал: «Стоп.» Потом ушёл.": ["Он сказал: «Стоп.»", "Потом ушёл."],
            "Подожди… и всё.": ["Подожди… и всё."],
            "Line one\nLine two. Three.": ["Line one", "Line two.", "Three."],
            "   ": [],
        }
        for text, expected in cases.items():
            self.assertEqual(sentences(text), expected, text)

    def test_offsets_are_absolute(self):
        self.assertEqual(split_line("Ab. Cd.", 10), [(10, 13), (14, 17)])


DOC = """---
title: Пример
tags: [a, b]
---
# Заголовок

Первое предложение. Второе с [[Ссылка|алиасом]] и #тег!
- пункт списка
  продолжение пункта
- [ ] задача на 2025-03-01
1. нумерованный

> цитата
> вторая строка

> [!note] Заголовок вставки
> текст вставки

```python
print("x")
```

| A | B |
|---|---|
| 1 | 2 |

---
Setext заголовок
===
<div>html текст</div>

%% комментарий %%
"""


class MarkdownTests(unittest.TestCase):
    def test_block_types(self):
        d = parse_document("x.md", DOC)
        self.assertEqual([b.type for b in d.blocks],
                         ["frontmatter", "heading", "paragraph", "list_item", "task_item", "list_item",
                          "quote", "callout", "code", "table", "hr", "heading", "html", "comment"])
        self.assertEqual(d.frontmatter["title"], "Пример")

    def test_coverage_invariant(self):
        d = parse_document("x.md", DOC)
        r = check_coverage(DOC, d.fragments)
        self.assertTrue(r.ok, r)
        self.assertEqual(r.chars_total, r.chars_covered)

    def test_heading_path_and_locators(self):
        d = parse_document("x.md", DOC)
        f = next(f for f in d.fragments if f.plain.startswith("Первое"))
        self.assertEqual(f.heading_path, ("Заголовок",))
        self.assertEqual(f.locator, "x.md::b3.1")
        second = next(f for f in d.fragments if f.plain.startswith("Второе"))
        self.assertEqual(second.locator, "x.md::b3.2")

    def test_inline_extraction(self):
        info = extract_inline("См. [[Папка/Заметка#Раздел|алиас]] и ![[img.png]], #тема/AI, [x](https://a.b/c), 01.02.2025")
        self.assertEqual(info.wikilinks[0], ("Папка/Заметка", "Раздел", "алиас", False))
        self.assertTrue(info.wikilinks[1][3])
        self.assertEqual(info.tags, ("тема/AI",))
        self.assertEqual(info.urls, ("https://a.b/c",))
        self.assertEqual(info.dates, ("2025-02-01",))
        self.assertEqual(extract_inline("# 1 #2 текст").tags, ())

    def test_plain_text(self):
        self.assertEqual(to_plain("**Python** — это [[Язык программирования|язык]] `x` ==важно=="),
                         "Python — это язык x важно")

    def test_ids_stable_under_unrelated_edits(self):
        a = parse_document("n.md", "Альфа.\n\nБета.\n")
        b = parse_document("n.md", "Новое.\n\nАльфа.\n\nБета.\n")
        ids_a = {f.plain: f.id for f in a.fragments}
        ids_b = {f.plain: f.id for f in b.fragments}
        self.assertEqual(ids_a["Альфа."], ids_b["Альфа."])
        self.assertEqual(ids_a["Бета."], ids_b["Бета."])

    def test_duplicate_text_distinct_ids_including_nfd(self):
        nfd = unicodedata.normalize("NFD", "Файл й.pdf")
        d = parse_document("n.md", f"- Файл й.pdf\n- {nfd}\n- Файл й.pdf\n")
        ids = [f.id for f in d.fragments]
        self.assertEqual(len(ids), len(set(ids)))


class CoveragePropertyTests(unittest.TestCase):
    """Свойство: для случайной смеси конструкций Markdown покрытие всегда 100%."""
    PIECES = ["# H", "## Заголовок #тег", "Текст. Ещё текст!", "- пункт", "  - вложенный", "- [x] сделано",
              "1. раз", "> цитата", "> [!tip] t", "```", "code()", "~~~", "| a | b |", "|---|---|", "| 1 | 2 |",
              "---", "***", "<div>", "</div>", "%%", "$$", "x = 1", "**Ж**: определение", "", "", "Строка===",
              "===", "[[ссылка]]", "https://x.y/z", "Сокр. т.е. вот.", "\t\tотступ", "🙂"]

    def test_random_documents(self):
        rng = random.Random(7)
        for _ in range(400):
            text = "\n".join(rng.choice(self.PIECES) for _ in range(rng.randint(1, 30)))
            d = parse_document("r.md", text)
            r = check_coverage(text, d.fragments)
            self.assertTrue(r.ok, (text, r))


class ClassifyTests(unittest.TestCase):
    def kind(self, line, heading=()):
        d = parse_document("c.md", line)
        f = d.fragments[0]
        if heading:
            from dataclasses import replace
            f = replace(f, heading_path=heading)
        return classify(f)

    def test_golden_kinds(self):
        cases = {
            "Что такое Python?": K.QUESTION,
            "Python — это язык программирования.": K.DEFINITION,
            "Если идёт дождь, то земля мокрая.": K.RULE,
            "Я думаю, что это полезно.": K.OPINION,
            "Мне нравится кофе.": K.PREFERENCE,
            "Я решил перейти на Linux.": K.DECISION,
            "Возможно, стресс вызывает бессонницу.": K.HYPOTHESIS,
            "По словам врача, сон важен.": K.CLAIM,
            "Установите пакет через pip.": K.PROCEDURE,
            "Нужно купить хлеб.": K.PLAN,
            "Кофе повышает давление.": K.CLAIM,
            "- [ ] позвонить": K.TASK,
            "[[Заметка]] [[Другая]]": K.REFERENCE,
            "#ежедневник #тип/дневник": K.METADATA,
            "x = foo(bar);": K.CODE,
            "---": K.STRUCTURAL,
            "> Чужая мысль.": K.QUOTE,
        }
        for text, expected in cases.items():
            self.assertEqual(self.kind(text).kind, expected, text)

    def test_heading_context(self):
        c = self.kind("Пробежка 5 км", heading=("Сегодня я сделал:",))
        self.assertEqual(c.kind, K.EVENT)

    def test_formalization_levels(self):
        self.assertEqual(self.kind("[[A]]").formalization, Fm.FULL)
        self.assertEqual(self.kind("# Заголовок").formalization, Fm.PARTIAL)
        self.assertEqual(self.kind("```\ncode\n```").formalization, Fm.NON_KNOWLEDGE)
        self.assertEqual(self.kind("Кофе повышает давление.").formalization, Fm.NONE)


def tree_hash(root: Path) -> str:
    h = hashlib.sha256()
    for p in sorted(root.rglob("*")):
        if p.is_file():
            h.update(str(p.relative_to(root)).encode())
            h.update(p.read_bytes())
            h.update(str(p.stat().st_mtime_ns).encode())
    return h.hexdigest()


class ImporterTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.vault = base / "vault"
        (self.vault / ".obsidian").mkdir(parents=True)
        (self.vault / "notes").mkdir()
        (self.vault / "secret").mkdir()
        (self.vault / ".gitignore").write_text("secret/fixture.md\n", encoding="utf-8")
        (self.vault / ".obsidian" / "app.json").write_text("{}", encoding="utf-8")
        (self.vault / "notes" / "a.md").write_text("# A\n\nPython — это язык. [[b]]\n", encoding="utf-8")
        (self.vault / "notes" / "b.md").write_text("- [ ] задача\n", encoding="utf-8")
        (self.vault / "notes" / "c.txt").write_text("Простой текст. Второе.\n", encoding="utf-8")
        (self.vault / "notes" / "s.py").write_text("print(1)\n", encoding="utf-8")
        (self.vault / "notes" / "x.pwb").write_bytes(b"7z\xbc\xaf\x27\x1c")
        (self.vault / "notes" / "img.jpg").write_bytes(b"\xff\xd8\xff")
        (self.vault / "secret" / "fixture.md").write_text("пароль: 123", encoding="utf-8")
        self.ws = Workspace(base / "ws")
        self.ws.init(str(self.vault))

    def tearDown(self):
        self.tmp.cleanup()

    def test_every_file_has_status_and_vault_is_untouched(self):
        before = tree_hash(self.vault)
        m = import_vault(self.ws, self.vault, FixedClock()).manifest
        self.assertEqual(before, tree_hash(self.vault))
        status = {f["path"]: f["status"] for f in m["files"]}
        self.assertEqual(status, {
            ".gitignore": "REGISTERED_BINARY", ".obsidian/app.json": "EXCLUDED_SERVICE",
            "notes/a.md": "PARSED", "notes/b.md": "PARSED", "notes/c.txt": "PARSED", "notes/s.py": "PARSED",
            "notes/x.pwb": "SKIPPED_UNSUPPORTED_FORMAT", "notes/img.jpg": "REGISTERED_BINARY",
            "secret/fixture.md": "EXCLUDED_IGNORED"})
        secret = next(f for f in m["files"] if f["path"] == "secret/fixture.md")
        self.assertIsNone(secret["sha256"])  # секрет даже не читался
        self.assertEqual(m["totals"]["files"], 9)

    def test_idempotent_and_incremental(self):
        m1 = import_vault(self.ws, self.vault, FixedClock()).manifest
        m2 = import_vault(self.ws, self.vault, FixedClock("2030-01-01T00:00:00Z")).manifest
        self.assertEqual(m1["import_id"], m2["import_id"])
        self.assertNotEqual(m1["generated_at"], m2["generated_at"])
        import sqlite3
        con = sqlite3.connect(self.ws.db_path)
        ids1 = sorted(r[0] for r in con.execute("SELECT id FROM fragments"))
        m3 = import_vault(self.ws, self.vault, FixedClock(), force=True).manifest
        ids3 = sorted(r[0] for r in con.execute("SELECT id FROM fragments"))
        self.assertEqual(ids1, ids3)
        self.assertEqual(m1["import_id"], m3["import_id"])
        # изменение и удаление
        (self.vault / "notes" / "b.md").write_text("- [x] задача\n", encoding="utf-8")
        (self.vault / "notes" / "c.txt").unlink()
        m4 = import_vault(self.ws, self.vault, FixedClock()).manifest
        self.assertNotEqual(m1["import_id"], m4["import_id"])
        self.assertNotIn("notes/c.txt", {f["path"] for f in m4["files"]})
        self.assertEqual(con.execute("SELECT COUNT(*) FROM fragments WHERE doc='notes/c.txt'").fetchone()[0], 0)

    def test_every_fragment_has_kind_and_formalization(self):
        import sqlite3
        import_vault(self.ws, self.vault, FixedClock())
        con = sqlite3.connect(self.ws.db_path)
        rows = con.execute("SELECT kind, formalization FROM fragments").fetchall()
        self.assertTrue(rows)
        for kind, form in rows:
            K(kind)
            Fm(form)


if __name__ == "__main__":
    unittest.main()
