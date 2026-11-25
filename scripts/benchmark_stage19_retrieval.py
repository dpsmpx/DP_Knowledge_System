#!/usr/bin/env python3
"""Offline, synthetic 20k FTS4 evidence-search benchmark for Stage 19.

Compares the original path-ordered OR shortlist with the bounded two-pass
all-term-first/OR-fallback shortlist and title-aware three-pass search.
Not an Android, model, embedding, RRF, or semantic-grounding benchmark.
No user files, secrets, network or disk DB.
"""

import argparse
import json
import sqlite3
import statistics
import time


TOPICS = (
    ("amber", "violet"),
    ("cobalt", "willow"),
    ("citrine", "juniper"),
    ("saffron", "otter"),
    ("память", "синтез"),
)
NATURAL_QUESTIONS = (
    "Tell me about amber violet",
    "What do we know about cobalt willow?",
    "Please explain citrine juniper",
    "Describe saffron otter",
    "Расскажи, что известно про память синтез?",
)
GENERATION = "synthetic-stage19"
MAX_EVIDENCE_QUESTION_CHARS = 1200
QUESTION_FILLERS = frozenset((
    'what', 'which', 'where', 'when', 'whose', 'please', 'could', 'would', 'should',
    'tell', 'about', 'know', 'explain', 'describe',
    'какие', 'какой', 'какая', 'какое', 'когда', 'почему', 'зачем', 'сколько',
    'такое', 'каковы',
    'расскажи', 'расскажите', 'известно', 'объясни', 'объясните', 'опиши', 'опишите',
))


def fts_evidence_terms(question: str) -> list[str]:
    """ASCII/Cyrillic fixture equivalent of KnowledgeMarkdownMetadata.ftsEvidenceTerms."""
    if not question or len(question) > MAX_EVIDENCE_QUESTION_CHARS:
        return []
    terms = []
    word = ""
    for char in question + " ":
        if char.isalnum():
            word += char.lower()
        elif word:
            if 4 <= len(word) <= 48 and word not in terms:
                terms.append(word)
            word = ""
    informative = [term for term in terms if term not in QUESTION_FILLERS]
    chosen = informative if informative else terms
    return chosen[:8]


def fts_evidence_query(question: str) -> str:
    """Bounded escaped-prefix OR query equivalent to the production Java helper."""
    return " OR ".join(f'"{term}*"' for term in fts_evidence_terms(question))


def fts_evidence_phrase(question: str) -> str:
    """Short adjacent FTS4 phrase, constructed only from sanitized tokens."""
    terms = fts_evidence_query(question).split(' OR ')
    if not 2 <= len(terms) <= 4:
        return ''
    return '"' + ' '.join(term[1:-1] for term in terms) + '"'


def title_evidence_term_matches(title: str, terms: list[str]) -> int:
    """Distinct query prefixes at complete title-word boundaries, capped at 256 chars."""
    if not title or not terms:
        return 0
    seen = set()
    word = ''
    end = min(len(title), 256)
    for offset in range(end + 1):
        char = title[offset] if offset < end else ' '
        if offset < end and char.isalnum():
            word += char.lower()
        elif word:
            if offset < end or end == len(title) or not title[end].isalnum():
                matches = (term for term in terms if term not in seen and
                           word.startswith(term))
                best = max(matches, key=len, default=None)
                if best is not None:
                    seen.add(best)
            word = ''
    return len(seen)


