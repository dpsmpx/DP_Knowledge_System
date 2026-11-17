"""Фасад языкового слоя: текст → (CNL | KSF) → интерпретации.

Наблюдаемый конвейер для одного входа:
    SOURCE → TOKENS → PARSE (все прочтения LF) → SEMANTIC UNITS (IR) → PARAPHRASE
Статус:
    FORMALIZED  — ровно одно полное прочтение;
    AMBIGUOUS   — несколько прочтений → выбор человека (resolve);
    UNRESOLVED  — есть неразрешённые ссылки → вопрос человеку (bind);
    NO_PARSE    — вне контролируемого языка (с причиной).
Если человек уже выбрал прочтение для этого origin, выбор применяется детерминированно.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from ..core.errors import KSError
from ..ir.ksf import Directive, expand_directive, parse_ksf
from ..ir.nodes import Node
from ..language.lexicon import Lexicon
from ..parser.cnl import CNLParser
from ..parser.lf import ParseOutcome
from .build import Interpretation, NullResolver, SemanticBuilder, input_hash
from .normalize import ksf_symbol
from .paraphrase import paraphrase_nodes


@dataclass
class Formalization:
    text: str
    origin: str
    input_hash: str
    status: str                       # FORMALIZED | AMBIGUOUS | UNRESOLVED | NO_PARSE
    syntax: str                       # cnl | ksf
    interpretations: list = field(default_factory=list)
    reason: str = ""
    chosen: str | None = None         # reading_id, выбранный человеком ранее

    @property
    def selected(self) -> Interpretation | None:
        if self.status != "FORMALIZED":
            return None
        if self.chosen:
            return next(i for i in self.interpretations if i.reading_id == self.chosen)
        return self.interpretations[0]


KSF_START_RE = re.compile(r"^\s*(?:\?-|:-|#\w|constraint\b|decision\s+\"|define\s+\w|event\s+\w+\(|"
                          r"(?:(?:fact|claim(?:\s+by\s+\S+)?|hypothesis|rule\s+\w+)\s*:?\s*)?(?:not\s+|¬)?"
                          r"[a-zа-яё_][\w]*\s*\()", re.UNICODE)


def looks_formal(text: str) -> bool:
    """KSF распознаётся только по явной форме: предикат(…) со строчной буквы, ?-, :-, #директива…"""
    return bool(KSF_START_RE.match(text))


class Formalizer:
    def __init__(self, lexicon: Lexicon | None = None, resolver=None, mode: str = "assert"):
        self.lexicon = lexicon or Lexicon()
        self.parser = CNLParser(self.lexicon)
        self.resolver = resolver or NullResolver()
        self.mode = mode

    def formalize(self, text: str, origin: str | None = None) -> Formalization:
        ih = input_hash(text)
        origin = origin or f"input:{ih}"
        if self.mode != "import" and looks_formal(text):  # в тексте источника KSF не распознаётся (это код)
            return self._ksf(text, origin, ih)
        outcome: ParseOutcome = self.parser.parse(text)
        if outcome.status == "NO_PARSE":
            return Formalization(text, origin, ih, "NO_PARSE", "cnl", [], outcome.reason)
        builder = SemanticBuilder(self.resolver, self.mode)
        interps = builder.build_all(outcome, origin)
        chosen = None
        choice = getattr(self.resolver, "reading_choice", lambda o: None)(origin)
        if len(interps) > 1 and choice and any(i.reading_id == choice for i in interps):
            chosen = choice
        candidates = [i for i in interps if i.reading_id == chosen] if chosen else interps
        if any(i.unresolved for i in candidates):
            return Formalization(text, origin, ih, "UNRESOLVED", "cnl", interps, "unresolved reference", chosen)
        if len(candidates) > 1:
            return Formalization(text, origin, ih, "AMBIGUOUS", "cnl", interps, "several readings", chosen)
        if not candidates[0].nodes:
            reason = "; ".join(d.message for d in candidates[0].diagnostics) or "no semantic units"
            return Formalization(text, origin, ih, "NO_PARSE", "cnl", interps, reason, chosen)
        return Formalization(text, origin, ih, "FORMALIZED", "cnl", interps, "", chosen)

    def _ksf(self, text: str, origin: str, ih: str) -> Formalization:
        try:
            items = parse_ksf(text if text.rstrip().endswith(".") else text + ".", ksf_symbol)
        except KSError as e:
            return Formalization(text, origin, ih, "NO_PARSE", "ksf", [], str(e))
        nodes: list[Node] = []
        for item in items:
            if isinstance(item, Directive):
                nodes.extend(expand_directive(item))
            else:
                nodes.append(item)
        interp = Interpretation("RD:ksf", (), nodes, paraphrase_nodes(nodes, self.resolver), form="ksf")
        return Formalization(text, origin, ih, "FORMALIZED", "ksf", [interp])
