"""Грамматика русского контролируемого языка (CNL) → все прочтения (LF).

Принципы (в духе Attempto/ACE):
* язык — подмножество русского с однозначно разбираемыми конструкциями;
* всё, что вне подмножества, даёт NO_PARSE с причиной (а не «примерный» разбор);
* если подмножество допускает несколько прочтений, возвращаются ВСЕ (AMBIGUOUS);
* грамматика не обращается к базе знаний и не пишет в неё.

Поддерживаемые конструкции (см. docs/LANGUAGE_MODEL.md):
  связка:       X является Y | X — Y | X — это Y | X не является Y | X — не Y
  прилагательное: X — быстрый | X быстр(ен)  → is_a(X, быстрый) (прилагательное = класс)
  обладание:    у X есть Y | X имеет Y | X имеет свойство Y
  значение:     <атрибут> <владелец> равен|составляет N [единица]
  глагол:       X <глагол из лексикона> [предлог] Y   (вызывает, приводит к, использует…)
  сравнение:    X лучше|старше|больше… [чем] Y
  сочинение:    X и Y …  /  … Y и Z  /  X … и <глагол> …  (общий субъект)
  правила:      Если A [и B], то C. | Все/Каждый/Любой X … | Ни один X не …
  директивы:    Отношение «является частью» транзитивно.
  определения:  Определение: X — это Y… | Под X понимается Y | X означает Y
  префиксы:     Возможно, … | Обычно … | Гипотеза: … | По словам X, … | X утверждает, что …
                Я думаю, что … | Я заметил, что … | В контексте X: … | Решение: … потому что …
  время:        с D1 по D2 | с D | до D | в 2025 году | в мае 2025 | 1 января 2025 | 2025-01-01
  вопросы:      Верно ли, что …? | Является ли X Y? | Кто/Что <глагол> Y?
"""
from __future__ import annotations

import calendar
import itertools
from dataclasses import dataclass, replace

from ..language.lexicon import (CLAIM_PREFIX_HEADS, CLAIM_VERBS, COMPARATIVES, CONTEXT_PREFIXES,
                                COPULA_PAST, COPULA_WORDS, DECISION_PREFIXES, DEFINITION_PREFIXES,
                                DIRECTIVE_WORDS, HYPOTHESIS_PREFIXES, INDEF_DET, INDEFINITES, ME_WORDS,
                                MODAL_PREFIXES, NEGATIVE_Q, OBSERVATION_PREFIXES, OPINION_PREFIXES,
                                PREDICATE_NAMES_RU, PRONOUNS, QUESTION_PREFIXES, UNIVERSAL_Q,
                                UNSUPPORTED_WORDS, VALUE_WORDS, WH_WORDS, FLOATING_MODALS, NP_ADVERBS,
                                PREPOSITIONS, QUANT_UNSUPPORTED, POSSESSIVE_PRONOUNS, MODAL_PREDICATIVES,
                                LEADING_CONJUNCTIONS, ADVERBS_O, NP_FORBIDDEN, ENGLISH_FUNCTION, WHY_WORDS, Lexicon, Sense,
                                IMPERATIVE_SG, is_adjective, is_finite_verb_like, is_imperative_form, is_infinitive,
                                short_adjective_full_form)
from ..language.tokenize import Tok, tokenize
from .lf import LF, NP, Clause, ParseOutcome

MONTH_FORMS = {}
for _i, _forms in enumerate([
        ("январь", "января", "январе"), ("февраль", "февраля", "феврале"), ("март", "марта", "марте"),
        ("апрель", "апреля", "апреле"), ("май", "мая", "мае"), ("июнь", "июня", "июне"),
        ("июль", "июля", "июле"), ("август", "августа", "августе"), ("сентябрь", "сентября", "сентябре"),
        ("октябрь", "октября", "октябре"), ("ноябрь", "ноября", "ноябре"), ("декабрь", "декабря", "декабре")], 1):
    for _f in _forms:
        MONTH_FORMS[_f] = _i
YEAR_WORDS = {"год", "году", "года", "г"}
MAX_READINGS = 16


