"""Детерминированный разбор Obsidian-Markdown в блоки и листовые фрагменты.

Блоки: frontmatter, heading (ATX/setext), paragraph, list_item, task_item, quote,
callout, code (```/~~~), math ($$), comment (%%), html, table, hr.

Листы:
* текстовые блоки (paragraph/list_item/task_item/quote/callout) — предложения;
  каждый лист начинается с начала своей строки, если он первый на строке
  (так маркеры «- », «> » входят в лист и ничего не теряется);
* таблица — строки (строка-разделитель отдельным листом);
* frontmatter и html — строки;
* code/math/comment/heading/hr — блок целиком.
"""
from __future__ import annotations

import html
import re
from collections import Counter

from ..language.segment import split_line
from ..core.ids import nfc
from .model import Block, Fragment, InlineInfo, ParsedDocument, fragment_id

FENCE_RE = re.compile(r"^[ \t]*(`{3,}|~{3,})(.*)$")
HEADING_RE = re.compile(r"^ {0,3}(#{1,6})(?:[ \t]+|$)(.*)$")
HR_RE = re.compile(r"^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$")
SETEXT_RE = re.compile(r"^ {0,3}(=+|-+)[ \t]*$")
LIST_RE = re.compile(r"^([ \t]*)([-*+]|\d{1,9}[.)])(?:[ \t]+|$)")
TASK_RE = re.compile(r"^\[(.)\](?:[ \t]+|$)")
QUOTE_PREFIX_RE = re.compile(r"^[ \t]*(?:>[ \t]?)+")
CALLOUT_RE = re.compile(r"^[ \t]*>[ \t]*\[!([^\]]+)\][+-]?[ \t]*(.*)$")
HTML_START_RE = re.compile(r"^[ \t]*<(?:/?[A-Za-z][\w-]*[\s/>]|/?[A-Za-z][\w-]*$|!--|!DOCTYPE|!doctype|\?)")
TABLE_DELIM_RE = re.compile(r"^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$")

WIKILINK_RE = re.compile(r"(!?)\[\[([^\]\|#\^]*)((?:#|\^)[^\]\|]*)?(?:\|([^\]]*))?\]\]")
MDLINK_RE = re.compile(r"(!?)\[([^\]]*)\]\((<[^>]*>|[^)\s]+)(?:\s+\"[^\"]*\")?\)")
URL_RE = re.compile(r"https?://[^\s<>()\[\]\"'«»]+[^\s<>()\[\]\"'«».,;:!?]")
TAG_RE = re.compile(r"(?<![\w/&#\]\[`])#([^\s#\[\](){}.,;:!?\"'«»`]*[^\W\d_][^\s#\[\](){}.,;:!?\"'«»`]*)")
ISO_DATE_RE = re.compile(r"(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)")
DMY_DATE_RE = re.compile(r"(?<![\d.])(\d{1,2})\.(\d{1,2})\.(\d{4})(?![\d])")
TEMPLATER_RE = re.compile(r"<%.*?%>", re.S)
HTML_TAG_RE = re.compile(r"<[^<>\n]{1,400}>")


# --------------------------------------------------------------------------- inline

def _valid_date(y: int, m: int, d: int) -> str | None:
    import datetime as dt
    try:
        return dt.date(y, m, d).isoformat()
    except ValueError:
        return None


def extract_inline(text: str, task: str | None = None) -> InlineInfo:
    wikilinks = tuple((m.group(2).strip(), (m.group(3) or "")[1:], (m.group(4) or "").strip(),
                       bool(m.group(1))) for m in WIKILINK_RE.finditer(text))
    without_wl = WIKILINK_RE.sub(" ", text)
    links = tuple((m.group(2), m.group(3).strip("<>"), bool(m.group(1))) for m in MDLINK_RE.finditer(without_wl))
    urls = tuple(dict.fromkeys(m.group() for m in URL_RE.finditer(text)))
    code_free = re.sub(r"`[^`]*`", " ", URL_RE.sub(" ", MDLINK_RE.sub(" ", without_wl)))
    tags = tuple(dict.fromkeys(m.group(1) for m in TAG_RE.finditer(code_free)))
    dates = []
    for m in ISO_DATE_RE.finditer(text):
        v = _valid_date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        if v:
            dates.append(v)
    for m in DMY_DATE_RE.finditer(text):
        v = _valid_date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        if v:
            dates.append(v)
    return InlineInfo(wikilinks, links, urls, tags, tuple(dict.fromkeys(dates)), task)


