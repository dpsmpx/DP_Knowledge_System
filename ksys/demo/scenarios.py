"""Детерминированные демо-сценарии 1–10 (раздел XLIX задачи).

Каждый сценарий работает в собственном временном рабочем пространстве с
фиксированными часами, поэтому вывод воспроизводим байт-в-байт (golden-тест).
"""
from __future__ import annotations

import tempfile
from pathlib import Path

from ..core.clock import FixedClock
from ..dialogue.session import Dialogue
from ..importer.vault import import_vault
from ..ir.ksf import parse_one
from ..query.answers import Answerer
from ..query.hypothesis import HypothesisEngine
from ..importer.language_analyzer import LanguageAnalyzer
from ..semantics.normalize import ksf_symbol
from ..store.db import connect
from ..store.kb import KnowledgeBase
from ..store.workspace import Workspace
from ..temporal.allen import relation

DEMO_VAULT = Path(__file__).resolve().parents[2] / "demo" / "vault"


class Run:
    def __init__(self, tmp: Path, name: str):
        self.ws = Workspace(tmp / name)
        self.ws.init()
        self.clock = FixedClock("2026-01-01T00:00:00Z")
        self.kb = KnowledgeBase(self.ws, self.clock)
        self.d = Dialogue(self.kb)
        self.lines: list[str] = []

    def say(self, text: str, **kw) -> object:
        r = self.d.handle(text, **kw)
        self.lines.append(f"\n> {text}" + ("   [подтверждено: --yes]" if kw.get("yes") else ""))
        self.lines.append(r.render())
        return r

    def note(self, text: str) -> None:
        self.lines.append(f"\n# {text}")


def lit(src: str):
    return parse_one(src, ksf_symbol).assertion.literal


def s1(r: Run) -> None:
    r.note("Два факта + правило → выведенный факт (с деревом доказательства).")
    r.say("Добавь факт: Алекс является разработчиком.", yes=True)
    r.say("Добавь факт: Алекс работает с Python.", yes=True)
    r.say("Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.", yes=True)
    r.say("Использует ли Алекс Python?")


def s2(r: Run) -> None:
    r.note("Противоречивые факты: BOTH локально, база не становится тривиальной.")
    r.say("Добавь факт: Кофе полезен.", yes=True)
    r.say("Добавь факт: Кофе не полезен.", yes=True)
    r.say("Добавь факт: Чай полезен.", yes=True)
    r.say("Верно ли, что кофе полезен?")
    r.say("Верно ли, что чай полезен?")
    r.say("Верно ли, что чай вреден?")
    r.say("Найди противоречия")


def s3(r: Run) -> None:
    r.note("Гипотеза, поддержанная несколькими фактами и заявлением.")
    r.say("Добавь факт: Алекс является разработчиком.", yes=True)
    r.say("Добавь факт: Алекс работает с Python.", yes=True)
    r.say("Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.", yes=True)
    r.say("Добавь факт: Если X работает с Python, то X использует Python.", yes=True)
    r.say("Добавь факт: По словам Анны, Алекс использует Python.", yes=True)
    r.say("Проверь гипотезу, что Алекс использует Python.")


def s4(r: Run) -> None:
    r.note("Гипотеза, опровергнутая принятым фактом (заявление не может победить факт).")
    r.say("Добавь факт: Шум не вызывает бессонницу.", yes=True)
    r.say("Добавь факт: По словам Ивана, шум вызывает бессонницу.", yes=True)
    r.say("Проверь гипотезу, что шум вызывает бессонницу.")


def s5(r: Run) -> None:
    r.note("Неизвестная гипотеза: UNKNOWN (не FALSE) + недостающие посылки.")
    r.say("Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.", yes=True)
    r.say("Добавь факт: Анна является разработчиком.", yes=True)
    r.say("Проверь гипотезу, что Анна использует Python.")
    r.say("Проверь гипотезу, что Python лучше Java.")


def s6(r: Run) -> None:
    r.note("Пересмотр факта инвалидирует зависимые выводы (TMS, история сохраняется).")
    r.say("Добавь факт: Возраст Алекса равен 12.", yes=True)
    r.say("Добавь факт: Возраст Анны равен 11.", yes=True)
    r.say("Добавь правило: older_than(X, Y) :- value(X, возраст, A), value(Y, возраст, B), A > B.", yes=True)
    r.say("Добавь: Решение: поручить задачу Алексу, потому что Алекс старше Анны.", yes=True)
    r.say("Верно ли, что Алекс старше Анны?")
    r.say("Исправление: возраст Алекса было 10, а не 12.")
    r.say("Исправление: возраст Алекса было 10, а не 12.", yes=True)
    r.say("Верно ли, что Алекс старше Анны?")
    r.say("Верно ли, что Анна старше Алекса?")
    r.say("Какие неизвестные остаются?")