def build_synthetic_index(connection: sqlite3.Connection, notes: int) -> list[set[str]]:
    """Inject known relevant documents late in an otherwise ordinary FTS4 vault."""
    connection.executescript("""
        CREATE TABLE notes (
            id INTEGER PRIMARY KEY, path TEXT NOT NULL, title TEXT NOT NULL,
            generation TEXT NOT NULL, oversized INTEGER NOT NULL
        );
        CREATE VIRTUAL TABLE documents_fts USING fts4(
            path, title, body, tokenize=unicode61
        );
    """)
    expected = [set() for _ in TOPICS]
    gold = {
        min(notes - 1, (notes * 3 // 4) + topic * 8 + offset): (topic, offset)
        for topic in range(len(TOPICS))
        for offset in range(4)
    }
    with connection:
        for number in range(notes):
            path = f"group-{number // 1000:03d}/synthetic-{number:06d}.md"
            title = f"Synthetic example {number}"
            body = "Fake test text for a safe offline retrieval experiment."
            for topic, (common, _) in enumerate(TOPICS):
                if number % 47 == topic:
                    body += " " + common
            # Early files with BOTH words but deliberately nonadjacent crowd
            # the old alphabetically ordered all-term pass at 512+ files.
            for topic, (common, second) in enumerate(TOPICS):
                if number < notes // 2 and number % 23 == topic:
                    body += f" {common} synthetic-distance {second}"
            if number in gold:
                topic, offset = gold[number]
                body += " " + " ".join(TOPICS[topic])
                # Opposite synthetic statements must BOTH remain retrievable;
                # FTS4 does not judge which one is true.
                body += " supports claim" if offset % 2 == 0 else " refutes claim"
                expected[topic].add(path)
            docid = number + 1
            connection.execute("INSERT INTO notes VALUES (?,?,?,?,0)",
                               (docid, path, title, GENERATION))
            connection.execute("INSERT INTO documents_fts"
                               "(docid,path,title,body) VALUES (?,?,?,?)",
                               (docid, path, title, body))
    return expected


def _fts4_paths(connection: sqlite3.Connection, query: str, limit: int) -> list[str]:
    """Exact SQL predicate and stable pathname tie-breaker of the Java index."""
    return [row[0] for row in connection.execute("""
        SELECT notes.path FROM documents_fts
        JOIN notes ON notes.id=documents_fts.docid
        WHERE documents_fts MATCH ? AND notes.oversized=0
        AND notes.generation=?
        ORDER BY notes.path COLLATE NOCASE, notes.path COLLATE BINARY LIMIT ?
        """, (query, GENERATION, limit))]


def _fts4_title_ranked_paths(
    connection: sqlite3.Connection, query: str, limit: int, terms: list[str]
) -> list[str]:
    """Bounded metadata-only rerank, stable SQL path order for equal scores."""
    rows = list(connection.execute("""
        SELECT notes.path, notes.title FROM documents_fts
        JOIN notes ON notes.id=documents_fts.docid
        WHERE documents_fts MATCH ? AND notes.oversized=0
        AND notes.generation=?
        ORDER BY notes.path COLLATE NOCASE, notes.path COLLATE BINARY LIMIT ?
        """, (query, GENERATION, limit)))
    rows.sort(key=lambda row: -title_evidence_term_matches(row[1], terms))
    return [path for path, _ in rows]


def search_path_order_or_baseline(
    connection: sqlite3.Connection, question: str, limit: int
) -> list[str]:
    """Previous single-pass retrieval, retained only as a comparison."""
    return _fts4_paths(connection, fts_evidence_query(question), limit)


def search_two_pass_baseline(
    connection: sqlite3.Connection, question: str, limit: int
) -> list[str]:
    """The previous all-term-first then OR fallback algorithm."""
    safe = fts_evidence_query(question)
    if not safe:
        return []
    # Safe generated syntax only; never transform raw user FTS4 expressions.
    first = _fts4_paths(connection, safe.replace(" OR ", " "), limit)
    if len(first) == limit or " OR " not in safe:
        return first
    seen = set(first)
    for path in _fts4_paths(connection, safe, limit * 2):
        if path not in seen:
            first.append(path)
            seen.add(path)
            if len(first) == limit:
                break
    return first


def search_production_lexical_mirror(
    connection: sqlite3.Connection, question: str, limit: int
) -> list[str]:
    """Three bounded FTS4 passes with within-pass title rerank and dedup."""
    safe = fts_evidence_query(question)
    if not safe:
        return []
    phrase = fts_evidence_phrase(question)
    terms = fts_evidence_terms(question)
    first = _fts4_title_ranked_paths(connection, phrase, limit * 2, terms)[:limit] if phrase else []
    seen = set(first)
    if len(first) < limit:
        for path in _fts4_title_ranked_paths(
                connection, safe.replace(' OR ', ' '), limit * 2, terms):
            if path not in seen:
                first.append(path)
                seen.add(path)
                if len(first) == limit:
                    break
    if len(first) < limit and ' OR ' in safe:
        for path in _fts4_title_ranked_paths(connection, safe, limit * 2, terms):
            if path not in seen:
                first.append(path)
                seen.add(path)
                if len(first) == limit:
                    break
    return first


def synthetic_title_promotion_probe() -> dict:
    """Small adversarial fixture with a title-relevant hit inside the scan cap."""
    with sqlite3.connect(':memory:') as connection:
        connection.executescript("""
            CREATE TABLE notes (id INTEGER PRIMARY KEY, path TEXT, title TEXT,
                                generation TEXT, oversized INTEGER);
            CREATE VIRTUAL TABLE documents_fts USING fts4(
                path,title,body,tokenize=unicode61);
        """)
        for number in range(8):
            path = f'a-{number:02d}.md'
            title = 'Parcel notes on neon' if number == 7 else 'Generic'
            body = 'neon synthetic-distance parcel'
            connection.execute('INSERT INTO notes VALUES (?,?,?,?,0)',
                               (number + 1, path, title, GENERATION))
            connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                               ' VALUES (?,?,?,?)', (number + 1, path, title, body))
        for ident, path, body in (
                (9, 'y-support.md', 'neon parcel supports a synthetic claim'),
                (10, 'z-refute.md', 'neon parcel refutes a synthetic claim')):
            connection.execute('INSERT INTO notes VALUES (?,?,?,?,0)',
                               (ident, path, 'Generic', GENERATION))
            connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                               ' VALUES (?,?,?,?)', (ident, path, 'Generic', body))
        original = search_two_pass_baseline(connection, 'neon parcel', 4)
        updated = search_production_lexical_mirror(connection, 'neon parcel', 4)
        return {
            'notes': 10,
            'candidate_scan_cap_per_pass': 8,
            'previous_two_pass_missed_titled_source': 'a-07.md' not in original,
            'title_promoted_to_rank_3': updated[2] == 'a-07.md',
            'opposing_phrase_sources_still_first': updated[:2] == [
                'y-support.md', 'z-refute.md'],
        }


