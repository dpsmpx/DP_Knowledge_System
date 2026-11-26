#!/usr/bin/env python3
"""Reproducible FTS4 + synthetic LSH/cosine + RRF benchmark (no real model).

Mirrors the bounded Android candidate SQL and Java LSH/RRF arithmetic on a
disposable in-memory 512..20k-note index. Hand-assigned one-hot vectors are
*labels for plumbing*, not learned embeddings or evidence of semantic quality.
No user vault, network, provider, app database or filesystem writes.
"""

import argparse
import json
import math
import sqlite3
import statistics
import struct
import time

if __package__:
    from .benchmark_stage19_retrieval import (
        GENERATION, TOPICS, build_synthetic_index, search_production_lexical_mirror,
    )
else:
    from benchmark_stage19_retrieval import (
        GENERATION, TOPICS, build_synthetic_index, search_production_lexical_mirror,
    )


DIMENSIONS = 8
LSH_TABLES = 4
LSH_BITS = 10
LSH_SAMPLES = 24
MAX_SEMANTIC_CANDIDATES = 45_000
RRF_K = 60
MODEL_KEY = 'synthetic-eight-dimensional-not-provider'
SYNONYM_CASE = 'synonym_only_sofa_to_couch'
NEGATIVE_CASE = 'no_relevant_phosphor_titanium'


def _mix(value: int) -> int:
    """Java KnowledgeEmbeddingLsh.mix, with explicit 32-bit wraparound."""
    value &= 0xffffffff
    value ^= value >> 16
    value = (value * 0x7feb352d) & 0xffffffff
    value ^= value >> 15
    value = (value * 0x846ca68b) & 0xffffffff
    return (value ^ (value >> 16)) & 0xffffffff


def lsh_buckets(vector: tuple[float, ...]) -> tuple[int, ...]:
    if len(vector) != DIMENSIONS:
        raise ValueError('The fixture uses eight-dimensional normalized vectors')
    result = []
    for table in range(LSH_TABLES):
        signature = 0
        for bit in range(LSH_BITS):
            projection = 0.0
            for sample in range(LSH_SAMPLES):
                hashed = _mix((0x9e3779b9 * (table + 1)) ^
                              (0x85ebca6b * (bit + 1)) ^
                              (0xc2b2ae35 * (sample + 1)))
                coordinate = (hashed & 0x7fffffff) % DIMENSIONS
                projection += (-1 if hashed & 0x80000000 else 1) * vector[coordinate]
            if projection >= 0:
                signature |= 1 << bit
        result.append(table * (1 << LSH_BITS) + signature)
    return tuple(result)


def lsh_probes(vector: tuple[float, ...]) -> tuple[int, ...]:
    result = []
    for bucket in lsh_buckets(vector):
        base = bucket & ~((1 << LSH_BITS) - 1)
        code = bucket & ((1 << LSH_BITS) - 1)
        result.append(bucket)
        result.extend(base + (code ^ (1 << bit)) for bit in range(LSH_BITS))
    return tuple(result)


def basis(coordinate: int) -> tuple[float, ...]:
    return tuple(1.0 if i == coordinate else 0.0 for i in range(DIMENSIONS))


def _path(number: int) -> str:
    return f'group-{number // 1000:03d}/synthetic-{number:06d}.md'


