"""Русский стеммер Snowball (Porter-style), детерминированный, без словарей.

Реализация следует официальному описанию алгоритма snowball «russian».
Используется для нормализации ключей понятий: «языком программирования» и
«язык программирования» → один ключ ``язык_программирован``.
Ограничение: стеммер может склеить разные слова с общей основой — ключ
показывается человеку рядом с ярлыком, склейку можно разрешить алиасами.
"""
from __future__ import annotations

from functools import lru_cache

VOWELS = set("аеиоуыэюя")

PERFECTIVE_GERUND_1 = ("вшись", "вши", "в")
PERFECTIVE_GERUND_2 = ("ившись", "ывшись", "ивши", "ывши", "ив", "ыв")
ADJECTIVE = ("ими", "ыми", "его", "ого", "ему", "ому", "ее", "ие", "ые", "ое", "ей", "ий", "ый", "ой",
             "ем", "им", "ым", "ом", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею")
PARTICIPLE_1 = ("ем", "нн", "вш", "ющ", "щ")
PARTICIPLE_2 = ("ивш", "ывш", "ующ")
REFLEXIVE = ("ся", "сь")
VERB_1 = ("ешь", "нно", "ете", "йте", "ла", "на", "ли", "ем", "ло", "но", "ет", "ют", "ны", "ть", "й", "л", "н")
VERB_2 = ("уйте", "ейте", "ила", "ыла", "ена", "ите", "или", "ыли", "ило", "ыло", "ено", "ует", "уют", "ены",
          "ить", "ыть", "ишь", "ей", "уй", "ил", "ыл", "им", "ым", "ен", "ят", "ит", "ыт", "ую", "ю")
NOUN = ("иями", "ями", "ами", "ией", "иям", "ием", "иях", "ев", "ов", "ие", "ье", "еи", "ии", "ей", "ой",
        "ий", "ям", "ем", "ам", "ом", "ах", "ях", "ию", "ью", "ия", "ья", "а", "е", "и", "й", "о", "у",
        "ы", "ь", "ю", "я")
SUPERLATIVE = ("ейше", "ейш")
DERIVATIONAL = ("ость", "ост")


def _regions(word: str) -> tuple[int, int, int]:
    rv = r1 = r2 = len(word)
    for i, ch in enumerate(word):
        if ch in VOWELS:
            rv = i + 1
            break
    for i in range(1, len(word)):
        if word[i - 1] in VOWELS and word[i] not in VOWELS:
            r1 = i + 1
            break
    for i in range(r1 + 1, len(word)):
        if word[i - 1] in VOWELS and word[i] not in VOWELS:
            r2 = i + 1
            break
    return rv, r1, r2


def _strip(word: str, start: int, endings: tuple, preceded_by_a: bool = False) -> str | None:
    for e in sorted(endings, key=len, reverse=True):
        if word.endswith(e) and len(word) - len(e) >= start:
            if preceded_by_a:
                pos = len(word) - len(e) - 1
                if pos >= start and word[pos] in "ая":
                    return word[: len(word) - len(e)]
                continue
            return word[: len(word) - len(e)]
    return None


def _strip_group(word: str, rv: int, group1: tuple, group2: tuple) -> str | None:
    """Окончания группы 1 требуют предшествующих «а»/«я» (они остаются); выбирается самое длинное."""
    best = None
    for e in group1:
        if word.endswith(e) and len(word) - len(e) - 1 >= rv and word[len(word) - len(e) - 1] in "ая":
            if best is None or len(e) > len(best):
                best = e
    for e in group2:
        if word.endswith(e) and len(word) - len(e) >= rv:
            if best is None or len(e) > len(best):
                best = e
    return word[: len(word) - len(best)] if best is not None else None


@lru_cache(maxsize=200_000)
def stem(word: str) -> str:
    w = word.lower().replace("ё", "е")
    if not any("а" <= ch <= "я" for ch in w):
        return w
    rv, _r1, r2 = _regions(w)
    # Step 1
    res = _strip_group(w, rv, PERFECTIVE_GERUND_1, PERFECTIVE_GERUND_2)
    if res is not None:
        w = res
    else:
        res = _strip(w, rv, REFLEXIVE)
        if res is not None:
            w = res
        res = _strip(w, rv, ADJECTIVE)
        if res is not None:
            w = res
            res2 = _strip_group(w, rv, PARTICIPLE_1, PARTICIPLE_2)
            if res2 is not None:
                w = res2
        else:
            res = _strip_group(w, rv, VERB_1, VERB_2)
            if res is not None:
                w = res
            else:
                res = _strip(w, rv, NOUN)
                if res is not None:
                    w = res
    # Step 2
    if w.endswith("и") and len(w) - 1 >= rv:
        w = w[:-1]
    # Step 3
    for e in DERIVATIONAL:
        if w.endswith(e) and len(w) - len(e) >= r2:
            w = w[: -len(e)]
            break
    # Step 4
    if w.endswith("нн") and len(w) - 2 >= rv:
        w = w[:-1]
    else:
        res = _strip(w, rv, SUPERLATIVE)
        if res is not None:
            w = res
            if w.endswith("нн"):
                w = w[:-1]
        elif w.endswith("ь") and len(w) - 1 >= rv:
            w = w[:-1]
    return w