def synthetic_diverse_query_probe() -> dict:
    """Small independent labeled fixture: realistic failure *types*, artificial text.

    Reports no note content or paths. The manually assigned labels test only
    what the lexical SQL mirror can retrieve, not semantic entailment or a
    representative dataset sampled from a real vault.
    """
    documents = (
        ('a-overlap.md', 'Generic', 'Amberlight violet were written together.'),
        ('b-separate.md', 'Generic', 'Amber appears far from the violet example.'),
        ('c-support.md', 'Generic', 'Amber violet supports an artificial premise.'),
        ('d-refute.md', 'Generic', 'Amber violet refutes an artificial premise.'),
        ('e-title.md', 'Cobalt willow overview', 'Unrelated generic discussion.'),
        ('f-title-decoy.md', 'Generic', 'Cobalt separately discusses willow.'),
        ('g-ru.md', 'Generic', 'Памятью синтезом описывается вымышленный тест.'),
        ('h-ru-decoy.md', 'Generic', 'Памятью отдельно; синтезом отдельно.'),
        ('i-five.md', 'Generic', 'Citrine juniper saffron otter acacia appear together.'),
        ('j-partial.md', 'Generic', 'Citrine juniper saffron appear here.'),
        ('k-synonym.md', 'Furniture', 'A couch is placed near the desk.'),
        ('l-unrelated.md', 'Generic', 'Unrelated imaginary field notes.'),
        ('m-prefix.md', 'Generic', 'Amber amberlight are two separate tokens.'),
        ('n-prefix-decoy.md', 'Generic', 'Amberlight occurs in isolation.'),
    )
    cases = (
        ('english_phrase', 'amber violet', {'c-support.md', 'd-refute.md'}),
        ('natural_question', 'Tell me about amber violet',
         {'c-support.md', 'd-refute.md'}),
        ('russian_inflection', 'Расскажи про память синтез', {'g-ru.md'}),
        ('title_metadata', 'cobalt willow', {'e-title.md'}),
        ('five_term_query', 'citrine juniper saffron otter acacia', {'i-five.md'}),
        ('overlapping_prefixes', 'amber amberlight', {'m-prefix.md'}),
        ('synonym_without_expansion', 'sofa', {'k-synonym.md'}),
        ('no_relevant_document', 'phosphor titanium', set()),
    )
    with sqlite3.connect(':memory:') as connection:
        connection.executescript("""
            CREATE TABLE notes (id INTEGER PRIMARY KEY, path TEXT, title TEXT,
                                generation TEXT, oversized INTEGER);
            CREATE VIRTUAL TABLE documents_fts USING fts4(
                path,title,body,tokenize=unicode61);
        """)
        for ident, (path, title, body) in enumerate(documents, start=1):
            connection.execute('INSERT INTO notes VALUES (?,?,?,?,0)',
                               (ident, path, title, GENERATION))
            connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                               ' VALUES (?,?,?,?)', (ident, path, title, body))
        universe = {row[0] for row in documents}
        output = []
        for label, question, relevant in cases:
            if not relevant.issubset(universe):
                raise AssertionError('Gold label has no corresponding fixture source')
            found = search_production_lexical_mirror(connection, question, 6)
            recovered = len(relevant.intersection(found))
            output.append({
                'case': label,
                'known_relevant': len(relevant),
                'retrieved_at_6': len(found),
                'true_positive_at_6': recovered,
                'false_positive_at_6': len(found) - recovered,
                'precision_at_6': round(recovered / 6, 4),
                # No-ground-truth-positive is not 'perfect recall': undefined.
                'recall_at_6': round(recovered / len(relevant), 4) if relevant else None,
            })
        return {'synthetic_notes': len(documents), 'cases': output}


