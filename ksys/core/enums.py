"""Закрытые словари статусов. Значения — стабильные строки (попадают в журнал и экспорт)."""
from __future__ import annotations

from enum import Enum


class EpistemicStatus(str, Enum):
    UNKNOWN = "UNKNOWN"                # нет ни подтверждения, ни опровержения
    CLAIMED = "CLAIMED"                # утверждается источником, человеком не принято
    HUMAN_ACCEPTED = "HUMAN_ACCEPTED"  # принято человеком → авторитетное знание
    HUMAN_REJECTED = "HUMAN_REJECTED"  # отклонено человеком
    DERIVED = "DERIVED"                # выведено правилами из авторитетного знания
    SUPPORTED = "SUPPORTED"            # поддержано (заявлениями/аргументами), но не доказано
    REFUTED = "REFUTED"                # выведено отрицание
    CONTESTED = "CONTESTED"            # одновременно поддержано и опровергнуто
    OBSOLETE = "OBSOLETE"              # отозвано или замещено (история сохраняется)


AUTHORITATIVE = frozenset({EpistemicStatus.HUMAN_ACCEPTED})


class Polarity(str, Enum):
    POSITIVE = "positive"
    NEGATIVE = "negative"  # сильное (явное) отрицание, не «отсутствие знания»


class Modality(str, Enum):
    """Упорядочено от сильной к слабой; вывод наследует самую слабую."""
    ASSERTED = "asserted"
    USUALLY = "usually"
    POSSIBLE = "possible"


MODALITY_RANK = {Modality.ASSERTED: 3, Modality.USUALLY: 2, Modality.POSSIBLE: 1}


class Confidence(str, Enum):
    """Качественная уверенность. Назначает человек; для вывода — самое слабое звено.
    UNRATED — «не оценено»; считается самым слабым звеном (неизвестное не повышается)."""
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"
    UNRATED = "unrated"


CONFIDENCE_RANK = {Confidence.HIGH: 4, Confidence.MEDIUM: 3, Confidence.LOW: 2, Confidence.UNRATED: 1}


class KnowledgeType(str, Enum):
    OBJECTIVE_FACT = "OBJECTIVE_FACT"
    PERSONAL_OBSERVATION = "PERSONAL_OBSERVATION"
    PERSONAL_PREFERENCE = "PERSONAL_PREFERENCE"
    PERSONAL_DECISION = "PERSONAL_DECISION"
    PERSONAL_OPINION = "PERSONAL_OPINION"
    EXTERNAL_CLAIM = "EXTERNAL_CLAIM"
    HYPOTHESIS = "HYPOTHESIS"
    PLAN = "PLAN"


class FragmentKind(str, Enum):
    """Статус обработки исходного фрагмента (EVERY SOURCE FRAGMENT → STATUS)."""
    FACT = "FACT"
    RULE = "RULE"
    DEFINITION = "DEFINITION"
    CLAIM = "CLAIM"
    HYPOTHESIS = "HYPOTHESIS"
    QUESTION = "QUESTION"
    PROCEDURE = "PROCEDURE"
    EVENT = "EVENT"
    OPINION = "OPINION"
    PREFERENCE = "PREFERENCE"
    DECISION = "DECISION"
    PLAN = "PLAN"
    TASK = "TASK"
    CONTEXT = "CONTEXT"
    QUOTE = "QUOTE"
    METADATA = "METADATA"
    RELATION = "RELATION"
    REFERENCE = "REFERENCE"
    CODE = "CODE"
    STRUCTURAL = "STRUCTURAL"
    UNRESOLVED = "UNRESOLVED"


NON_KNOWLEDGE_KINDS = frozenset({FragmentKind.STRUCTURAL, FragmentKind.CODE})


class Formalization(str, Enum):
    """Насколько фрагмент представлен формально (метрика покрытия)."""
    FULL = "FULL"                  # всё содержание выражено атомами IR
    PARTIAL = "PARTIAL"            # формальна структура/термин, остаток — текст
    AMBIGUOUS = "AMBIGUOUS"        # несколько прочтений — нужен выбор человека
    NONE = "NONE"                  # классифицирован, формального представления нет
    NON_KNOWLEDGE = "NON_KNOWLEDGE"  # форматирование/код/разметка — сохранено, не знание
    HUMAN_REJECTED = "HUMAN_REJECTED"


class Verdict(str, Enum):
    """Четырёхзначный результат проверки (логика Белнапа)."""
    PROVEN = "PROVEN"
    DISPROVEN = "DISPROVEN"
    BOTH = "BOTH"
    UNKNOWN = "UNKNOWN"


class HypothesisStatus(str, Enum):
    PROVEN = "PROVEN"          # выводится из авторитетного знания, отрицание — нет
    SUPPORTED = "SUPPORTED"    # поддержано заявлениями/аргументами, но не доказано
    CONTESTED = "CONTESTED"    # есть и поддержка, и опровержение
    REFUTED = "REFUTED"        # выводится отрицание, утверждение — нет
    UNKNOWN = "UNKNOWN"        # нет ни того, ни другого


class DialogueOp(str, Enum):
    ASSERT = "ASSERT"
    ASK = "ASK"
    VERIFY = "VERIFY"
    RETRACT = "RETRACT"
    REVISE = "REVISE"
    DEFINE = "DEFINE"
    HYPOTHESIZE = "HYPOTHESIZE"
    EXPLAIN = "EXPLAIN"
    COMPARE = "COMPARE"
    FIND_CONTRADICTIONS = "FIND_CONTRADICTIONS"
    DERIVE = "DERIVE"
    TRACE = "TRACE"
    GENERALIZE = "GENERALIZE"
    SPECIALIZE = "SPECIALIZE"
    UNKNOWNS = "UNKNOWNS"
    WHAT_IF = "WHAT_IF"
