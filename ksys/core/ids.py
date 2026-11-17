"""Стабильные контентно-адресные идентификаторы и каноническая сериализация.

Один и тот же смысл всегда даёт один и тот же ID — на любой машине и при любом
повторном запуске. Поэтому ID никогда не зависят от времени, порядка вставки
или автоинкремента.
"""
from __future__ import annotations

import hashlib
import json
import unicodedata
from typing import Any

HASH_LEN = 16  # 64 бита: вероятность коллизии на 10^6 объектов ≈ 3·10^-8


def nfc(text: str) -> str:
    return unicodedata.normalize("NFC", text)


def canonical_json(obj: Any) -> str:
    """JSON с сортированными ключами и без пробелов — основа хешей."""
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(*parts: str, length: int = HASH_LEN) -> str:
    """SHA-256 от последовательности строк с префиксом длины (без склеек «ab|c» = «a|bc»)."""
    h = hashlib.sha256()
    for part in parts:
        data = part.encode("utf-8")
        h.update(len(data).to_bytes(8, "big"))
        h.update(data)
    return h.hexdigest()[:length]


def make_id(prefix: str, payload: Any) -> str:
    """ID вида ``PREFIX:hhhh…`` от канонического JSON полезной нагрузки."""
    return f"{prefix}:{digest(canonical_json(payload))}"


def file_hash(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()
