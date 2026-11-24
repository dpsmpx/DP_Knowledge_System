#!/usr/bin/env python3
"""Offline, synthetic 20k FTS4 evidence-search benchmark for Stage 19.

Compares the original path-ordered OR shortlist with the bounded two-pass
all-term-first/OR-fallback shortlist. Not an Android, model, embedding, RRF,
or semantic-grounding benchmark. No user files, secrets, network or disk DB.
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
)
GENERATION = "synthetic-stage19"


def fts_evidence_query(question: str) -> str:
    """ASCII test-case equivalent of KnowledgeMarkdownMetadata.ftsEvidenceQuery."""
    terms = []
    word = ""
    for char in question + " ":
        if char.isalnum():
            word += char.lower()
        elif word:
            if 4 <= len(word) <= 48 and word not in terms:
                terms.append(word)
            word = ""
    return " OR ".join(f'"{term}*"' for term in terms[:8])


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
        min(notes - 1, (notes * 3 // 4) + topic * 8 + offset): topic
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
            if number in gold:
                topic = gold[number]
                body += " " + " ".join(TOPICS[topic])
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


def search_path_order_or_baseline(
    connection: sqlite3.Connection, question: str, limit: int
) -> list[str]:
    """Previous single-pass retrieval, retained only as a comparison."""
    return _fts4_paths(connection, fts_evidence_query(question), limit)


def search_production_lexical_mirror(
    connection: sqlite3.Connection, question: str, limit: int
) -> list[str]:
    """Two bounded FTS4 passes, same order and deduplication as Java."""
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


def benchmark(notes: int = 20_000, repeats: int = 5) -> dict:
    if not 512 <= notes <= 20_000 or not 1 <= repeats <= 30:
        raise ValueError("notes must be 512..20000 and repeats 1..30")
    with sqlite3.connect(":memory:") as connection:
        start = time.perf_counter()
        expected = build_synthetic_index(connection, notes)
        index_ms = (time.perf_counter() - start) * 1000
        queries = []
        latency_ms = []
        for topic, words in enumerate(TOPICS):
            question = " ".join(words)
            baseline = search_path_order_or_baseline(connection, question, 24)
            runs = []
            for _ in range(repeats):
                start = time.perf_counter()
                rows = search_production_lexical_mirror(connection, question, 24)
                latency_ms.append((time.perf_counter() - start) * 1000)
                runs.append(rows)
            if any(rows != runs[0] for rows in runs[1:]):
                raise AssertionError("Non-deterministic two-pass FTS4 search")
            shortlist = runs[0]
            relevant = expected[topic]
            top_six = shortlist[:6]
            queries.append({
                "synthetic_query": question,
                "known_relevant": len(relevant),
                "retrieved_24": len(shortlist),
                "baseline_or_path_recall_at_24": round(
                    len(relevant.intersection(baseline)) / len(relevant), 4),
                "precision_at_6": round(len(relevant.intersection(top_six)) / 6, 4),
                "recall_at_6": round(len(relevant.intersection(top_six)) / len(relevant), 4),
                "recall_at_24": round(len(relevant.intersection(shortlist)) / len(relevant), 4),
            })
        timings = sorted(latency_ms)
        p95 = timings[max(0, (95 * len(timings) + 99) // 100 - 1)]
        return {
            "mode": "synthetic_fts4_two_pass_vs_path_baseline_not_android_or_hybrid",
            "sqlite_version": sqlite3.sqlite_version,
            "synthetic_notes": notes,
            "repetitions_per_query": repeats,
            "index_build_ms": round(index_ms, 2),
            "query_p50_ms": round(statistics.median(timings), 3),
            "query_p95_ms": round(p95, 3),
            "queries": queries,
            "limitations": [
                "synthetic adversarial corpus, not representative relevance labels",
                "SQLite FTS4 SQL mirror, not device or app I/O",
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