def s7(r: Run) -> None:
    r.note("Неоднозначный текст требует решения человека; решение сохраняется и переиспользуется.")
    r.say("Добавь факт: Алекс держит магазин.")
    pv = r.kb.preview("Алекс держит магазин.")
    owns = next(o for o in pv.options if "sense=owns" in o["labels"])
    r.say("Добавь факт: Алекс держит магазин.", yes=True, reading=owns["reading_id"])
    r.say("Добавь факт: Алекс держит магазин.")
    r.say("Добавь факт: Он использует Git.")
    origin = r.kb.preview("Он использует Git.").formalization.origin
    r.kb.bind(origin, "c0.0", "алекс")
    r.lines.append(f"\n# человек отвечает: «Он» = Алекс  → журнал: bind {origin} c0.0 алекс")
    r.say("Добавь факт: Он использует Git.", yes=True)
    r.say("Кто использует Git?")


def s8(r: Run) -> None:
    r.note("Одно знание разными словами → одно представление и один ID.")
    a = r.kb.preview("Стресс вызывает бессонницу.").options[0]
    b = r.kb.preview("Стресс приводит к бессоннице.").options[0]
    c = r.kb.preview("causes(стресс, бессонница).").options[0]
    for name, o in (("вызывает", a), ("приводит к", b), ("KSF", c)):
        r.lines.append(f"{name:12} → {o['ksf']} {o['node_ids']}")
    r.lines.append(f"одинаковые ID: {a['node_ids'] == b['node_ids'] == c['node_ids']}")
    r.say("Добавь факт: Стресс вызывает бессонницу.", yes=True)
    r.say("Добавь факт: Стресс приводит к бессоннице.")


def s9(r: Run) -> None:
    r.note("Временной факт: истинен только в интервале действия.")
    r.say("Добавь факт: С 2025-01-01 по 2025-06-01 Алекс работает в Google.", yes=True)
    ans = Answerer(r.kb)
    for day in ("2025-03-01", "2025-07-01"):
        res = ans.ask_literals("works_at(алекс, google)?", [lit("works_at(алекс, google)")], at=day,
                               with_proof=False)
        r.lines.append(f"на {day}: {res.status} — {res.answer}")
    from ..ir.terms import Interval
    r.lines.append("отношение Аллена [2025-01-01..2025-06-01] к [2025-03-01..2025-12-31]: "
                   + relation(Interval("2025-01-01", "2025-06-01"), Interval("2025-03-01", "2025-12-31")))


def s10(r: Run) -> None:
    r.note("Трассировка ответа до исходного фрагмента Markdown (demo/vault/people/alex.md).")
    m = import_vault(r.ws, DEMO_VAULT, r.clock, LanguageAnalyzer(r.kb.lexicon, r.kb.state)).manifest
    t = m["totals"]
    r.lines.append(f"импорт demo/vault: файлов {t['files']}, фрагментов {t['fragments']}, кандидатов {t['candidates']}, "
                   f"по формализации {t['by_formalization']}")
    con = connect(r.ws.db_path)
    rows = con.execute("SELECT c.id, f.locator, f.plain FROM candidates c JOIN fragments f ON f.id=c.origin "
                       "WHERE f.doc='people/alex.md' ORDER BY f.ordinal, c.id").fetchall()
    for cid, loc, plain in rows:
        r.lines.append(f"кандидат {cid} ← {loc} «{plain}»")
    for cid, loc, plain in rows[:2]:
        r.kb.accept_candidate(cid, role="fact", confidence="high")
        r.lines.append(f"человек принимает {cid} как факт")
    r.say("Добавь правило: Если X является разработчиком и X работает с Python, то X использует Python.", yes=True)
    r.say("Почему ты считаешь, что Алекс использует Python?")
    r.say("Откуда взялся этот вывод?")


SCENARIOS = [("1. Два факта → правило → вывод", s1), ("2. Противоречивые факты", s2),
             ("3. Гипотеза поддержана несколькими фактами", s3), ("4. Гипотеза опровергнута", s4),
             ("5. Неизвестная гипотеза", s5), ("6. Пересмотр факта инвалидирует выводы", s6),
             ("7. Неоднозначный текст и решение человека", s7), ("8. Разные слова — одно знание", s8),
             ("9. Временной факт", s9), ("10. Provenance до фрагмента Markdown", s10)]


def run_all() -> str:
    out = ["# DEMO — детерминированные сценарии 1–10", "",
           "Сгенерировано командой `knowledge demo` (фиксированные часы, временные рабочие пространства).", ""]
    with tempfile.TemporaryDirectory() as tmp:
        for k, (title, fn) in enumerate(SCENARIOS, 1):
            r = Run(Path(tmp), f"s{k}")
            fn(r)
            out += [f"## Сценарий {title}", "", "```text", *[ln.lstrip("\n") if i == 0 else ln for i, ln in
                                                           enumerate(r.lines)], "```", ""]
    return "\n".join(out) + "\n"