def benchmark(notes: int = 20_000, repeats: int = 5) -> dict:
    if not 512 <= notes <= 20_000 or not 1 <= repeats <= 30:
        raise ValueError("notes must be 512..20000 and repeats 1..30")
    with sqlite3.connect(":memory:") as connection:
        start = time.perf_counter()
        expected = build_synthetic_index(connection, notes)
        index_ms = (time.perf_counter() - start) * 1000
        queries = []
        framed_queries = []
        latency_ms = []
        for topic, words in enumerate(TOPICS):
            question = " ".join(words)
            baseline = search_path_order_or_baseline(connection, question, 24)
            previous_two_pass = search_two_pass_baseline(connection, question, 24)
            runs = []
            for _ in range(repeats):
                start = time.perf_counter()
                rows = search_production_lexical_mirror(connection, question, 24)
                latency_ms.append((time.perf_counter() - start) * 1000)
                runs.append(rows)
            if any(rows != runs[0] for rows in runs[1:]):
                raise AssertionError("Non-deterministic three-pass FTS4 search")
            shortlist = runs[0]
            relevant = expected[topic]
            top_six = shortlist[:6]
            queries.append({
                "synthetic_query": question,
                "known_relevant": len(relevant),
                "retrieved_24": len(shortlist),
                "baseline_or_path_recall_at_24": round(
                    len(relevant.intersection(baseline)) / len(relevant), 4),
                "baseline_two_pass_recall_at_24": round(
                    len(relevant.intersection(previous_two_pass)) / len(relevant), 4),
                "precision_at_6": round(len(relevant.intersection(top_six)) / 6, 4),
                "recall_at_6": round(len(relevant.intersection(top_six)) / len(relevant), 4),
                "recall_at_24": round(len(relevant.intersection(shortlist)) / len(relevant), 4),
            })
            framed_question = NATURAL_QUESTIONS[topic]
            framed = search_production_lexical_mirror(
                connection, framed_question, 24)
            framed_queries.append({
                "synthetic_question": framed_question,
                "same_shortlist_as_bare_terms": framed == shortlist,
                "precision_at_6": round(
                    len(relevant.intersection(framed[:6])) / 6, 4),
                "recall_at_6": round(
                    len(relevant.intersection(framed[:6])) / len(relevant), 4),
                "recall_at_24": round(
                    len(relevant.intersection(framed)) / len(relevant), 4),
            })
        timings = sorted(latency_ms)
        p95 = timings[max(0, (95 * len(timings) + 99) // 100 - 1)]
        return {
            "mode": "synthetic_fts4_title_ranked_three_pass_not_android_or_hybrid",
            "sqlite_version": sqlite3.sqlite_version,
            "synthetic_notes": notes,
            "repetitions_per_query": repeats,
            "index_build_ms": round(index_ms, 2),
            "query_p50_ms": round(statistics.median(timings), 3),
            "query_p95_ms": round(p95, 3),
            "title_promotion_probe": synthetic_title_promotion_probe(),
            "diverse_query_probe": synthetic_diverse_query_probe(),
            "queries": queries,
            "natural_question_cases": framed_queries,
            "limitations": [
                "synthetic adversarial corpus, not representative relevance labels",
                "small extra manually labeled query types are also artificial, not human-evaluated",
                "limited English/Russian question prefixes, not language understanding",
                "synthetic opposing claims are retrieved, not semantically reconciled",
                "no synonym expansion or natural-language entailment",
                "SQLite FTS4 SQL mirror, not device or app I/O",
                "metadata title tie-break only inside a bounded path-first window",
                "no embeddings, semantic model, RRF, physical RAM or battery measurement",
                "no proof of LLM citation grounding or safety",
            ],
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notes", type=int, default=20_000)
    parser.add_argument("--repeats", type=int, default=5)
    args = parser.parse_args()
    print(json.dumps(benchmark(args.notes, args.repeats), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
