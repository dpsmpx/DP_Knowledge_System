"""Лексические маркеры для детерминированной классификации фрагментов.

Это не «понимание» текста: маркер лишь относит фрагмент к классу (OPINION, PLAN, …).
Каждое срабатывание записывается в ``cues`` фрагмента, так что классификация
наблюдаема и проверяема. Списки — конечные и явные.
"""
from __future__ import annotations

import re

from ..core.enums import FragmentKind as K


def _rx(words: list[str]) -> re.Pattern:
    alt = "|".join(sorted((re.escape(w) for w in words), key=len, reverse=True))
    return re.compile(rf"(?<![\w-])(?:{alt})(?![\w-])", re.IGNORECASE)


CUES: list[tuple[str, K, re.Pattern]] = [
    ("ai_marker", K.METADATA, re.compile(r"due to technical issues|search service is temporarily unavailable", re.I)),
    ("hypothesis", K.HYPOTHESIS, _rx(["возможно", "вероятно", "предполагаю", "предположим", "гипотеза",
                                      "может быть", "видимо", "наверное", "по-видимому", "допустим",
                                      "не исключено", "hypothesis", "perhaps", "probably"])),
    ("opinion", K.OPINION, _rx(["я думаю", "я считаю", "мне кажется", "по-моему", "на мой взгляд",
                                "я уверен", "я уверена", "по моему мнению", "я полагаю", "имхо", "imho",
                                "i think", "in my opinion"])),
    ("preference", K.PREFERENCE, _rx(["я люблю", "мне нравится", "мне нравятся", "предпочитаю",
                                      "обожаю", "ненавижу", "не люблю", "мне не нравится", "i like",
                                      "i prefer", "я предпочитаю"])),
    ("decision", K.DECISION, _rx(["я решил", "я решила", "решено", "решение:", "принял решение",
                                  "приняла решение", "выбираю", "я выбрал", "я выбрала", "decided"])),
    ("claim", K.CLAIM, _rx(["по словам", "согласно", "исследования показывают", "учёные", "ученые",
                            "считается", "доказано", "утверждает", "утверждают", "по данным",
                            "как пишет", "according to", "studies show"])),
    ("plan", K.PLAN, _rx(["нужно", "надо", "необходимо", "планирую", "собираюсь", "буду", "план:",
                          "хочу", "цель:", "в планах", "следует сделать", "предстоит", "todo"])),
    ("rule", K.RULE, re.compile(r"(?<![\w-])если(?![\w-]).*(?<![\w-])то(?![\w-])|(?<![\w-])if(?![\w-]).*(?<![\w-])then(?![\w-])", re.I)),
    ("definition", K.DEFINITION, re.compile(
        r"^\s*[\w«\"][^.!?]{0,80}?\s+[—–-]\s+это\s|^\s*[\w«\"][^.!?,]{0,80}?\s+это\s+[\w«\"]|"
        r"\s(?:называется|называют|означает|определяется как|понимается)\s|^\s*под\s+.{1,60}\s+понима", re.I)),
    ("event", K.EVENT, _rx(["сегодня", "вчера", "сходил", "сходила", "купил", "купила", "встретил",
                            "встретила", "сделал", "сделала", "посетил", "посетила", "произошло",
                            "случилось", "начал", "начала", "закончил", "закончила"])),
]

IMPERATIVE_RE = re.compile(r"^\s*(?:\d+[.)]\s*)?([А-ЯЁA-Z][а-яёa-z]+(?:ите|йте))(?![\w-])")
IMPERATIVE_WORDS = {"установите", "откройте", "нажмите", "запустите", "выполните", "создайте",
                    "добавьте", "используйте", "сделайте", "скачайте", "введите", "выберите",
                    "перейдите", "проверьте", "удалите", "настройте", "скопируйте", "вставьте",
                    "install", "open", "run", "click", "create", "add", "use", "download", "select"}

