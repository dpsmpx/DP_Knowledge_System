"""Типизированные ошибки и диагностические сообщения.

Ни один слой не делает «тихий fallback»: либо результат, либо Diagnostic/KSError
с одним из перечисленных видов.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class ErrorKind(str, Enum):
    LEXICAL_ERROR = "LEXICAL_ERROR"
    PARSE_ERROR = "PARSE_ERROR"
    SEMANTIC_AMBIGUITY = "SEMANTIC_AMBIGUITY"
    UNRESOLVED_REFERENCE = "UNRESOLVED_REFERENCE"
    UNKNOWN_ENTITY = "UNKNOWN_ENTITY"
    UNKNOWN_PREDICATE = "UNKNOWN_PREDICATE"
    TYPE_ERROR = "TYPE_ERROR"
    SCHEMA_ERROR = "SCHEMA_ERROR"
    LOGIC_ERROR = "LOGIC_ERROR"
    PROVENANCE_ERROR = "PROVENANCE_ERROR"
    IMPORT_ERROR = "IMPORT_ERROR"
    JOURNAL_ERROR = "JOURNAL_ERROR"
    QUERY_ERROR = "QUERY_ERROR"
    SMT_UNAVAILABLE = "SMT_UNAVAILABLE"


class Severity(str, Enum):
    ERROR = "error"
    WARNING = "warning"
    INFO = "info"


@dataclass(frozen=True)
class Diagnostic:
    kind: ErrorKind
    message: str
    severity: Severity = Severity.ERROR
    location: str | None = None
    details: tuple[tuple[str, Any], ...] = field(default_factory=tuple)

    def to_dict(self) -> dict:
        d = {"kind": self.kind.value, "severity": self.severity.value, "message": self.message}
        if self.location:
            d["location"] = self.location
        if self.details:
            d["details"] = {k: v for k, v in self.details}
        return d

    def __str__(self) -> str:
        loc = f" @ {self.location}" if self.location else ""
        return f"{self.kind.value}{loc}: {self.message}"


class KSError(Exception):
    """Исключение с видом ошибки. Ловится на границе CLI и печатается типизированно."""

    def __init__(self, kind: ErrorKind, message: str, location: str | None = None, **details: Any):
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.location = location
        self.details = details

    def diagnostic(self) -> Diagnostic:
        return Diagnostic(self.kind, self.message, Severity.ERROR, self.location,
                          tuple(sorted(self.details.items())))

    def __str__(self) -> str:
        return str(self.diagnostic())
