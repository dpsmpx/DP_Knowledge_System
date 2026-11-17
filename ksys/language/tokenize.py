"""Токенизатор для контролируемого языка.

Типы токенов:
    WORD   — слово (кириллица/латиница, с дефисами: «кто-то», «по-моему»)
    NAME   — «имя в кавычках» или "имя" (одна сущность, внутренние слова не анализируются)
    LINK   — [[Заметка]] / [[Заметка|алиас]] (ссылка на сущность-заметку)
    NUM    — число (10, 3.5, 3,5)
    DATE   — 2025-01-01 или 01.01.2025
    VAR    — переменная правила: одиночная заглавная латинская буква (X, Y) или ?x
    NODEID — ссылка на узел (F:0123…)
    PUNCT  — , . : ; ! ? ( ) — –  (тире как отдельный токен)
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from ..core.errors import ErrorKind, KSError
from .stem import stem

TOKEN_RE = re.compile(r"""
    (?P<LINK>\[\[[^\]]+\]\])
  | (?P<NAME>«[^»]+»|"[^"]+"|“[^”]+”)
  | (?P<NODEID>(?:A|F|C|H|R|K|D|Q|DEF|EVT|EV|DRV|DEC|J):[0-9a-f]{6,64})
  | (?P<DATE>\d{4}-\d{2}-\d{2}(?!\d)|\d{1,2}\.\d{1,2}\.\d{4}(?!\d))
  | (?P<NUM>-?\d+(?:[.,]\d+)?(?![\d\w]))
  | (?P<VAR>\?[A-Za-z]\w*|(?<![\w])[A-Z](?![\w]))
  | (?P<WORD>[^\W\d_](?:[\w]|-(?=\w))*|\d+[^\W\d_][\w-]*)
  | (?P<PUNCT>[,.:;!?()—–]|-(?=\s)|(?<=\s)-)
  | (?P<WS>\s+)
  | (?P<OTHER>.)
""", re.VERBOSE | re.UNICODE)


@dataclass(frozen=True)
class Tok:
    kind: str
    text: str
    start: int
    end: int

    @property
    def low(self) -> str:
        return self.text.lower().replace("ё", "е")

    @property
    def stem(self) -> str:
        return stem(self.low) if self.kind == "WORD" else self.low

    def is_word(self, *words: str) -> bool:
        return self.kind == "WORD" and self.low in words

    @property
    def is_dash(self) -> bool:
        return self.kind == "PUNCT" and self.text in ("—", "–", "-")

    @property
    def capitalized(self) -> bool:
        return self.kind == "WORD" and self.text[:1].isupper()


def tokenize(text: str, strict: bool = False) -> list[Tok]:
    out: list[Tok] = []
    for m in TOKEN_RE.finditer(text):
        kind = m.lastgroup
        if kind == "WS":
            continue
        if kind == "OTHER":
            if strict:
                raise KSError(ErrorKind.LEXICAL_ERROR, f"unexpected character {m.group()!r}", f"offset {m.start()}")
            out.append(Tok("OTHER", m.group(), m.start(), m.end()))
            continue
        out.append(Tok(kind, m.group(), m.start(), m.end()))
    return out