class NoParse(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class Frame:
    question: bool = False
    modality: str = "asserted"
    hypothesis: bool = False
    author: NP | None = None
    knowledge_type: str | None = None
    context: NP | None = None
    interval: tuple | None = None
    definition_mode: bool = False
    decision_mode: bool = False


@dataclass(frozen=True)
class Anchor:
    start: int
    end: int
    kind: str            # copula | value | verb | comparative | has | has_property | possession | part_copula
    senses: tuple = ()
    negated: bool = False
    predicate: str = ""


def _low(toks, i):
    return toks[i].low if 0 <= i < len(toks) and toks[i].kind == "WORD" else None


def _prefix_len(toks, words) -> int | None:
    """Длина префикса (в токенах), совпадающего со словами; запятые между словами пропускаются."""
    i = 0
    for w in words:
        while i < len(toks) and toks[i].kind == "PUNCT" and toks[i].text == "," and i > 0:
            i += 1
        if _low(toks, i) != w:
            return None
        i += 1
    return i


def _starts_with(toks, words) -> bool:
    return _prefix_len(toks, words) is not None


def _skip_sep(toks):
    while toks and toks[0].kind == "PUNCT" and toks[0].text in (",", ":"):
        toks = toks[1:]
    return toks


def _iso(y: int, m: int, d: int) -> str:
    calendar.monthrange(y, m)  # ValueError на неверном месяце
    if not 1 <= d <= calendar.monthrange(y, m)[1]:
        raise ValueError("bad day")
    return f"{y:04d}-{m:02d}-{d:02d}"


def _date_token(t: Tok) -> str | None:
    if t.kind != "DATE":
        return None
    try:
        if "-" in t.text:
            y, m, d = (int(x) for x in t.text.split("-"))
        else:
            d, m, y = (int(x) for x in t.text.split("."))
        return _iso(y, m, d)
    except ValueError:
        return None


def date_phrase(toks, i) -> tuple[tuple, int] | None:
    """Дата/месяц/год, начиная с позиции i → ((start, end), следующая позиция)."""
    if i >= len(toks):
        return None
    t = toks[i]
    iso = _date_token(t)
    if iso:
        return (iso, iso), i + 1
    if t.kind == "NUM" and t.text.isdigit():
        n = int(t.text)
        m = MONTH_FORMS.get(_low(toks, i + 1) or "")
        if m and i + 2 < len(toks) and toks[i + 2].kind == "NUM" and len(toks[i + 2].text) == 4:
            try:
                iso = _iso(int(toks[i + 2].text), m, n)
            except ValueError:
                return None
            j = i + 3 + (1 if _low(toks, i + 3) in YEAR_WORDS else 0)
            return (iso, iso), j
        if len(t.text) == 4 and _low(toks, i + 1) in YEAR_WORDS:
            return (f"{n:04d}-01-01", f"{n:04d}-12-31"), i + 2
    m = MONTH_FORMS.get(_low(toks, i) or "")
    if m and i + 1 < len(toks) and toks[i + 1].kind == "NUM" and len(toks[i + 1].text) == 4:
        y = int(toks[i + 1].text)
        last = calendar.monthrange(y, m)[1]
        j = i + 2 + (1 if _low(toks, i + 2) in YEAR_WORDS else 0)
        return (f"{y:04d}-{m:02d}-01", f"{y:04d}-{m:02d}-{last:02d}"), j
    return None


def temporal(toks, i) -> tuple[tuple, int] | None:
    """Временное обстоятельство: с D1 по/до D2 | с D | до D | по D | в D | D."""
    w = _low(toks, i)
    if w == "с":
        d1 = date_phrase(toks, i + 1)
        if not d1:
            return None
        (s, _), j = d1
        if _low(toks, j) in ("по", "до"):
            d2 = date_phrase(toks, j + 1)
            if not d2:
                return None
            (_, e), k = d2
            if e < s:
                return None
            return (s, e), k
        return (s, None), j
    if w in ("до", "по"):
        d = date_phrase(toks, i + 1)
        return ((None, d[0][1]), d[1]) if d else None
    if w == "в":
        d = date_phrase(toks, i + 1)
        return (d[0], d[1]) if d else None
    d = date_phrase(toks, i)
    return (d[0], d[1]) if d else None


class CNLParser:
    def __init__(self, lexicon: Lexicon | None = None):
        self.lex = lexicon or Lexicon()

    # ------------------------------------------------------------------ entry
    def parse(self, text: str) -> ParseOutcome:
        self.text = text
        toks = tokenize(text)
        if not toks:
            return ParseOutcome("NO_PARSE", (), "empty")
        if any(t.kind == "OTHER" for t in toks):
            return ParseOutcome("NO_PARSE", (), "unsupported character")
        question = False
        while toks and toks[-1].kind == "PUNCT" and toks[-1].text in ".!?":
            question = question or toks[-1].text == "?"
            toks = toks[:-1]
        if any(t.kind == "PUNCT" and t.text in "();!?" for t in toks):
            return ParseOutcome("NO_PARSE", (), "unsupported punctuation")
        if any(t.kind == "NODEID" for t in toks) and not _starts_with(toks, ("решение",)):
            return ParseOutcome("NO_PARSE", (), "node reference outside decision")
        if any(t.kind == "WORD" and t.low in ENGLISH_FUNCTION for t in toks):
            return ParseOutcome("NO_PARSE", (), "English text: the controlled language is Russian")
        if _low(toks, 0) in IMPERATIVE_SG or (toks[0].kind == "WORD" and is_imperative_form(toks[0].low)):
            return ParseOutcome("NO_PARSE", (), "imperative (procedure, not a statement)")
        if _low(toks, 0) in WHY_WORDS:
            return ParseOutcome("NO_PARSE", (), f"'{_low(toks, 0)}'-question is not representable as an atom")
        if _starts_with(toks, ("то", "есть")):
            return ParseOutcome("NO_PARSE", (), "discourse marker 'то есть'")
        if _low(toks, 0) in LEADING_CONJUNCTIONS:
            return ParseOutcome("NO_PARSE", (), "sentence starts with a conjunction (depends on previous discourse)")
        for t in toks:
            if t.kind == "WORD" and t.low in QUANT_UNSUPPORTED:
                return ParseOutcome("NO_PARSE", (), f"generalized quantifier {t.low!r} is not representable")
            if t.kind == "WORD" and t.low in MODAL_PREDICATIVES:
                return ParseOutcome("NO_PARSE", (), f"modal predicative {t.low!r} (plan/norm, not a fact)")
        try:
            readings = self._statement(toks, Frame(question=question))
        except NoParse as e:
            return ParseOutcome("NO_PARSE", (), e.reason)
        uniq: dict[str, LF] = {}
        for r in readings:
            uniq.setdefault(r.key_repr(), r)
        out = tuple(uniq.values())
        if not out:
            return ParseOutcome("NO_PARSE", (), "no reading")
        if len(out) > MAX_READINGS:
            return ParseOutcome("NO_PARSE", (), f"too many readings ({len(out)})")
        return ParseOutcome("OK" if len(out) == 1 else "AMBIGUOUS", out)

    def _span_text(self, toks) -> str:
        return self.text[toks[0].start:toks[-1].end] if toks else ""

    # ------------------------------------------------------------------ statement level
    def _statement(self, toks, fr: Frame) -> list[LF]:
        while True:
            peeled = self._peel_prefix(toks, fr)
            if peeled is None:
                break
            toks, fr = peeled
            if not toks:
                raise NoParse("empty after prefix")
        if fr.decision_mode:
            return [self._decision(toks, fr)]
        floating = [k for k in range(1, len(toks)) if _low(toks, k) in FLOATING_MODALS]
        if len(floating) > 1:
            raise NoParse("several modal adverbs")
        if floating:
            k = floating[0]
            mod = FLOATING_MODALS[_low(toks, k)]
            fr = replace(fr, modality=mod if fr.modality == "asserted" else fr.modality,
                         hypothesis=fr.hypothesis or mod == "possible")
            toks = toks[:k] + toks[k + 1:]
        toks, fr = self._peel_temporal_suffix(toks, fr)
        if fr.definition_mode:
            return self._definition(toks, fr)
        first = _low(toks, 0)
        if first == "если":
            return self._rule_if(toks, fr)
        if first == "отношение":
            return [self._directive(toks, fr)]
        if first == "под" or any(_low(toks, k) in ("означает", "понимается", "понимают") for k in range(len(toks))):
            return self._definition(toks, fr)
        if len(toks) > 2 and _low(toks, 1) == "ли":
            return self._inverted_question(toks, fr)
        try:
            return self._assertion(toks, fr)
        except NoParse as e:
            dash = next((k for k, t in enumerate(toks) if t.is_dash), None)
            if dash and 0 < dash < len(toks) - 1 and self._copula_dash(toks, dash):
                try:
                    return self._definition(toks, fr, implicit=True)
                except NoParse:
                    pass
            raise e

    def _peel_prefix(self, toks, fr: Frame):
        for words in QUESTION_PREFIXES:
            n = _prefix_len(toks, words)
            if n:
                return _skip_sep(toks[n:]), replace(fr, question=True)
        for words in HYPOTHESIS_PREFIXES:
            n = _prefix_len(toks, words)
            if n:
                return _skip_sep(toks[n:]), replace(fr, hypothesis=True)
        for words in OPINION_PREFIXES:
            n = _prefix_len(toks, words)
            if n:
                return _skip_sep(toks[n:]), replace(fr, knowledge_type="PERSONAL_OPINION")
        for words in OBSERVATION_PREFIXES:
            n = _prefix_len(toks, words)
            if n:
                return _skip_sep(toks[n:]), replace(fr, knowledge_type="PERSONAL_OBSERVATION")
        for words, modality, hyp in MODAL_PREFIXES:
            n = _prefix_len(toks, words)
            if n and len(toks) > n:
                return _skip_sep(toks[n:]), replace(fr, modality=modality, hypothesis=fr.hypothesis or hyp)
        for words in CLAIM_PREFIX_HEADS:
            if _starts_with(toks, words):
                comma = next((k for k in range(len(words), len(toks)) if toks[k].text == ","), None)
                if comma is None or comma == len(words):
                    raise NoParse("claim source without comma")
                author = self._np(toks[len(words):comma], "author")
                return toks[comma + 1:], replace(fr, author=author)
        for k in range(1, min(len(toks) - 2, 8)):
            if _low(toks, k) in CLAIM_VERBS and toks[k + 1].text == "," and _low(toks, k + 2) == "что":
                author = self._np(toks[:k], "author")
                return toks[k + 3:], replace(fr, author=author)
        for words in CONTEXT_PREFIXES:
            if _starts_with(toks, words):
                sep = next((k for k in range(len(words), len(toks)) if toks[k].text in (":", ",")), None)
                if sep is None or sep == len(words):
                    raise NoParse("context without separator")
                return toks[sep + 1:], replace(fr, context=self._np(toks[len(words):sep], "context"))
        for words in DEFINITION_PREFIXES:
            if _starts_with(toks, words) and len(toks) > len(words) and toks[len(words)].text == ":":
                return toks[len(words) + 1:], replace(fr, definition_mode=True)
        for words in DECISION_PREFIXES:
            if _starts_with(toks, words):
                return _skip_sep(toks[len(words):]), replace(fr, decision_mode=True)
        tm = temporal(toks, 0)
        if tm and tm[1] < len(toks):
            if fr.interval:
                raise NoParse("two time expressions")
            return _skip_sep(toks[tm[1]:]), replace(fr, interval=tm[0])
        return None

    def _peel_temporal_suffix(self, toks, fr: Frame):
        for start in range(len(toks) - 1, 0, -1):
            if _low(toks, start) in ("с", "в", "до", "по"):
                tm = temporal(toks, start)
                if tm and tm[1] == len(toks):
                    if fr.interval:
                        raise NoParse("two time expressions")
                    rest = toks[:start]
                    if rest and rest[-1].text == ",":
                        rest = rest[:-1]
                    return rest, replace(fr, interval=tm[0])
        return toks, fr

    # ------------------------------------------------------------------ forms
    def _wrap(self, fr: Frame, form: str, clauses=(), conditions=(), labels=(), **kw) -> LF:
        return LF(form, tuple(clauses), tuple(conditions), fr.modality, fr.hypothesis, fr.author,
                  fr.knowledge_type, fr.context, fr.interval, reading=tuple(labels), **kw)

    def _assertion(self, toks, fr: Frame) -> list[LF]:
        out = []
        for clauses, labels, _subj in self._conjunction(toks, fr.question):
            for conds, heads, blabels in _bind_variables((), tuple(clauses)):
                has_vars = any(a.kind == "var" for c in heads for a in c.args)
                if has_vars:
                    if fr.question:
                        raise NoParse("quantifier in question")
                    out.append(self._wrap(fr, "rule", heads, conds, labels + blabels))
                else:
                    out.append(self._wrap(fr, "question" if fr.question else "assertion", heads, (), labels + blabels))
        return out

    def _rule_if(self, toks, fr: Frame) -> list[LF]:
        to = next((k for k in range(2, len(toks)) if _low(toks, k) == "то" and toks[k - 1].text == ","), None)
        if to is None:
            to = next((k for k in range(2, len(toks)) if _low(toks, k) == "то"), None)
        if to is None:
            raise NoParse("'если' without 'то'")
        cond_toks = toks[1:to - 1] if toks[to - 1].text == "," else toks[1:to]
        head_toks = toks[to + 1:]
        if not cond_toks or not head_toks:
            raise NoParse("empty condition or conclusion")
        out = []
        for conds, cl, _ in self._conjunction(cond_toks, False, rule_mode=True):
            for heads, hl, _ in self._conjunction(head_toks, False, rule_mode=True):
                for c2, h2, bl in _bind_variables(tuple(conds), tuple(heads)):
                    out.append(self._wrap(fr, "rule", h2, c2, cl + hl + bl))
        return out

    def _directive(self, toks, fr: Frame) -> LF:
        if len(toks) < 3 or _low(toks, len(toks) - 1) not in DIRECTIVE_WORDS:
            raise NoParse("unknown directive")
        name = DIRECTIVE_WORDS[_low(toks, len(toks) - 1)]
        mid = toks[1:-1]
        if len(mid) == 1 and mid[0].kind == "NAME":
            phrase = mid[0].text[1:-1].lower().replace("ё", "е").strip()
        else:
            phrase = " ".join(t.low for t in mid)
        pred = PREDICATE_NAMES_RU.get(phrase) or (phrase if phrase.replace("_", "").isalnum() and " " not in phrase else None)
        if not pred:
            raise NoParse(f"unknown relation {phrase!r}")
        return self._wrap(fr, "directive", directive=(name, pred))

    def _definition(self, toks, fr: Frame, implicit: bool = False) -> list[LF]:
        if _low(toks, 0) == "под":
            k = next((i for i in range(1, len(toks)) if _low(toks, i) in ("понимается", "понимают", "подразумевается")), None)
            if k is None or k == 1 or k + 1 >= len(toks):
                raise NoParse("bad 'под … понимается' definition")
            term_toks, body = toks[1:k], toks[k + 1:]
        else:
            k = next((i for i, t in enumerate(toks) if t.is_dash or _low(toks, i) in ("означает", "это")), None)
            if k is None or k == 0 or k + 1 >= len(toks):
                raise NoParse("definition without separator")
            term_toks = toks[:k]
            body = toks[k + 1:]
            if body and _low(body, 0) == "это":
                body = body[1:]
            if not body:
                raise NoParse("empty definiens")
        term = self._np(term_toks, "subj")
        if term.kind != "name":
            raise NoParse("definition term must be a name")
        chunk = []
        for t in body:
            if t.text == "," or (t.kind == "WORD" and t.low in UNSUPPORTED_WORDS):
                break
            chunk.append(t)
        genus = None
        if chunk:
            try:
                g = self._np(chunk, "obj")
                genus = g if g.kind == "name" else None
            except NoParse:
                genus = None
        if implicit and genus is None:
            raise NoParse("implicit definition without genus")
        definiens = self._span_text(body)
        return [self._wrap(fr, "definition", definition=(term, genus, definiens),
                           labels=("definition=implicit",) if implicit else ())]

    def _decision(self, toks, fr: Frame) -> LF:
        because_at = next((k for k in range(len(toks) - 1)
                           if (_low(toks, k), _low(toks, k + 1)) in (("потому", "что"), ("так", "как"))), None)
        effect_at = next((k for k in range(len(toks)) if _low(toks, k) == "чтобы"), None)
        end_action = min(x for x in (because_at, effect_at, len(toks)) if x is not None)
        action_toks = toks[:end_action]
        while action_toks and action_toks[-1].text == ",":
            action_toks = action_toks[:-1]
        if not action_toks:
            raise NoParse("decision without action")
        because = None
        if because_at is not None:
            stop = effect_at if effect_at and effect_at > because_at else len(toks)
            sub = toks[because_at + 2:stop]
            while sub and sub[-1].text == ",":
                sub = sub[:-1]
            if sub:
                ids = tuple(t.text for t in sub if t.kind == "NODEID")
                if ids:
                    because = ids
                else:
                    try:
                        res = CNLParser(self.lex).parse(self._span_text(sub))
                    except NoParse:
                        res = None
                    because = res.readings[0] if res and res.status == "OK" else self._span_text(sub)
        effect = self._span_text(toks[effect_at + 1:]) if effect_at is not None and effect_at + 1 < len(toks) else None
        return self._wrap(fr, "decision", decision=(self._span_text(action_toks), because, effect))

    def _inverted_question(self, toks, fr: Frame) -> list[LF]:
        """«Является ли X Y?», «Использует ли X Y?» → вопрос да/нет."""
        head, rest = Tok(toks[0].kind, toks[0].text.lower(), toks[0].start, toks[0].end), toks[2:]
        if not rest:
            raise NoParse("empty inverted question")
        if rest[0].kind in ("NAME", "LINK") or rest[0].capitalized:
            k = 1
            while k < len(rest) - 1 and (rest[k].kind in ("NAME", "LINK") or rest[k].capitalized):
                k += 1
            splits = [k]
        else:
            splits = list(range(1, len(rest)))
        out = []
        for k in splits:
            try:
                rebuilt = list(rest[:k]) + [head] + list(rest[k:])
                for lf in self._assertion(rebuilt, replace(fr, question=True)):
                    out.append(replace(lf, reading=lf.reading + ((f"split={k}",) if len(splits) > 1 else ())))
            except NoParse:
                continue
        if not out:
            raise NoParse("cannot split inverted question")
        return out

    # ------------------------------------------------------------------ clauses
    def _anchors(self, toks) -> list[Anchor]:
        out: list[Anchor] = []
        i = 0
        n = len(toks)
        while i < n:
            t = toks[i]
            low = _low(toks, i)
            if i > 0 and t.kind == "WORD" and t.capitalized:
                i += 1  # слово с заглавной в середине — заголовок/название, а не предикат
                continue
            neg = i > 0 and _low(toks, i - 1) == "не"
            start = i - 1 if neg else i
            if low in COPULA_WORDS or low in COPULA_PAST:
                if low == "есть" and _low(toks, 0) == "у":
                    out.append(Anchor(start, i + 1, "possession", negated=neg))
                elif _low(toks, i + 1) == "частью":
                    out.append(Anchor(start, i + 2, "verb", (Sense("part_of", (), gloss="{0} является частью {1}"),), neg))
                    i += 2
                    continue
                else:
                    out.append(Anchor(start, i + 1, "copula", negated=neg))
            elif t.is_dash and 0 < i < n - 1 and self._copula_dash(toks, i):
                end = i + 1
                dneg = False
                if _low(toks, end) == "это":
                    end += 1
                if _low(toks, end) == "не":
                    dneg = True
                    end += 1
                out.append(Anchor(i, end, "copula", negated=dneg))
                i = end
                continue
            elif low == "это" and 0 < i < n - 1 and not toks[i - 1].is_dash:
                out.append(Anchor(start, i + 1, "copula", negated=neg))
            elif low in VALUE_WORDS:
                out.append(Anchor(start, i + 1, "value", negated=neg))
            elif low == "имеет" or low == "имеют":
                if _low(toks, i + 1) in ("свойство", "свойства"):
                    out.append(Anchor(start, i + 2, "has_property", negated=neg))
                    i += 2
                    continue
                out.append(Anchor(start, i + 1, "has", negated=neg))
            elif low in COMPARATIVES:
                out.append(Anchor(start, i + 1, "comparative", negated=neg, predicate=COMPARATIVES[low][0]))
            elif low and t.kind == "WORD":
                senses = self.lex.senses_for(low, _low(toks, i + 1))
                if senses:
                    width = 2 if senses[0].prep else 1
                    out.append(Anchor(start, i + width, "verb", tuple(senses), neg))
                    i += width
                    continue
            i += 1
        if not out and n >= 2 and toks[-1].kind == "WORD" and not toks[-1].capitalized:
            full = short_adjective_full_form(toks[-1].low)
            neg = n >= 3 and _low(toks, n - 2) == "не"
            if full:
                out.append(Anchor(n - 2 if neg else n - 1, n, "short_adj", negated=neg, predicate=full))
        return out

    def _copula_dash(self, toks, i) -> bool:
        """Тире — связка, только если оно отделено пробелами и справа «это» или нарицательное слово.
        «Паоло Коэльо - Алхимик» (между именами) и «8–10» (диапазон) — разделители."""
        t = toks[i]
        spaced = (t.start > 0 and self.text[t.start - 1].isspace()) and \
            (t.end < len(self.text) and self.text[t.end].isspace())
        if not spaced:
            return False
        nxt = toks[i + 1]
        if nxt.kind == "WORD" and nxt.low == "это":
            return True
        if nxt.kind == "WORD" and nxt.low == "не" and i + 2 < len(toks):
            nxt = toks[i + 2]
        return nxt.kind == "WORD" and not nxt.capitalized and nxt.low not in PRONOUNS

    def _conjunction(self, toks, question: bool, rule_mode: bool = False):
        """Разбиение на клаузы по «и» между предикатами; все допустимые разбиения."""
        anchors = self._anchors(toks)
        if not anchors:
            raise NoParse("no predicate")
        gaps = []
        for a, b in zip(anchors, anchors[1:]):
            options = [k for k in range(a.end, b.start) if _low(toks, k) == "и"]
            if not options:
                raise NoParse("several predicates without conjunction")
            gaps.append(options)
        results = []
        last_error = None
        for split in itertools.product(*gaps) if gaps else [()]:
            bounds = [0, *[x for k in split for x in (k, k + 1)], len(toks)]
            segments = [toks[bounds[j]:bounds[j + 1]] for j in range(0, len(bounds), 2)]
            partial = [((), (), None)]
            try:
                for seg in segments:
                    nxt = []
                    for clauses, labels, subj in partial:
                        for c2, l2, s2 in self._clause(seg, subj, question, rule_mode):
                            nxt.append((clauses + tuple(c2), labels + tuple(l2), s2))
                    partial = nxt
            except NoParse as e:
                last_error = e
                continue
            label = (f"split={'/'.join(map(str, split))}",) if len(gaps) and any(len(g) > 1 for g in gaps) else ()
            results.extend((c, lab + label, s) for c, lab, s in partial)
        if not results:
            raise last_error or NoParse("no clause reading")
        return results

    def _clause(self, seg, inherited, question: bool, rule_mode: bool):
        anchors = self._anchors(seg)
        if len(anchors) != 1:
            raise NoParse("clause needs exactly one predicate")
        a = anchors[0]
        left, right = seg[:a.start], seg[a.end:]
        if a.kind == "possession":
            left = left[1:] if _low(left, 0) == "у" else left
        if a.kind == "short_adj":
            right = seg[a.end - 1:a.end]
        if not right:
            raise NoParse("predicate without object")
        if a.kind == "comparative" and a.predicate in ("greater_than", "less_than"):
            r = right[1:] if _low(right, 0) == "чем" else right
            if r and r[0].kind == "NUM" and len(r) == 1 and left:
                op = {"greater_than": ">", "less_than": "<"}[a.predicate]
                if a.negated:
                    op = {">": "<=", "<": ">="}[op]
                num = NP("number", r[0].text, value=r[0].text.replace(",", "."), slot="obj")
                return [([Clause("bound", (owner, attr, NP("string", op, value=op, slot="op"), num))], lab, (owner,))
                        for attr, owner, lab in self._attr_owner(left, inherited)]
        subj_alts = [(inherited, ())] if not left and inherited else self._np_list(left, "subj", question, rule_mode)
        if not left and not inherited:
            raise NoParse("no subject")
        out = []
        if a.kind == "value":
            for attr, owner, lab in self._attr_owner(left, inherited):
                value = self._value_np(right)
                out.append(([Clause(value[0], (owner, attr) + value[1:], a.negated)], lab, (owner,)))
            return out
        senses: tuple = a.senses if a.kind == "verb" else ()
        for subjects, slab in subj_alts:
            if a.kind == "short_adj":
                adj = NP("name", right[0].text, (a.predicate,), slot="obj")
                out.append((_distribute("is_a", subjects, (adj,), a.negated), slab, subjects))
            elif a.kind == "copula":
                for objects, olab in self._np_list(right, "obj", question, rule_mode):
                    out.append((_distribute("is_a", subjects, objects, a.negated), slab + olab, subjects))
            elif a.kind in ("has", "possession", "has_property"):
                pred = "has_property" if a.kind == "has_property" else "has"
                for objects, olab in self._np_list(right, "obj", question, rule_mode):
                    out.append((_distribute(pred, subjects, objects, a.negated), slab + olab, subjects))
            elif a.kind == "comparative":
                r = right[1:] if _low(right, 0) == "чем" else right
                for objects, olab in self._np_list(r, "obj", question, rule_mode):
                    out.append((_distribute(a.predicate, subjects, objects, a.negated), slab + olab, subjects))
            else:
                for sense in senses:
                    lab_s = (f"sense={sense.predicate}",) if len(senses) > 1 else ()
                    subs = subjects
                    if sense.object_kind == "date":
                        d = date_phrase(right, 0)
                        if not d or d[1] != len(right) or d[0][0] != d[0][1]:
                            raise NoParse("expected a date")
                        obj_alts = [((NP("date", self._span_text(right), value=d[0][0], slot="obj"),), ())]
                    else:
                        obj_alts = self._np_list(right, "obj", question, rule_mode)
                    for objects, olab in obj_alts:
                        if sense.inverse:
                            out.append((_distribute(sense.predicate, objects, subs, a.negated), slab + olab + lab_s, subs))
                            continue
                        out.append((_distribute(sense.predicate, subs, objects, a.negated),
                                    slab + olab + lab_s + (("coord=distributive",) if sense.collective and len(subs) > 1 else ()),
                                    subs))
                        if sense.collective and len(subs) > 1:
                            group = NP("group", " и ".join(s.text for s in subs), members=tuple(subs), slot="subj")
                            out.append((_distribute(sense.predicate, (group,), objects, a.negated),
                                        slab + olab + lab_s + ("coord=collective",), (group,)))
        return out

    def _attr_owner(self, left, inherited):
        """«возраст Алекса» → (атрибут, владелец); при неясной границе — все варианты."""
        if not left:
            raise NoParse("value without attribute")
        if len(left) == 1:
            if inherited and len(inherited) == 1:
                return [(self._np(left, "attr"), inherited[0], ())]
            owner = NP("pronoun", "∅", slot="owner")  # чей атрибут — неизвестно: спросить человека
            return [(self._np(left, "attr"), owner, ())]
        marked = [k for k in range(1, len(left)) if left[k].kind in ("NAME", "LINK") or left[k].capitalized
                  or left[k].low in ME_WORDS or left[k].low in ("моего", "моей", "моих")]
        splits = [marked[0]] if marked else list(range(1, len(left)))
        out = []
        for k in splits:
            try:
                attr = self._np(left[:k], "attr")
                owner = self._np(left[k:], "subj")
            except NoParse:
                continue
            out.append((attr, owner, (f"attr-split={k}",) if len(splits) > 1 else ()))
        if not out:
            raise NoParse("cannot split attribute and owner")
        return out

    def _value_np(self, right):
        t = right[0]
        if t.kind == "NUM":
            value = NP("number", t.text, value=t.text.replace(",", "."), slot="obj")
            if len(right) == 1:
                return ("value", value)
            unit = " ".join(x.low for x in right[1:] if x.kind == "WORD")
            if len(unit.split()) != len(right) - 1:
                raise NoParse("bad unit")
            return ("measure", value, NP("string", unit, value=unit, slot="unit"))
        iso = _date_token(t)
        if iso and len(right) == 1:
            return ("value", NP("date", t.text, value=iso, slot="obj"))
        return ("value", self._np(right, "obj"))

    # ------------------------------------------------------------------ noun phrases
    def _np_list(self, toks, slot: str, question: bool, rule_mode: bool):
        if not toks:
            raise NoParse(f"empty {slot}")
        for t in toks:
            if t.kind == "WORD" and t.low in UNSUPPORTED_WORDS:
                raise NoParse(f"unsupported word {t.low!r}")
            if t.kind == "WORD" and t.low == "или":
                raise NoParse("disjunction is not supported")
        conjuncts, cur = [], []
        for t in toks:
            if (t.kind == "WORD" and t.low == "и") or (t.kind == "PUNCT" and t.text == ","):
                if not cur:
                    raise NoParse("empty conjunct")
                conjuncts.append(cur)
                cur = []
            else:
                cur.append(t)
        if not cur:
            raise NoParse("empty conjunct")
        conjuncts.append(cur)
        merged = [conjuncts[0]]  # «ремонтных и обслуживающих работ»: сочинение прилагательных внутри ИГ
        for c in conjuncts[1:]:
            prev = merged[-1]
            if prev[-1].kind == "WORD" and is_adjective(prev[-1].low) and c[0].kind == "WORD" and is_adjective(c[0].low) \
                    and len(c) > 1:
                merged[-1] = prev + c
            else:
                merged.append(c)
        conjuncts = merged
        nps = tuple(self._np(c, slot, question, rule_mode) for c in conjuncts)
        if self._head_ambiguous(conjuncts):
            whole = self._np([t for c in conjuncts for t in c], slot, question, rule_mode)
            return [(nps, ("modifier-scope=separate",)), ((whole,), ("modifier-scope=shared-head",))]
        if self._tail_ambiguous(conjuncts):
            head_mods = conjuncts[-1][1:]
            wide = tuple(self._np(c + head_mods, slot, question, rule_mode) for c in conjuncts[:-1]) + (nps[-1],)
            return [(nps, ("modifier-scope=narrow",)), (wide, ("modifier-scope=wide",))]
        alts = [(nps, ("adj-scope=narrow",) if self._adj_ambiguous(conjuncts) else ())]
        if self._adj_ambiguous(conjuncts):
            first = conjuncts[0]
            adjs = []
            for t in first:
                if t.kind == "WORD" and is_adjective(t.low):
                    adjs.append(t)
                else:
                    break
            wide = [nps[0]] + [self._np(adjs + c, slot, question, rule_mode) for c in conjuncts[1:]]
            alts.append((tuple(wide), ("adj-scope=wide",)))
        return alts

    @staticmethod
    def _head_ambiguous(conjuncts) -> bool:
        """«комбинация лёгкости, аэродинамики и эргономики»: все члены — зависимые первого?"""
        if len(conjuncts) < 2 or len(conjuncts[0]) < 2:
            return False
        if not all(t.kind == "WORD" for t in conjuncts[0]) or any(is_adjective(t.low) for t in conjuncts[0]):
            return False
        return all(len(c) == 1 and c[0].kind == "WORD" and not is_adjective(c[0].low) for c in conjuncts[1:])

    @staticmethod
    def _tail_ambiguous(conjuncts) -> bool:
        """«сбор и организация знаний»: относится ли «знаний» к обоим членам?"""
        if len(conjuncts) < 2 or len(conjuncts[-1]) < 2:
            return False
        last = conjuncts[-1]
        if not all(t.kind == "WORD" for t in last) or is_adjective(last[0].low):
            return False
        return all(len(c) == 1 and c[0].kind == "WORD" and not is_adjective(c[0].low) for c in conjuncts[:-1])

    @staticmethod
    def _adj_ambiguous(conjuncts) -> bool:
        if len(conjuncts) < 2:
            return False
        first = conjuncts[0]
        lead = 0
        for t in first:
            if t.kind == "WORD" and is_adjective(t.low):
                lead += 1
            else:
                break
        if lead == 0 or lead == len(first):
            return False
        return all(len(c) == 1 and c[0].kind == "WORD" and not is_adjective(c[0].low) for c in conjuncts[1:])

    def _np(self, toks, slot: str, question: bool = False, rule_mode: bool = False) -> NP:
        if not toks:
            raise NoParse(f"empty {slot}")
        text = self._span_text(toks)
        if len(toks) == 1:
            t = toks[0]
            if t.kind == "LINK":
                inner = t.text[2:-2]
                target, _, alias = inner.partition("|")
                target = target.split("#")[0].rsplit("/", 1)[-1].strip()
                return NP("name", alias.strip() or target, tuple(target.lower().replace("ё", "е").split()), slot=slot)
            if t.kind == "NAME":
                inner = t.text[1:-1].strip()
                return NP("name", inner, tuple(w.lower().replace("ё", "е") for w in inner.split()), slot=slot)
            if t.kind == "NUM":
                return NP("number", t.text, value=t.text.replace(",", "."), slot=slot)
            if t.kind == "DATE":
                iso = _date_token(t)
                if not iso:
                    raise NoParse("invalid date")
                return NP("date", t.text, value=iso, slot=slot)
            if t.kind == "VAR":
                return NP("var", t.text, value=t.text.lstrip("?").upper(), slot=slot)
            if t.kind == "WORD":
                if t.low in ME_WORDS:
                    return NP("me", t.text, slot=slot)
                if t.low in PRONOUNS:
                    return NP("pronoun", t.text, (t.low,), slot=slot)
                if t.low in INDEFINITES:
                    if not rule_mode:
                        raise NoParse("indefinite outside rule")
                    return NP("var", t.text, slot=slot)
                if t.low in WH_WORDS:
                    if not question:
                        raise NoParse("wh-word outside question")
                    return NP("wh", t.text, value=WH_WORDS[t.low].upper(), slot=slot)
        first = toks[0].low if toks[0].kind == "WORD" else ""
        if first in UNIVERSAL_Q or first in INDEF_DET:
            rest = toks[1:]
            if not rest or not all(t.kind == "WORD" for t in rest):
                raise NoParse("quantifier without type")
            return NP("var", text, type_words=tuple(t.low for t in rest), slot=slot)
        for neg in NEGATIVE_Q:
            if tuple(t.low for t in toks[:len(neg)]) == neg:
                rest = toks[len(neg):]
                if not rest or not all(t.kind == "WORD" for t in rest):
                    raise NoParse("quantifier without type")
                return NP("var", text, type_words=tuple(t.low for t in rest), slot=slot)
        if len(toks) > 1 and any(t.kind == "WORD" and t.low in ME_WORDS for t in toks):
            raise NoParse("first-person word inside a longer noun phrase")
        if any(t.kind == "WORD" and t.low in POSSESSIVE_PRONOUNS for t in toks):
            return NP("pronoun", text, tuple(t.low for t in toks), slot=slot)
        words = []
        for t in toks:
            if t.kind == "WORD":
                if is_infinitive(t.low) or is_imperative_form(t.low) or is_finite_verb_like(t.low):
                    raise NoParse(f"verb form {t.low!r} inside noun phrase")
                if t.low in NP_FORBIDDEN:
                    raise NoParse(f"discourse/subordinate word {t.low!r} inside noun phrase")
                if t.low in ADVERBS_O:
                    raise NoParse(f"adverb {t.low!r} inside noun phrase")
                if t.low in PRONOUNS or t.low in WH_WORDS or t.low in ("не", "ли"):
                    raise NoParse(f"unexpected {t.low!r} inside noun phrase")
                if t.low in PREPOSITIONS:
                    raise NoParse(f"prepositional phrase inside noun phrase ({t.low!r}): attachment is ambiguous")
                if t.low in NP_ADVERBS:
                    raise NoParse(f"adverb {t.low!r} inside noun phrase")
                words.append(t.low)
            elif t.kind in ("NAME", "LINK"):
                inner = t.text[1:-1] if t.kind == "NAME" else t.text[2:-2].split("|")[0]
                words.extend(w.lower().replace("ё", "е") for w in inner.split())
            elif t.kind == "NUM":
                words.append(t.text)
            else:
                raise NoParse(f"unexpected token {t.text!r} in noun phrase")
        return NP("name", text, tuple(words), slot=slot)


def _distribute(pred: str, subjects, objects, negated: bool) -> list[Clause]:
    return [Clause(pred, (s, o), negated) for s in subjects for o in objects]


def _bind_variables(conds: tuple, heads: tuple):
    """Именование переменных (V1, V2… по порядку появления), условия типа для кванторов,
    связывание местоимений с переменными. Несколько антецедентов → несколько прочтений."""
    counter = itertools.count(1)
    names: list[str] = []
    type_conds: list[Clause] = []

    def name_vars(clauses):
        out = []
        for c in clauses:
            args = []
            for a in c.args:
                if a.kind == "var":
                    if a.value is None or a.type_words:
                        v = a.value or f"V{next(counter)}"
                        a = replace(a, value=v)
                        if a.type_words:
                            type_conds.append(Clause("is_a", (replace(a, type_words=()),
                                                              NP("name", " ".join(a.type_words), a.type_words, slot="obj"))))
                    if a.value not in names:
                        names.append(a.value)
                    a = replace(a, type_words=())
                args.append(a)
            out.append(replace(c, args=tuple(args)))
        return tuple(out)

    conds2 = name_vars(conds)
    heads2 = name_vars(heads)
    all_conds = tuple(type_conds) + conds2
    pronoun_slots = [(ci, ai) for ci, c in enumerate(all_conds + heads2) for ai, a in enumerate(c.args)
                     if a.kind == "pronoun" and a.text != "∅"]
    if not pronoun_slots or not names:
        return [(all_conds, heads2, ())]
    results = []
    for choice in itertools.product(names, repeat=len(pronoun_slots)):
        clauses = list(all_conds + heads2)
        for (ci, ai), v in zip(pronoun_slots, choice):
            c = clauses[ci]
            args = list(c.args)
            args[ai] = NP("var", c.args[ai].text, value=v, slot=c.args[ai].slot)
            clauses[ci] = replace(c, args=tuple(args))
        label = tuple(f"ref={clauses[ci].args[ai].text}→{v}" for (ci, ai), v in zip(pronoun_slots, choice)) \
            if len(names) > 1 else ()
        results.append((tuple(clauses[:len(all_conds)]), tuple(clauses[len(all_conds):]), label))
    return results