def build_synthetic_embeddings(connection: sqlite3.Connection, notes: int,
                               labels: list[set[str]]) -> dict[str, set[str]]:
    """Assign known positive and negative artificial vectors, never model output."""
    directions = {}
    for topic, relevant in enumerate(labels):
        for path in relevant:
            directions[path] = topic
        for offset in range(2):
            directions[_path(16 + topic * 4 + offset)] = topic  # false positives
    synonym_positive = _path(120)
    directions[synonym_positive] = 5
    for number in (121, 122):
        directions[_path(number)] = 5  # vector-only distractors
    for number in (123, 124):
        directions[_path(number)] = 6  # no-gold query false positives
    connection.execute("UPDATE documents_fts SET body=body || ' A couch near a desk.' "
                       'WHERE docid=121')
    connection.executescript('''
        CREATE TABLE embedding_notes (
            path TEXT PRIMARY KEY, model_key TEXT NOT NULL,
            dimension INTEGER NOT NULL, chunks INTEGER NOT NULL,
            completed INTEGER NOT NULL
        );
        CREATE TABLE embedding_chunks (
            path TEXT NOT NULL, chunk INTEGER NOT NULL, vector BLOB NOT NULL,
            PRIMARY KEY(path,chunk)
        );
        CREATE TABLE embedding_lsh (
            bucket INTEGER NOT NULL, path TEXT NOT NULL, chunk INTEGER NOT NULL,
            PRIMARY KEY(bucket,path,chunk)
        );
    ''')
    encoded = [struct.pack('<8f', *basis(i)) for i in range(DIMENSIONS)]
    buckets = [lsh_buckets(basis(i)) for i in range(DIMENSIONS)]
    with connection:
        for number in range(notes):
            path = _path(number)
            direction = directions.get(path, 7)
            connection.execute('INSERT INTO embedding_notes VALUES (?,?,?,?,?)',
                               (path, MODEL_KEY, DIMENSIONS, 1, 1))
            connection.execute('INSERT INTO embedding_chunks VALUES (?,?,?)',
                               (path, 0, encoded[direction]))
            connection.executemany('INSERT INTO embedding_lsh VALUES (?,?,?)',
                                   ((bucket, path, 0) for bucket in buckets[direction]))
    return {'synonym': {synonym_positive}, 'negative': set()}


def search_semantic_mirror(connection: sqlite3.Connection,
                           query: tuple[float, ...], limit: int = 24,
                           candidate_cap: int = MAX_SEMANTIC_CANDIDATES) -> list[str]:
    """Same 44 LSH probes, 45k candidate cap, cosine and path ties as Android.

    Host schema omits actual note byte seals: Android checks these separately.
    Multiple chunks are deduplicated using each path's best cosine score.
    A larger diagnostic candidate_cap may be used only by the cap probe;
    the benchmark itself uses the production 45k cap.
    """
    if not 1 <= limit <= 40:
        raise ValueError('Semantic limit must be 1..40')
    if not 1 <= candidate_cap <= 60_000:
        raise ValueError('Synthetic diagnostic cap must be 1..60000')
    probes = lsh_probes(query)
    rows = connection.execute('''
        SELECT e.path, v.vector FROM embedding_lsh l
        JOIN embedding_chunks v ON v.path=l.path AND v.chunk=l.chunk
        JOIN embedding_notes e ON e.path=v.path
        JOIN notes n ON n.path=e.path
        WHERE l.bucket IN (''' + ','.join('?' for _ in probes) + ''')
          AND e.model_key=? AND e.dimension=? AND e.completed=e.chunks
          AND n.generation=? AND n.oversized=0
        GROUP BY l.path,l.chunk LIMIT ?
    ''', (*probes, MODEL_KEY, DIMENSIONS, GENERATION, candidate_cap))
    best = {}
    for path, raw in rows:
        vector = struct.unpack('<8f', raw)
        score = sum(a * b for a, b in zip(query, vector))
        if path not in best or score > best[path]:
            best[path] = score
    return sorted(best, key=lambda path: (-best[path], path))[:limit]


