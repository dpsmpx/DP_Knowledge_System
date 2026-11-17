"""Детерминированная сегментация текста на предложения.

Правила (без статистики и моделей):
* граница — после ``. ! ? …`` (с хвостом из закрывающих кавычек/скобок), если дальше
  пробел и начало нового предложения (заглавная буква, цифра, открывающая
  кавычка/скобка, тире диалога, эмодзи) или конец текста;
* перевод строки — всегда граница (в Obsidian строка видна как отдельная строка);
* не граница: известные сокращения (т.е., т.д., г., см., e.g. …), инициалы
  (``А. С. Пушкин``), десятичные числа, многоточие внутри строки перед строчной буквой.

Возвращаются пары смещений [start, end) без ведущих/хвостовых пробелов.
"""
from __future__ import annotations

import re

ABBREVIATIONS = {
    # русские
    "т.е", "т.д", "т.п", "т.к", "т.н", "т.ч", "и.о", "др", "пр", "г", "гг", "в", "вв", "см", "ср",
    "стр", "с", "напр", "мн", "ед", "млн", "млрд", "тыс", "руб", "коп", "ул", "д", "кв", "им",
    "проф", "акад", "доц", "чел", "мин", "сек", "ч", "рис", "табл", "гл", "п", "пп", "ст", "т",
    "англ", "лат", "греч", "русск", "букв", "прим", "изд", "соч", "кн", "вып", "т.о", "н.э", "до н.э",
    # английские
    "e.g", "i.e", "etc", "vs", "mr", "mrs", "ms", "dr", "st", "no", "fig", "approx", "inc", "ltd",
}

TERMINATOR_RE = re.compile(r"[.!?…]+[»\"'”’)\]]*")
OPENERS = set("«\"'“„([{—–-*#•")


def _is_sentence_start(ch: str) -> bool:
    return ch.isupper() or ch.isdigit() or ch in OPENERS or (not ch.isalpha() and not ch.isspace()
                                                              and ord(ch) > 0x2000)


def _abbreviation_before(text: str, dot_pos: int) -> bool:
    j = dot_pos
    while j > 0 and (text[j - 1].isalpha() or text[j - 1] == "."):
        j -= 1
    word = text[j:dot_pos].lower().strip(".")
    if not word:
        return False
    if word in ABBREVIATIONS:
        return True
    letters = word.replace(".", "")
    return len(letters) == 1 and text[j:dot_pos][:1].isupper()  # инициал «А.»


def split_line(text: str, base: int = 0) -> list[tuple[int, int]]:
    """Предложения одной строки (без переводов строк)."""
    spans: list[tuple[int, int]] = []
    start = 0
    for m in TERMINATOR_RE.finditer(text):
        end = m.end()
        if end >= len(text):
            break
        if not text[end].isspace():
            continue
        k = end
        while k < len(text) and text[k].isspace():
            k += 1
        if k >= len(text):
            break
        if not _is_sentence_start(text[k]):
            continue
        punct = m.group()
        if punct.startswith(".") and len(punct.rstrip("»\"'”’)]")) == 1 and _abbreviation_before(text, m.start()):
            continue
        if punct[0] == "." and m.start() > 0 and text[m.start() - 1].isdigit() and text[k].isdigit():
            continue
        spans.append((start, end))
        start = k
    spans.append((start, len(text)))
    out = []
    for s, e in spans:
        while s < e and text[s].isspace():
            s += 1
        while e > s and text[e - 1].isspace():
            e -= 1
        if s < e:
            out.append((base + s, base + e))
    return out


def split_sentences(text: str, base: int = 0) -> list[tuple[int, int]]:
    """Предложения многострочного текста: каждая строка сегментируется отдельно."""
    out: list[tuple[int, int]] = []
    pos = 0
    for line in text.split("\n"):
        out.extend(split_line(line, base + pos))
        pos += len(line) + 1
    return out
