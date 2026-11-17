"""Временная модель: отношения Аллена (дневная гранулярность) и состояние базы на дату.

Факт с интервалом [a, b] не считается истинным вне интервала: запрос «на дату d»
учитывает только факты, чей интервал содержит d (см. query.answers.support(at=…)).
Открытые концы интервала — −∞/+∞.
"""
from __future__ import annotations

import datetime as dt

from ..ir.terms import Interval

NEG_INF, POS_INF = dt.date.min, dt.date.max


def _bounds(i: Interval) -> tuple[dt.date, dt.date]:
    return (dt.date.fromisoformat(i.start) if i.start else NEG_INF,
            dt.date.fromisoformat(i.end) if i.end else POS_INF)


def relation(a: Interval, b: Interval) -> str:
    """before | meets | overlaps | starts | during | finishes | equals и обратные."""
    a1, a2 = _bounds(a)
    b1, b2 = _bounds(b)
    one = dt.timedelta(days=1)
    if a1 == b1 and a2 == b2:
        return "equals"
    if a2 < b1:
        return "meets" if a2 != POS_INF and a2 + one == b1 else "before"
    if b2 < a1:
        return "met_by" if b2 != POS_INF and b2 + one == a1 else "after"
    if a1 == b1:
        return "starts" if a2 < b2 else "started_by"
    if a2 == b2:
        return "finishes" if a1 > b1 else "finished_by"
    if b1 < a1 and a2 < b2:
        return "during"
    if a1 < b1 and b2 < a2:
        return "contains"
    return "overlaps" if a1 < b1 else "overlapped_by"


def holds_at(i: Interval, day: str) -> bool:
    return i.contains(day)


def state_at(engine, day: str) -> list:
    """Исторический срез: утверждения, истинные на дату (по интервалам действия)."""
    return sorted(aid for aid, a in engine.items.items() if a.interval.contains(day))
