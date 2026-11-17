"""Анализатор фрагментов для импорта: классификация + грамматика → кандидаты.

Импорт никогда не создаёт факты: утверждения из заметок становятся заявлениями
(Claim) источника, правила/определения — кандидатами, которые принимает человек.
"""
from __future__ import annotations

from .. import GRAMMAR_VERSION, PARSER_VERSION
from ..core.enums import FragmentKind as K
from ..core.errors import KSError
from ..core.enums import Formalization as Fm
from .classify import Classification, classify
from .vault import FragmentAnalysis
from ..language.lexicon import Lexicon
from ..source.model import Fragment
from ..semantics.formalize import Formalizer

PROSE_BLOCKS = {"paragraph", "list_item", "quote", "callout", "comment", "html"}
FORM_KIND = {"rule": K.RULE, "directive": K.RULE, "definition": K.DEFINITION, "decision": K.DECISION,
             "question": K.QUESTION}
KT_KIND = {"PERSONAL_OPINION": K.OPINION, "PERSONAL_PREFERENCE": K.PREFERENCE, "HYPOTHESIS": K.HYPOTHESIS,
           "PERSONAL_DECISION": K.DECISION}


class LanguageAnalyzer:
    version = f"cnl-g{GRAMMAR_VERSION}-p{PARSER_VERSION}"

    def __init__(self, lexicon: Lexicon | None = None, resolver=None):
        self.formalizer = Formalizer(lexicon, resolver, mode="import")

    def analyze(self, fragment: Fragment, doc_context: dict) -> FragmentAnalysis:
        base = classify(fragment)
        if fragment.block_type not in PROSE_BLOCKS or fragment.unit_role != "sentence" \
                or base.formalization == Fm.NON_KNOWLEDGE or not fragment.plain.strip() \
                or base.kind in (K.CODE, K.STRUCTURAL, K.REFERENCE, K.METADATA):
            return FragmentAnalysis(base)
        try:
            result = self.formalizer.formalize(fragment.plain, origin=fragment.id)
        except KSError as e:  # не тихий fallback: ошибка записывается в cues фрагмента и в отчёт
            return FragmentAnalysis(Classification(base.kind, base.formalization,
                                                   base.cues + (f"cnl:error:{e.kind.value}",)))
        if result.status == "NO_PARSE":
            return FragmentAnalysis(Classification(base.kind, base.formalization,
                                                   base.cues + (f"cnl:no_parse:{result.reason}",)))
        n = len(result.interpretations)
        cands = [i.candidate_dict(fragment.id, result.input_hash, n) for i in result.interpretations]
        first = result.interpretations[0]
        kind = FORM_KIND.get(first.form) or KT_KIND.get(first.knowledge_type or "") or \
            (K.QUOTE if fragment.block_type == "quote" else K.CLAIM)
        if result.status in ("AMBIGUOUS", "UNRESOLVED"):
            form = Fm.AMBIGUOUS
        elif first.form in ("definition", "decision") or first.notes:
            form = Fm.PARTIAL
        else:
            form = Fm.FULL
        cue = f"cnl:{result.status.lower()}"
        return FragmentAnalysis(Classification(kind, form, base.cues + (cue,)), cands)