def _wikilink_text(m: re.Match) -> str:
    if m.group(1):
        return ""
    alias = (m.group(4) or "").strip()
    if alias:
        return alias
    target = m.group(2).strip()
    return target.rsplit("/", 1)[-1]


def to_plain(text: str) -> str:
    """Текст без разметки (вход языкового слоя). Сам источник не меняется."""
    s = TEMPLATER_RE.sub(" ", text)
    s = WIKILINK_RE.sub(_wikilink_text, s)
    s = MDLINK_RE.sub(lambda m: m.group(2), s)
    s = re.sub(r"<(https?://[^>]+)>", r"\1", s)
    s = HTML_TAG_RE.sub(" ", s)
    s = html.unescape(s)
    s = re.sub(r"\[\^[^\]]*\]", "", s)
    s = re.sub(r"(\*\*|__|~~|==)(.+?)\1", r"\2", s)
    s = re.sub(r"(?<![\w*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![\w*])", r"\1", s)
    s = re.sub(r"(?<![\w_])_(?!\s)([^_\n]+?)(?<!\s)_(?![\w_])", r"\1", s)
    s = re.sub(r"`+([^`]*)`+", r"\1", s)
    s = TAG_RE.sub(" ", s)
    s = re.sub(r"\s\^[\w-]+\s*$", "", s)
    return re.sub(r"[ \t]+", " ", s).strip()


# --------------------------------------------------------------------------- block scanner

class _Lines:
    def __init__(self, text: str):
        self.lines = text.split("\n")
        self.starts = []
        pos = 0
        for line in self.lines:
            self.starts.append(pos)
            pos += len(line) + 1

    def __len__(self) -> int:
        return len(self.lines)

    def end(self, i: int) -> int:
        return self.starts[i] + len(self.lines[i])


def _indent(line: str) -> int:
    n = 0
    for ch in line:
        if ch == " ":
            n += 1
        elif ch == "\t":
            n += 4
        else:
            break
    return n


def _is_blank(line: str) -> bool:
    return not line.strip()


def _table_start(lines: list[str], i: int) -> bool:
    return "|" in lines[i] and i + 1 < len(lines) and "-" in lines[i + 1] \
        and bool(TABLE_DELIM_RE.match(lines[i + 1])) and "|" in lines[i + 1] + lines[i]


def _starts_block(lines: list[str], i: int) -> bool:
    line = lines[i]
    return bool(FENCE_RE.match(line) or HEADING_RE.match(line) or HR_RE.match(line)
                or LIST_RE.match(line) or line.lstrip().startswith(">")
                or line.lstrip().startswith("%%") or line.lstrip().startswith("$$")
                or HTML_START_RE.match(line) or _table_start(lines, i))


