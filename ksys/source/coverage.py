"""Проверка инварианта lossless-фрагментации (XLVII: ни один фрагмент не исчезает молча)."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class CoverageResult:
    ok: bool
    chars_total: int          # непробельных символов в документе
    chars_covered: int        # из них внутри листов
    overlaps: tuple           # пары пересекающихся листов
    mismatches: tuple         # листы, чей текст не равен срезу источника
    lost: tuple               # непробельные участки вне листов: (start, end, text)


def check_coverage(text: str, fragments) -> CoverageResult:
    spans = sorted((f.span, f) for f in fragments)
    overlaps, mismatches, lost = [], [], []
    pos = 0
    covered = 0
    for (s, e), f in spans:
        if s < pos:
            overlaps.append((f.id, s, pos))
        elif text[pos:s].strip():
            lost.append((pos, s, text[pos:s][:80]))
        if text[s:e] != f.text:
            mismatches.append(f.id)
        covered += sum(1 for ch in text[s:e] if not ch.isspace())
        pos = max(pos, e)
    if text[pos:].strip():
        lost.append((pos, len(text), text[pos:][:80]))
    total = sum(1 for ch in text if not ch.isspace())
    ok = not overlaps and not mismatches and not lost and covered == total
    return CoverageResult(ok, total, covered, tuple(overlaps), tuple(mismatches), tuple(lost))