def synthetic_multichunk_cap_probe() -> dict:
    """Expose the source-diversity loss from a chunk-level 45k SQL limit.

    360 small *artificial* note rows each have 128 vector chunks. All notes
    have a near-matching chunk; the final note has the one exact match at
    chunk 3. No Markdown files are generated and no user index is opened.
    """
    notes, chunks_per_note = 360, 128
    near = (0.9, math.sqrt(1.0 - 0.9 ** 2), 0., 0., 0., 0., 0., 0.)
    exact = basis(0)
    packed_near = struct.pack('<8f', *near)
    packed_exact = struct.pack('<8f', *exact)
    near_buckets = lsh_buckets(near)
    exact_buckets = lsh_buckets(exact)
    target = 'z-late-target.md'
    with sqlite3.connect(':memory:') as connection:
        connection.executescript('''
            CREATE TABLE notes(path TEXT PRIMARY KEY, generation TEXT, oversized INTEGER);
            CREATE TABLE embedding_notes(path TEXT PRIMARY KEY, model_key TEXT,
                                         dimension INTEGER, chunks INTEGER, completed INTEGER);
            CREATE TABLE embedding_chunks(path TEXT, chunk INTEGER, vector BLOB,
                                          PRIMARY KEY(path,chunk));
            CREATE TABLE embedding_lsh(bucket INTEGER, path TEXT, chunk INTEGER,
                                       PRIMARY KEY(bucket,path,chunk));
        ''')
        with connection:
            for number in range(notes):
                path = target if number == notes - 1 else f'a-{number:04d}.md'
                connection.execute('INSERT INTO notes VALUES (?,?,0)', (path, GENERATION))
                connection.execute('INSERT INTO embedding_notes VALUES (?,?,?,?,?)',
                                   (path, MODEL_KEY, DIMENSIONS,
                                    chunks_per_note, chunks_per_note))
                connection.executemany('INSERT INTO embedding_chunks VALUES (?,?,?)',
                                       ((path, chunk, packed_exact if path == target and chunk == 3
                                         else packed_near)
                                        for chunk in range(chunks_per_note)))
                connection.executemany('INSERT INTO embedding_lsh VALUES (?,?,?)',
                                       ((bucket, path, chunk)
                                        for chunk in range(chunks_per_note)
                                        for bucket in (exact_buckets if path == target and chunk == 3
                                                       else near_buckets)))
        limited = search_semantic_mirror(connection, exact, 6)
        unlimited_diagnostic = search_semantic_mirror(connection, exact, 6,
                                                     notes * chunks_per_note)
        return {
            'synthetic_sources': notes,
            'chunks_per_source': chunks_per_note,
            'total_chunks': notes * chunks_per_note,
            'production_candidate_cap': MAX_SEMANTIC_CANDIDATES,
            'exact_target_retrieved_at_6_with_cap': target in limited,
            'exact_target_retrieved_at_6_without_cap': target in unlimited_diagnostic,
            'warning': 'candidate GROUP BY/LIMIT has no guaranteed order; '
                       'observed outcome applies only to this SQLite build',
        }


def synthetic_angular_noise_probe() -> list[dict]:
    """Deterministic LSH collision visibility as an artificial vector rotates.

    Candidate membership is discrete and can vanish before cosine is small.
    These are manually chosen directions, never actual noisy model outputs.
    """
    query = basis(0)
    probes = set(lsh_probes(query))
    result = []
    for cosine in (1.0, 0.99, 0.95, 0.90, 0.80):
        vector = (cosine, math.sqrt(1.0 - cosine * cosine), 0., 0., 0., 0., 0., 0.)
        overlap = len(probes.intersection(lsh_buckets(vector)))
        result.append({
            'assigned_cosine': cosine,
            'tables_reaching_query_probes': overlap,
            'candidate_reachable': overlap > 0,
        })
    return result


def rrf_combine(lexical: list[str], semantic: list[str], limit: int = 24) -> list[str]:
    """Unique-source reciprocal rank and stable path ties of production Java."""
    if not 1 <= limit <= 24 or len(lexical) > 40 or len(semantic) > 40:
        raise ValueError('RRF input/output exceeds production limits')
    scores = {}
    for rows in (lexical, semantic):
        seen = set()
        for path in rows:
            if not path or path in seen:
                continue
            seen.add(path)
            scores[path] = scores.get(path, 0.0) + 1 / (RRF_K + len(seen))
    return sorted(scores, key=lambda path: (-scores[path], path))[:limit]


def _quality(ranked: list[str], gold: set[str], at: int = 6) -> dict:
    top = ranked[:at]
    recovered = len(gold.intersection(top))
    return {
        'retrieved_at_6': len(top),
        'true_positive_at_6': recovered,
        'false_positive_at_6': len(top) - recovered,
        'precision_at_6': round(recovered / at, 4),
        'recall_at_6': round(recovered / len(gold), 4) if gold else None,
    }