# Заголовки шаблона ежедневного отчёта → класс содержимого секции (детерминированный контекст).
HEADING_CONTEXT = [
    (re.compile(r"сегодня я сделал|что произошло|для чего использовал деньги", re.I), K.EVENT),
    (re.compile(r"что нужно перенести|^todo$|задачи|to ?do", re.I), K.PLAN),
    (re.compile(r"за что я благодарен|мысли|thoughts", re.I), K.OPINION),
]

# Грубый детектор «утвердительного предложения»: есть связка/тире или глагольная форма.
VERBISH_RE = re.compile(
    r"(?<![\w-])(?:является|являются|это|есть|был|была|было|были|будет|будут)(?![\w-])"
    r"|\s[—–]\s"
    r"|[а-яё]{2,}(?:ет|ит|ют|ут|ат|ят|ется|ится|ются|ятся|ал|ил|ял|ел|ла|ло|ли|ался|ался|илась|ется)(?![\w-])"
    r"|(?<![\w-])(?:is|are|was|were|has|have|can|will|does)(?![\w-])", re.I)


def match_cues(text: str) -> list[tuple[str, K]]:
    return [(name, kind) for name, kind, rx in CUES if rx.search(text)]


def is_imperative(text: str) -> bool:
    m = IMPERATIVE_RE.match(text)
    if m:
        return True
    first = re.match(r"^\s*(?:\d+[.)]\s*)?([\w-]+)", text)
    return bool(first and first.group(1).lower() in IMPERATIVE_WORDS)


def heading_kind(heading_path: tuple) -> K | None:
    for title in reversed(heading_path):
        for rx, kind in HEADING_CONTEXT:
            if rx.search(title):
                return kind
    return None


def looks_declarative(text: str) -> bool:
    return bool(VERBISH_RE.search(" " + text + " "))


# ---------------------------------------------------------------------------- структурные эвристики
CODE_KEYWORD_RE = re.compile(r"^\s*(?:def|class|import|from\s+\S+\s+import|return|let|const|var|function|"
                             r"elif|else\s*:|for\s+\w+\s+in|while|try\s*:|except|print\(|public|private|"
                             r"#include|using|package|SELECT|INSERT|UPDATE|sudo|pip|npm|git\s|cd\s|ls\s|mkdir)\b")
CODE_PUNCT_RE = re.compile(r"[=;{}()\[\]<>]|->|=>|::|\+=|==")
TREE_RE = re.compile(r"^[\s│|]*[├└]──|^[\s│]+│|^\s*│\s*$")
FILENAME_RE = re.compile(r"^[^\s/\\][^/\\]{0,200}\.(?:pdf|docx?|xlsx?|pptx?|txt|md|epub|fb2|djvu|zip|rar|7z|"
                         r"mp[34]|avi|mkv|jpe?g|png|gif|apk|exe|py|js|html?|json|pwb|csv|mobi)$", re.I)
INFINITIVE_RE = re.compile(r"^\s*(?:\d+[.)]\s*)?[А-ЯЁ]?[а-яё]+(?:ть|ться|ти|тись)(?![\w-])")
DEONTIC_RE = re.compile(r"(?<![\w-])(?:должен|должна|должно|должны|следует|нельзя|необходимо|обязан|обязана|"
                        r"обязательно|запрещено|разрешено|рекомендуется|must|should)(?![\w-])", re.I)
GLOSSARY_RE = re.compile(r"^\s*(?:[-*+]\s+|\d+[.)]\s+)?(?:\*\*|__)[^*_]{1,80}(?:\*\*|__)\s*(?::|[—–-])\s*\S")


def is_code_like(text: str) -> bool:
    letters = [ch for ch in text if ch.isalpha()]
    if not letters:
        return False
    if CODE_KEYWORD_RE.match(text) and len(CODE_PUNCT_RE.findall(text)) >= 1:
        return True
    cyr = sum(1 for ch in letters if "а" <= ch.lower() <= "я" or ch in "ёЁ")
    if cyr / len(letters) > 0.2:
        return False
    return bool(CODE_KEYWORD_RE.match(text)) or len(CODE_PUNCT_RE.findall(text)) >= 2
