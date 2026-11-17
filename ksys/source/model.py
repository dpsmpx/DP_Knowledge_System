"""Модель исходного документа и листовых фрагментов.

Листовой фрагмент — минимальная единица provenance. Инвариант lossless:
листы документа не пересекаются, каждый лист равен срезу исходного текста,
а всё, что между листами, — только пробельные символы. Значит, ни один
непробельный символ источника не теряется.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from ..core.ids import digest, nfc


@dataclass(frozen=True)
class InlineInfo:
    wikilinks: tuple = ()   # ((target, heading, alias, embed), …)
    links: tuple = ()       # ((text, url, image), …)
    urls: tuple = ()
    tags: tuple = ()
    dates: tuple = ()       # ISO-даты, найденные в тексте
    task: str | None = None  # " " | "x" | другой символ состояния

    def to_dict(self) -> dict:
        d = {}
        for name in ("wikilinks", "links", "urls", "tags", "dates"):
            v = getattr(self, name)
            if v:
                d[name] = [list(x) if isinstance(x, tuple) else x for x in v]
        if self.task is not None:
            d["task"] = self.task
        return d


@dataclass(frozen=True)
class Fragment:
    id: str
    doc: str
    locator: str
    block_index: int
    block_type: str
    unit_index: int
    unit_role: str          # sentence | line | row | delimiter | whole | marker
    span: tuple
    text: str               # дословный срез источника
    plain: str              # текст без разметки — вход языкового слоя
    depth: int = 0
    heading_path: tuple = ()
    inline: InlineInfo = field(default_factory=InlineInfo)
    meta: tuple = ()        # дополнительные пары (ключ, значение), напр. ячейки таблицы

    @property
    def text_hash(self) -> str:
        return digest(nfc(self.text).strip())

    def meta_dict(self) -> dict:
        return dict(self.meta)


def fragment_id(doc: str, block_type: str, text: str, occurrence: int) -> str:
    return "f_" + digest(nfc(doc), block_type, nfc(text).strip(), str(occurrence))


@dataclass(frozen=True)
class Block:
    index: int
    type: str
    span: tuple
    depth: int = 0
    info: str = ""  # язык code-блока, тип callout, уровень заголовка…


@dataclass
class ParsedDocument:
    path: str
    text: str
    blocks: list
    fragments: list
    frontmatter: dict
    warnings: list