def _timing_ms(values: list[float]) -> dict:
    ordered = sorted(values)
    return {
        'p50_ms': round(statistics.median(ordered), 3),
        'p95_ms': round(ordered[(95 * len(ordered) + 99) // 100 - 1], 3),
    }


def benchmark(notes: int = 20_000, repeats: int = 5) -> dict:
    if not 512 <= notes <= 20_000 or not 1 <= repeats <= 30:
        raise ValueError('notes must be 512..20000 and repeats 1..30')
    with sqlite3.connect(':memory:') as connection:
        start = time.perf_counter()
        topic_labels = build_synthetic_index(connection, notes)
        more_labels = build_synthetic_embeddings(connection, notes, topic_labels)
        index_ms = (time.perf_counter() - start) * 1000
        queries = [
            (f'artificial_topic_{i}', ' '.join(words), basis(i), topic_labels[i])
            for i, words in enumerate(TOPICS)
        ]
        queries.extend((
            (SYNONYM_CASE, 'sofa', basis(5), more_labels['synonym']),
            (NEGATIVE_CASE, 'phosphor titanium', basis(6), more_labels['negative']),
        ))
        lexical_times, semantic_times, fusion_times = [], [], []
        output = []
        for name, question, vector, gold in queries:
            for attempt in range(-2, repeats):
                start = time.perf_counter()
                lexical = search_production_lexical_mirror(connection, question, 24)
                lexical_ms = (time.perf_counter() - start) * 1000
                start = time.perf_counter()
                semantic = search_semantic_mirror(connection, vector, 24)
                semantic_ms = (time.perf_counter() - start) * 1000
                start = time.perf_counter()
                fused = rrf_combine(lexical, semantic, 24)
                fusion_ms = (time.perf_counter() - start) * 1000
                if attempt == -2:
                    first = (lexical, semantic, fused)
                elif (lexical, semantic, fused) != first:
                    raise AssertionError('Hybrid index ranking became nondeterministic')
                if attempt >= 0:
                    lexical_times.append(lexical_ms)
                    semantic_times.append(semantic_ms)
                    fusion_times.append(fusion_ms)
            output.append({
                'case': name,
                'known_relevant': len(gold),
                'lexical': _quality(lexical, gold),
                'synthetic_semantic': _quality(semantic, gold),
                'rrf_hybrid': _quality(fused, gold),
            })
        return {
            'mode': 'synthetic_android_lsh_rrf_sql_mirror_not_model_or_device',
            'sqlite_version': sqlite3.sqlite_version,
            'synthetic_notes': notes,
            'vector_dimension': DIMENSIONS,
            'embeddings': notes,
            'queries': output,
            'multichunk_cap_probe': synthetic_multichunk_cap_probe(),
            'angular_noise_probe': synthetic_angular_noise_probe(),
            'repetitions_per_query': repeats,
            'index_build_ms': round(index_ms, 2),
            'lexical_lookup': _timing_ms(lexical_times),
            'semantic_lookup': _timing_ms(semantic_times),
            'rrf_fusion': _timing_ms(fusion_times),
            'limitations': [
                'all relevance labels and vectors manually constructed; not a model evaluation',
                'not device latency, memory consumption, battery or Android SQLite version',
                'does not simulate source-byte seals, OCR or model quality',
                'multi-chunk cap probe uses its own tiny artificial index, not Android I/O',
                'one-hot vectors hide real embedding noise and cross-language ambiguity',
                'angular noise probe is artificial LSH collision geometry, not provider output',
                'synthetic positive and negative labels are not representative of user notes',
                'ranking never proves a claim, citation, synonym or semantic entailment',
            ],
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--notes', type=int, default=20_000)
    parser.add_argument('--repeats', type=int, default=5)
    args = parser.parse_args()
    print(json.dumps(benchmark(args.notes, args.repeats), indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
