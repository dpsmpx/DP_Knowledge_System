"""Символьная нормализация: поверхностные слова → стабильные ключи понятий.

ключ = основы слов (стеммер Snowball), соединённые «_»; латиница — в нижнем
регистре без стемминга; ё→е; NFC. Одна функция для CNL и для KSF, поэтому
формальная запись и текст на контролируемом языке дают одинаковые ключи.
"""
from __future__ import annotations

import re
import unicodedata

from ..core.errors import ErrorKind, KSError
from ..language.stem import stem

ME_KEY = "me"


def key_from_words(words) -> str:
    parts = []
    for w in words:
        w = unicodedata.normalize("NFC", str(w)).lower().replace("ё", "е")
        for piece in re.split(r"[^\w]+|_", w):
            if piece:
                parts.append(stem(piece))
    key = "_".join(p for p in parts if p)
    if not key:
        raise KSError(ErrorKind.LEXICAL_ERROR, f"empty key from {list(words)!r}")
    return key


def entity_key(text: str) -> str:
    return key_from_words(text.split())


def ksf_symbol(raw: str) -> str:
    """Нормализатор символов для KSF (инъецируется в ir.ksf)."""
    if raw.lower() == ME_KEY:
        return ME_KEY
    return key_from_words([raw])