def scan_blocks(text: str) -> list[Block]:
    L = _Lines(text)
    lines = L.lines
    n = len(lines)
    blocks: list[Block] = []

    def add(btype: str, i0: int, i1: int, depth: int = 0, info: str = "") -> None:
        blocks.append(Block(len(blocks), btype, (L.starts[i0], L.end(i1)), depth, info))

    i = 0
    if n and lines[0].rstrip() == "---":
        for j in range(1, n):
            if lines[j].rstrip() in ("---", "..."):
                add("frontmatter", 0, j)
                i = j + 1
                break
    while i < n:
        line = lines[i]
        if _is_blank(line):
            i += 1
            continue
        stripped = line.lstrip(" \t")
        m = FENCE_RE.match(line)
        if m:
            fence = m.group(1)
            j = i + 1
            while j < n:
                f2 = FENCE_RE.match(lines[j])
                if f2 and f2.group(1)[0] == fence[0] and len(f2.group(1)) >= len(fence) and not f2.group(2).strip():
                    break
                j += 1
            j = min(j, n - 1)
            add("code", i, j, _indent(line), m.group(2).strip()[:40])
            i = j + 1
            continue
        if stripped.startswith("%%"):
            j = i
            if not (len(stripped.rstrip()) > 2 and stripped.rstrip().endswith("%%") and stripped.count("%%") >= 2):
                j = i + 1
                while j < n and "%%" not in lines[j]:
                    j += 1
                j = min(j, n - 1)
            add("comment", i, j)
            i = j + 1
            continue
        if stripped.startswith("$$"):
            j = i
            if not (stripped.rstrip().endswith("$$") and len(stripped.rstrip()) > 2):
                j = i + 1
                while j < n and "$$" not in lines[j]:
                    j += 1
                j = min(j, n - 1)
            add("math", i, j)
            i = j + 1
            continue
        m = HEADING_RE.match(line)
        if m:
            add("heading", i, i, len(m.group(1)))
            i += 1
            continue
        if HR_RE.match(line):
            add("hr", i, i)
            i += 1
            continue
        if _table_start(lines, i):
            j = i + 2
            while j < n and "|" in lines[j] and not _is_blank(lines[j]):
                j += 1
            add("table", i, j - 1, _indent(line))
            i = j
            continue
        if stripped.startswith(">"):
            j = i
            while j + 1 < n and lines[j + 1].lstrip(" \t").startswith(">"):
                j += 1
            cm = CALLOUT_RE.match(line)
            add("callout" if cm else "quote", i, j, 0, cm.group(1).strip().lower() if cm else "")
            i = j + 1
            continue
        if HTML_START_RE.match(line):
            j = i
            if stripped.startswith("<!--"):
                while j < n and "-->" not in lines[j]:
                    j += 1
                j = min(j, n - 1)
            else:
                while j + 1 < n and not _is_blank(lines[j + 1]):
                    j += 1
            add("html", i, j)
            i = j + 1
            continue
        m = LIST_RE.match(line)
        if m:
            marker_indent = _indent(m.group(1))
            rest = line[m.end():]
            tm = TASK_RE.match(rest)
            j = i
            while j + 1 < n:
                nxt = lines[j + 1]
                if _is_blank(nxt) or LIST_RE.match(nxt) or FENCE_RE.match(nxt):
                    break
                if _indent(nxt) <= marker_indent and _starts_block(lines, j + 1):
                    break
                j += 1
            depth = marker_indent // 2
            add("task_item" if tm else "list_item", i, j, depth, tm.group(1) if tm else m.group(2))
            i = j + 1
            continue
        # paragraph (with setext heading detection)
        j = i
        while j + 1 < n and not _is_blank(lines[j + 1]) and not _starts_block(lines, j + 1):
            if SETEXT_RE.match(lines[j + 1]):
                break
            j += 1
        if j + 1 < n and SETEXT_RE.match(lines[j + 1]) and not _is_blank(lines[j + 1]):
            level = 1 if lines[j + 1].strip().startswith("=") else 2
            add("heading", i, j + 1, level, "setext")
            i = j + 2
            continue
        add("paragraph", i, j, _indent(line) // 2)
        i = j + 1
    return blocks


# --------------------------------------------------------------------------- units (leaves)

def _line_ranges(text: str, start: int, end: int) -> list[tuple[int, int]]:
    out = []
    pos = start
    for line in text[start:end].split("\n"):
        out.append((pos, pos + len(line)))
        pos += len(line) + 1
    return out


def _prefix_len(block_type: str, line: str, first: bool) -> int:
    if block_type in ("quote", "callout"):
        m = QUOTE_PREFIX_RE.match(line)
        return m.end() if m else _indent(line)
    if block_type in ("list_item", "task_item") and first:
        m = LIST_RE.match(line)
        end = m.end() if m else 0
        tm = TASK_RE.match(line[end:])
        return end + (tm.end() if tm else 0)
    return len(line) - len(line.lstrip(" \t"))


def _text_units(text: str, block: Block) -> list[tuple[int, int, str, int, int]]:
    """(start, end, role, plain_start, plain_end): лист = предложение; первый на строке — с начала строки."""
    units = []
    for k, (ls, le) in enumerate(_line_ranges(text, *block.span)):
        line = text[ls:le]
        if not line.strip():
            continue
        p = _prefix_len(block.type, line, k == 0)
        sentences = split_line(line[p:], ls + p)
        if not sentences:
            units.append((ls, le, "marker", le, le))
            continue
        for idx, (s, e) in enumerate(sentences):
            unit_start = ls if idx == 0 else s
            unit_end = le if idx == len(sentences) - 1 else e
            units.append((unit_start, unit_end, "sentence", s, e))
    return units


def _strip(text: str, s: int, e: int) -> tuple[int, int]:
    while s < e and text[s].isspace():
        s += 1
    while e > s and text[e - 1].isspace():
        e -= 1
    return s, e


def _split_row(row: str) -> list[str]:
    r = row.strip()
    if r.startswith("|"):
        r = r[1:]
    if r.endswith("|") and not r.endswith("\\|"):
        r = r[:-1]
    cells, buf, esc = [], "", False
    for ch in r:
        if ch == "|" and not esc:
            cells.append(buf.strip())
            buf = ""
        else:
            buf += ch
        esc = ch == "\\"
    cells.append(buf.strip())
    return cells


def parse_document(path: str, text: str) -> ParsedDocument:
    blocks = scan_blocks(text)
    fragments: list[Fragment] = []
    occurrences: Counter = Counter()
    per_block: Counter = Counter()
    heading_stack: list[tuple[int, str]] = []
    frontmatter: dict = {}
    warnings: list[str] = []

    def emit(block: Block, unit_idx: int, role: str, s: int, e: int, plain: str,
             inline: InlineInfo | None = None, meta: tuple = ()) -> None:
        s, e = _strip(text, s, e)
        if s >= e:
            return
        raw = text[s:e]
        key = (block.type, nfc(raw).strip())  # тот же ключ, что и в fragment_id (NFC!)
        occ = occurrences[key]
        occurrences[key] += 1
        per_block[block.index] += 1
        locator = f"{path}::b{block.index + 1}.{per_block[block.index]}"
        fragments.append(Fragment(
            id=fragment_id(path, block.type, raw, occ), doc=path, locator=locator,
            block_index=block.index, block_type=block.type, unit_index=unit_idx, unit_role=role,
            span=(s, e), text=raw, plain=plain, depth=block.depth,
            heading_path=tuple(t for _, t in heading_stack),
            inline=inline if inline is not None else extract_inline(raw), meta=meta))

    for block in blocks:
        bs, be = block.span
        if block.type == "heading":
            title_line = text[bs:be].split("\n")[0]
            title = to_plain(HEADING_RE.sub(r"\2", title_line) if block.info != "setext" else title_line)
            title = re.sub(r"\s#+\s*$", "", title).strip()
            while heading_stack and heading_stack[-1][0] >= block.depth:
                heading_stack.pop()
            emit(block, 0, "whole", bs, be, title)
            heading_stack.append((block.depth, title))
        elif block.type in ("code", "math", "comment", "hr"):
            inner = text[bs:be]
            emit(block, 0, "whole", bs, be, "" if block.type in ("code", "math", "hr") else to_plain(inner.replace("%%", " ")),
                 InlineInfo() if block.type in ("code", "math") else None)
        elif block.type == "frontmatter":
            for k, (ls, le) in enumerate(_line_ranges(text, bs, be)):
                line = text[ls:le]
                if line.strip() in ("---", "..."):
                    emit(block, k, "delimiter", ls, le, "", InlineInfo())
                    continue
                m = re.match(r"^\s*([^:#\s][^:]*):\s*(.*)$", line)
                meta = ()
                if m:
                    frontmatter[m.group(1).strip()] = m.group(2).strip()
                    meta = (("key", m.group(1).strip()), ("value", m.group(2).strip()))
                emit(block, k, "line", ls, le, line.strip(), extract_inline(line), meta)
        elif block.type == "table":
            rows = _line_ranges(text, bs, be)
            header = _split_row(text[rows[0][0]:rows[0][1]]) if rows else []
            for k, (ls, le) in enumerate(rows):
                row = text[ls:le]
                if k == 1 and TABLE_DELIM_RE.match(row):
                    emit(block, k, "delimiter", ls, le, "", InlineInfo())
                    continue
                cells = _split_row(row)
                meta = (("row", k), ("cells", tuple(cells)), ("header", tuple(header)))
                emit(block, k, "row", ls, le, " | ".join(to_plain(c) for c in cells), extract_inline(row), meta)
        elif block.type == "html":
            for k, (ls, le) in enumerate(_line_ranges(text, bs, be)):
                line = text[ls:le]
                if line.strip():
                    emit(block, k, "line", ls, le, to_plain(line))
        else:  # paragraph, list_item, task_item, quote, callout
            task = block.info if block.type == "task_item" else None
            if block.type == "task_item":
                content_start = bs + _prefix_len(block.type, text[bs:be].split("\n")[0], True)
                emit(block, 0, "whole", bs, be, to_plain(text[content_start:be]),
                     extract_inline(text[bs:be], task))
                continue
            for k, (s, e, role, ps, pe) in enumerate(_text_units(text, block)):
                emit(block, k, role, s, e, to_plain(text[ps:pe]) if role == "sentence" else "")
    return ParsedDocument(path, text, blocks, fragments, frontmatter, warnings)


def parse_plain_text(path: str, text: str) -> ParsedDocument:
    """Простой текст (.txt/.org): абзацы через пустые строки, листы — предложения."""
    L = _Lines(text)
    blocks: list[Block] = []
    i, n = 0, len(L)
    while i < n:
        if _is_blank(L.lines[i]):
            i += 1
            continue
        j = i
        while j + 1 < n and not _is_blank(L.lines[j + 1]):
            j += 1
        blocks.append(Block(len(blocks), "paragraph", (L.starts[i], L.end(j))))
        i = j + 1
    fragments = []
    occurrences: Counter = Counter()
    for block in blocks:
        for k, (s, e, role, ps, pe) in enumerate(_text_units(text, block)):
            s, e = _strip(text, s, e)
            if s >= e:
                continue
            raw = text[s:e]
            key = nfc(raw).strip()
            occ = occurrences[key]
            occurrences[key] += 1
            fragments.append(Fragment(fragment_id(path, "paragraph", raw, occ), path,
                                      f"{path}::b{block.index + 1}.{k + 1}", block.index, "paragraph", k,
                                      role, (s, e), raw, to_plain(text[ps:pe]) if role == "sentence" else "",
                                      inline=extract_inline(raw)))
    return ParsedDocument(path, text, blocks, fragments, {}, [])


def parse_code_file(path: str, text: str) -> ParsedDocument:
    """Код и прочие текстовые файлы: один лист CODE на весь файл (сохранён, не знание)."""
    stripped = text.strip()
    if not stripped:
        return ParsedDocument(path, text, [], [], {}, [])
    s = text.index(stripped[0])
    e = len(text.rstrip())
    block = Block(0, "code_file", (s, e))
    frag = Fragment(fragment_id(path, "code_file", text[s:e], 0), path, f"{path}::b1.1", 0, "code_file",
                    0, "whole", (s, e), text[s:e], "", inline=InlineInfo())
    return ParsedDocument(path, text, [block], [frag], {}, [])
