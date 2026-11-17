#!/usr/bin/env python3
"""Бенчмарк ядра на синтетической персональной базе (детерминированный seed).

    python3 tools/bench.py [N_FACTS]

Генерирует журнал из N решений человека (факты + правила), затем измеряет:
replay журнала, массовый вывод, инкрементальное добавление, DRed-отзыв,
запрос и построение дерева доказательства. Результаты печатаются в Markdown.
"""
from __future__ import annotations

import random
import sys
import tempfile
import time
from pathlib import Path

HOME = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HOME))

from ksys.core.clock import FixedClock  # noqa: E402
from ksys.ir.ksf import iter_rules, parse_ksf  # noqa: E402
from ksys.ir.nodes import Assertion, Fact  # noqa: E402
from ksys.ir.terms import Atom, Literal, num, sym  # noqa: E402
from ksys.provenance.proof import ProofBuilder  # noqa: E402
from ksys.query.answers import support  # noqa: E402
from ksys.store.journal import Journal  # noqa: E402
from ksys.store.kb import KnowledgeBase  # noqa: E402
from ksys.store.workspace import Workspace  # noqa: E402

RULES = """
#transitive part_of.
#subclass разработчик, специалист.
#subclass специалист, человек.
#disjoint человек, инструмент.
uses(X, Y) :- is_a(X, разработчик), works_with(X, Y).
depends(X, Y) :- uses(X, Y), is_a(Y, инструмент).
not is_a(X, инструмент) :- is_a(X, человек).
"""


def gen(n: int, rng: random.Random):
    people = max(10, n // 5)
    tools = 50
    facts = []
    for k in range(tools):
        facts.append(Fact(Assertion(Atom("is_a", (sym(f"t{k}"), sym("инструмент"))))))
    for i in range(people):
        facts.append(Fact(Assertion(Atom("is_a", (sym(f"p{i}"), sym("разработчик" if i % 3 else "специалист"))))))
        facts.append(Fact(Assertion(Atom("works_with", (sym(f"p{i}"), sym(f"t{rng.randrange(tools)}"))))))
        facts.append(Fact(Assertion(Atom("value", (sym(f"p{i}"), sym("возраст"), num(rng.randrange(18, 70)))))))
    nodes = max(10, n - len(facts))
    for i in range(1, nodes):  # дерево part_of глубиной ~log
        facts.append(Fact(Assertion(Atom("part_of", (sym(f"n{i}"), sym(f"n{(i - 1) // 4}"))))))
    return facts[:n]


def main() -> int:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 50_000
    rng = random.Random(7)
    facts = gen(n, rng)
    rules = list(iter_rules(parse_ksf(RULES)))
    with tempfile.TemporaryDirectory() as tmp:
        ws = Workspace(Path(tmp) / "ws")
        ws.init()
        j = Journal(ws.journal_path, FixedClock())
        t = time.perf_counter()
        for r in rules:
            j.append("accept", {"node": r.to_dict(), "role": "rule", "provenance": {"source_type": "bench"}})
        for f in facts:
            j.append("accept", {"node": f.to_dict(), "role": "fact", "provenance": {"source_type": "bench"}})
        t_journal = time.perf_counter() - t
        t = time.perf_counter()
        kb = KnowledgeBase(ws, FixedClock("2030-01-01T00:00:00Z"))
        t_open = time.perf_counter() - t
        t = time.perf_counter()
        kb.save_cache()
        t_save = time.perf_counter() - t
        t = time.perf_counter()
        warm = KnowledgeBase(ws, FixedClock("2030-01-01T00:00:00Z"))
        t_warm = time.perf_counter() - t
        assert warm.cache_used and warm.auth.snapshot() == kb.auth.snapshot()
        derived = sum(1 for a in kb.auth.items if not kb.auth.is_base(a))
        t = time.perf_counter()
        kb.accept([Fact(Assertion(Atom("works_with", (sym("p1"), sym("t_new")))))])
        t_add = time.perf_counter() - t
        target = next(f for f in facts if f.assertion.atom.predicate == "part_of" and f.assertion.atom.args[1] == sym("n0"))
        t = time.perf_counter()
        res = kb.retract(target.id)
        t_retract = time.perf_counter() - t
        t = time.perf_counter()
        hits = support(kb.auth, Literal(Atom("uses", (sym("p1"), sym("t_new")))))
        t_query = time.perf_counter() - t
        t = time.perf_counter()
        aid = next(a for a in sorted(kb.auth.items) if kb.auth.items[a].atom.predicate == "depends")
        ProofBuilder(kb.auth, kb.state).build(aid)
        t_proof = time.perf_counter() - t
        rows = [("решений в журнале", f"{len(j.entries())}"), ("запись журнала (генерация)", f"{t_journal:.1f} с"),
                ("открытие базы: replay + массовый вывод (холодное)", f"{t_open:.1f} с"),
                ("сохранение кеша производного состояния", f"{t_save:.1f} с"),
                ("открытие из кеша (тёплое, состояние идентично)", f"{t_warm:.1f} с"),
                ("элементов в auth-слое", f"{len(kb.auth.items)}"), ("из них выведенных", f"{derived}"),
                ("обоснований", f"{len(kb.auth.just)}"),
                ("инкрементальное добавление 1 факта (+журнал)", f"{t_add * 1000:.0f} мс"),
                ("отзыв корневого part_of (DRed)", f"{t_retract * 1000:.0f} мс; удалено {len(res.auth.removed)}, "
                                                   f"восстановлено {len(res.auth.rederived)}"),
                ("запрос по индексу", f"{t_query * 1000:.2f} мс, найдено {len(hits)}"),
                ("дерево доказательства (ограниченный обход)", f"{t_proof * 1000:.0f} мс")]
        print(f"## Бенчмарк: {n} фактов, {len(rules)} правил\n")
        print("| показатель | значение |\n|---|---|")
        for k, v in rows:
            print(f"| {k} | {v} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
