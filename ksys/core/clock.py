"""Инъецируемые часы.

Время используется ровно в одном месте — при записи решения человека в журнал.
Производное состояние никогда не читает часы, поэтому оно воспроизводимо.
Для тестов и демо время фиксируется через ``KS_NOW`` или ``FixedClock``.
"""
from __future__ import annotations

import datetime as _dt
import os


class Clock:
    def now(self) -> str:  # ISO-8601, UTC, секунды
        raise NotImplementedError


class SystemClock(Clock):
    def now(self) -> str:
        return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


class FixedClock(Clock):
    """Детерминированные часы: каждый вызов сдвигает время на ``step`` секунд."""

    def __init__(self, start: str = "2026-01-01T00:00:00Z", step: int = 1):
        self._t = _dt.datetime.fromisoformat(start.replace("Z", "+00:00"))
        self._step = _dt.timedelta(seconds=step)

    def now(self) -> str:
        value = self._t.isoformat().replace("+00:00", "Z")
        self._t += self._step
        return value


def clock_from_env() -> Clock:
    fixed = os.environ.get("KS_NOW")
    return FixedClock(fixed) if fixed else SystemClock()
