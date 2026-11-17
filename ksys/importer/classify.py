"""Детерминированная классификация листового фрагмента: FragmentKind + Formalization.

Таблица решений применяется сверху вниз, первое совпадение побеждает; каждое
сработавшее правило записывается в ``cues`` — классификация объяснима.
Классификация НЕ создаёт знание: она лишь говорит, чем фрагмент является.
Формальные кандидаты (Formalization FULL/PARTIAL/AMBIGUOUS) появляются только из
грамматики (см. ``semantics``) и структурных экстракторов.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from ..core.enums import FragmentKind as K
from ..core.enums import Formalization as Fm
from ..language import cues as C
from ..source.markdown import MDLINK_RE, TAG_RE, URL_RE, WIKILINK_RE
from ..source.model import Fragment


@dataclass(frozen=True)
class Classification:
    kind: K
    formalization: Fm
    cues: tuple = ()


_LIST_MARK_RE = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+")


def _residue_without_refs(text: str) -> str:
    s = WIKILINK_RE.sub(" ", text)
    s = MDLINK_RE.sub(" ", s)
    s = URL_RE.sub(" ", s)
    s = TAG_RE.sub(" ", s)
    s = _LIST_MARK_RE.sub(" ", s)
    s = re.sub(r"^\s*>+", " ", s)
    return s


def classify_prose(f: Fragment) -> Classification:
    text = f.plain
    residue = _residue_without_refs(f.text)
    has_letters = any(ch.isalpha() for ch in residue)
    if not has_letters:
        if f.inline.wikilinks or f.inline.links or f.inline.urls:
            return Classification(K.REFERENCE, Fm.FULL, ("links_only",))
        if f.inline.tags:
            return Classification(K.METADATA, Fm.FULL, ("tags_only",))
        return Classification(K.STRUCTURAL, Fm.NON_KNOWLEDGE, ("no_letters",))
    if C.is_code_like(f.text.strip()):
        return Classification(K.CODE, Fm.NON_KNOWLEDGE, ("code_like",))
    if C.TREE_RE.match(f.text):
        return Classification(K.METADATA, Fm.NONE, ("tree_listing",))
    if C.FILENAME_RE.match(text.strip()):
        return Classification(K.REFERENCE, Fm.NONE, ("filename",))
    found = dict(C.match_cues(text))
    if text.rstrip().endswith("?"):
        return Classification(K.QUESTION, Fm.NONE, ("question_mark",))
    if "ai_marker" in found:
        return Classification(K.METADATA, Fm.NONE, ("ai_marker",))
    if f.block_type == "quote":
        return Classification(K.QUOTE, Fm.NONE, ("blockquote",))
    if C.GLOSSARY_RE.match(f.text):
        return Classification(K.DEFINITION, Fm.NONE, ("glossary_entry",))
    for name in ("decision", "preference", "opinion", "definition", "rule", "hypothesis", "claim"):
        if name in found:
            return Classification(found[name], Fm.NONE, (name,))
    if C.DEONTIC_RE.search(text):
        return Classification(K.RULE, Fm.NONE, ("deontic",))
    if C.is_imperative(text):
        return Classification(K.PROCEDURE, Fm.NONE, ("imperative",))
    if "plan" in found:
        return Classification(K.PLAN, Fm.NONE, ("plan",))
    if C.INFINITIVE_RE.match(text):
        return Classification(K.PLAN, Fm.NONE, ("infinitive",))
    if text.rstrip().endswith(":"):
        return Classification(K.CONTEXT, Fm.NONE, ("label_colon",))
    ctx = C.heading_kind(f.heading_path)
    if ctx is not None:
        return Classification(ctx, Fm.NONE, ("heading_context",))
    if "event" in found or f.inline.dates:
        return Classification(K.EVENT, Fm.NONE, ("event" if "event" in found else "date",))
    if C.looks_declarative(text):
        return Classification(K.CLAIM, Fm.NONE, ("declarative",))
    if f.block_type in ("list_item",) and len(text.split()) <= 12:
        return Classification(K.RELATION, Fm.PARTIAL, ("list_entry",))  # «пункт Y входит в перечень раздела X»
    return Classification(K.UNRESOLVED, Fm.NONE, ())


def classify(f: Fragment) -> Classification:
    bt, role = f.block_type, f.unit_role
    if role in ("delimiter", "marker") or bt == "hr":
        return Classification(K.STRUCTURAL, Fm.NON_KNOWLEDGE, (role if role != "whole" else bt,))
    if bt in ("code", "math", "code_file"):
        return Classification(K.CODE, Fm.NON_KNOWLEDGE, (bt,))
    if bt == "frontmatter":
        return Classification(K.METADATA, Fm.FULL if f.meta else Fm.NONE, ("frontmatter",))
    if bt == "heading":
        return Classification(K.CONTEXT, Fm.PARTIAL, ("heading",))
    if bt == "table":
        row = f.meta_dict().get("row", 0)
        return Classification(K.CONTEXT if row == 0 else K.RELATION, Fm.PARTIAL, ("table_header" if row == 0 else "table_row",))
    if bt == "task_item":
        return Classification(K.TASK, Fm.PARTIAL, ("task_item",))
    if bt == "callout" and f.unit_index == 0 and C_CALLOUT_RE.match(f.text):
        return Classification(K.CONTEXT, Fm.PARTIAL, ("callout_title",))
    if bt == "html" and not f.plain.strip():
        return Classification(K.STRUCTURAL, Fm.NON_KNOWLEDGE, ("html_markup",))
    has_refs = bool(f.inline.tags or f.inline.wikilinks or f.inline.links or f.inline.urls)
    if not f.plain.strip() and not has_refs:
        return Classification(K.STRUCTURAL, Fm.NON_KNOWLEDGE, ("empty",))
    return classify_prose(f)


C_CALLOUT_RE = re.compile(r"^\s*>\s*\[!")
