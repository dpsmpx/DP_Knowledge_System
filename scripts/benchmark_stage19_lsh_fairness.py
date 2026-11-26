#!/usr/bin/env python3
"""Host-only LSH candidate-fairness experiment; never a production search path.

Compares four bounded SQL policies on a disposable in-memory multi-chunk index.
All vectors, paths and labels are artificial. No model, vault, network or APK.
The output contains aggregate counts/timing only, not fixture paths.
"""

import argparse
import heapq
import json
import math
import sqlite3
import statistics
import struct
import time

if __package__:
    from .benchmark_stage19_hybrid import (
        DIMENSIONS, LSH_TABLES, MAX_SEMANTIC_CANDIDATES, MODEL_KEY,
        basis, lsh_buckets, search_semantic_mirror,
    )
    from .benchmark_stage19_retrieval import GENERATION
else:
    from benchmark_stage19_hybrid import (
        DIMENSIONS, LSH_TABLES, MAX_SEMANTIC_CANDIDATES, MODEL_KEY,
        basis, lsh_buckets, search_semantic_mirror,
    )
    from benchmark_stage19_retrieval import GENERATION


TARGET = 'z-synthetic-late-source.md'
POLICIES = ('current_exact_first', 'one_chunk_per_source',
            'chunk_interleaved_sort', 'equal_exact_bucket_quotas',
            'per_bucket_source_sample', 'fourway_indexed_merge')

FOURWAY_QUERY = '''SELECT e.path, v.chunk, v.vector FROM embedding_lsh l
                   INDEXED BY embedding_lsh_bucket_chunk_path
                   JOIN embedding_chunks v ON v.path=l.path AND v.chunk=l.chunk
                   JOIN embedding_notes e ON e.path=v.path
                   JOIN notes n ON n.path=e.path
                   WHERE l.bucket=?
                     AND e.model_key=? AND e.dimension=? AND e.completed=e.chunks
                     AND n.generation=? AND n.oversized=0
                   ORDER BY l.chunk,l.path'''


def _vector(cosine: float) -> tuple[float, ...]:
    return (cosine, math.sqrt(1 - cosine * cosine), 0., 0., 0., 0., 0., 0.)


def _database(sources: int, chunks: int) -> sqlite3.Connection:
    connection = sqlite3.connect(':memory:')
    connection.executescript('''
        CREATE TABLE notes(path TEXT PRIMARY KEY, generation TEXT, oversized INTEGER);
        CREATE TABLE embedding_notes(path TEXT PRIMARY KEY, model_key TEXT,
                                     dimension INTEGER, chunks INTEGER, completed INTEGER);
        CREATE TABLE embedding_chunks(path TEXT, chunk INTEGER, vector BLOB,
                                      PRIMARY KEY(path,chunk));
        CREATE TABLE embedding_lsh(bucket INTEGER, path TEXT, chunk INTEGER,
                                   PRIMARY KEY(bucket,path,chunk));
        CREATE INDEX embedding_lsh_path ON embedding_lsh(path);
    ''')
    near = _vector(.99)  # one exact table, but cosine lower than the target
    hidden = _vector(.80)  # no matching exact or one-bit bucket
    exact = basis(0)
    packed_near = struct.pack('<8f', *near)
    packed_hidden = struct.pack('<8f', *hidden)
    packed_exact = struct.pack('<8f', *exact)
    near_buckets, hidden_buckets, exact_buckets = map(
        lsh_buckets, (near, hidden, exact))
    with connection:
        for number in range(sources):
            path = TARGET if number == sources - 1 else f'a-synthetic-{number:04d}.md'
            connection.execute('INSERT INTO notes VALUES (?,?,0)', (path, GENERATION))
            connection.execute('INSERT INTO embedding_notes VALUES (?,?,?,?,?)',
                               (path, MODEL_KEY, DIMENSIONS, chunks, chunks))
            is_target = path == TARGET
            connection.executemany('INSERT INTO embedding_chunks VALUES (?,?,?)',
                                   ((path, chunk,
                                     packed_exact if is_target and chunk == 3 else
                                     packed_hidden if is_target else packed_near)
                                    for chunk in range(chunks)))
            connection.executemany('INSERT INTO embedding_lsh VALUES (?,?,?)',
                                   ((bucket, path, chunk)
                                    for chunk in range(chunks)
                                    for bucket in (exact_buckets if is_target and chunk == 3
                                                   else hidden_buckets if is_target
                                                   else near_buckets)))
    return connection


def _make_target_earlier_chunks_exact_reachable(connection: sqlite3.Connection,
                                                chunks: int) -> None:
    """Target has an early weaker exact-bucket chunk and a later perfect one."""
    near = _vector(.99)
    packed = struct.pack('<8f', *near)
    buckets = lsh_buckets(near)
    with connection:
        connection.execute('DELETE FROM embedding_lsh WHERE path=? AND chunk<>3', (TARGET,))
        connection.executemany('UPDATE embedding_chunks SET vector=? '
                               'WHERE path=? AND chunk=?',
                               ((packed, TARGET, i) for i in range(chunks) if i != 3))
        connection.executemany('INSERT INTO embedding_lsh VALUES (?,?,?)',
                               ((bucket, TARGET, i) for i in range(chunks) if i != 3
                                for bucket in buckets))


def _make_target_best_only_within_same_exact_bucket(connection: sqlite3.Connection) -> None:
    """Later 0.999 chunk shares one exact table with earlier 0.99 chunks."""
    better = _vector(.999)
    if len(set(lsh_buckets(better)).intersection(lsh_buckets(basis(0)))) != 1:
        raise AssertionError('The fixed one-table collision fixture changed')
    with connection:
        connection.execute('UPDATE embedding_chunks SET vector=? '
                           'WHERE path=? AND chunk=3',
                           (struct.pack('<8f', *better), TARGET))
        connection.execute('DELETE FROM embedding_lsh WHERE path=? AND chunk=3', (TARGET,))
        connection.executemany('INSERT INTO embedding_lsh VALUES (?,?,3)',
                               ((bucket, TARGET) for bucket in lsh_buckets(better)))


def _candidates(connection: sqlite3.Connection, policy: str,
                cap: int) -> list[tuple[str, int, bytes]]:
    exact = lsh_buckets(basis(0))

    def fetch(buckets: tuple[int, ...], maximum: int,
              grouping: str, order: str = '') -> list[tuple[str, int, bytes]]:
        sql = '''SELECT e.path, v.chunk, v.vector FROM embedding_lsh l
                 JOIN embedding_chunks v ON v.path=l.path AND v.chunk=l.chunk
                 JOIN embedding_notes e ON e.path=v.path
                 JOIN notes n ON n.path=e.path
                 WHERE l.bucket IN (''' + ','.join('?' for _ in buckets) + ''')
                   AND e.model_key=? AND e.dimension=? AND e.completed=e.chunks
                   AND n.generation=? AND n.oversized=0
                 GROUP BY ''' + grouping + order + ' LIMIT ?'
        return list(connection.execute(sql, (*buckets, MODEL_KEY, DIMENSIONS,
                                             GENERATION, maximum)))

    if policy == 'current_exact_first':
        return fetch(exact, cap, 'l.path,l.chunk')
    if policy == 'one_chunk_per_source':
        return fetch(exact, cap, 'l.path')
    if policy == 'chunk_interleaved_sort':
        return fetch(exact, cap, 'l.path,l.chunk', ' ORDER BY l.chunk,l.path')
    if policy == 'equal_exact_bucket_quotas':
        rows = []
        for index, bucket in enumerate(exact):
            quota = cap // LSH_TABLES + (1 if index < cap % LSH_TABLES else 0)
            if quota:
                rows.extend(fetch((bucket,), quota, 'l.path,l.chunk'))
        return rows
    if policy == 'per_bucket_source_sample':
        # No ORDER BY across chunks. Every exact table gets at most one
        # candidate per source, with the global *raw row* cap preserved.
        # Multiple tables can still inspect the same source/chunk: this is
        # a diagnostic, not a production deduplicated candidate allocator.
        rows = []
        for bucket in exact:
            remaining = cap - len(rows)
            if remaining <= 0:
                break
            rows.extend(fetch((bucket,), remaining, 'l.path'))
        return rows
    if policy == 'fourway_indexed_merge':
        # The *extra* (bucket,chunk,path) index is created only while timing
        # this host experiment. The production app does not have this index.
        # One index-ordered cursor per exact bucket, with a four-item heap:
        # no global GROUP BY/ORDER BY over 45k candidates.
        cursors = [connection.execute(FOURWAY_QUERY, (bucket, MODEL_KEY,
                             DIMENSIONS, GENERATION)) for bucket in exact]
        heap = []
        for i, cursor in enumerate(cursors):
            row = cursor.fetchone()
            if row is not None:
                path, chunk, packed = row
                heapq.heappush(heap, (chunk, path, i, packed))
        rows = []
        previous_key = None
        while heap and len(rows) < cap:
            chunk, path, i, packed = heapq.heappop(heap)
            if (chunk, path) != previous_key:
                rows.append((path, chunk, packed))
                previous_key = (chunk, path)
            row = cursors[i].fetchone()
            if row is not None:
                next_path, next_chunk, next_packed = row
                heapq.heappush(heap, (next_chunk, next_path, i, next_packed))
        for cursor in cursors:
            cursor.close()
        return rows
    raise ValueError('Unsupported experimental policy')


def _evaluate(rows: list[tuple[str, int, bytes]],
              elapsed: list[float]) -> dict:
    best: dict[str, float] = {}
    for path, _chunk, packed in rows:
        vector = struct.unpack('<8f', packed)
        cosine = vector[0]  # query is the first unit basis; mirrors exact cosine
        if path not in best or cosine > best[path]:
            best[path] = cosine
    ranked = sorted(best, key=lambda path: (-best[path], path))[:6]
    return {
        'raw_chunk_rows': len(rows),
        'distinct_chunk_rows': len({(path, chunk) for path, chunk, _ in rows}),
        'sources_seen': len(best),
        'late_best_retrieved_at_6': TARGET in ranked,
        'late_source_best_cosine': round(best[TARGET], 3) if TARGET in best else None,
        'lookup_p50_ms': round(statistics.median(elapsed), 3),
        'lookup_max_ms': round(max(elapsed), 3),
    }


def compare(sources: int = 360, chunks: int = 128,
            cap: int = MAX_SEMANTIC_CANDIDATES,
            trials: int = 2) -> dict:
    if not 8 <= sources <= 20_000 or not 4 <= chunks <= 128:
        raise ValueError('Only 8..20000 sources and 4..128 chunks are supported')
    if not 4 <= cap <= MAX_SEMANTIC_CANDIDATES or (sources - 1) * chunks <= cap:
        raise ValueError('Fixture must saturate exact buckets within the cap')
    if not 1 <= trials <= 10:
        raise ValueError('Use 1..10 measured repetitions')
    connection = _database(sources, chunks)
    try:
        cases = {}
        for case in ('single_late_exact_chunk', 'late_best_among_other_exact_chunks',
                     'late_better_same_exact_bucket'):
            if case == 'late_best_among_other_exact_chunks':
                _make_target_earlier_chunks_exact_reachable(connection, chunks)
            if case == 'late_better_same_exact_bucket':
                _make_target_best_only_within_same_exact_bucket(connection)
            results = {}
            for policy in POLICIES:
                index_ms = None
                index_kib = None
                if policy == 'fourway_indexed_merge':
                    page_size = connection.execute('PRAGMA page_size').fetchone()[0]
                    def in_use_pages():
                        pages = connection.execute('PRAGMA page_count').fetchone()[0]
                        free = connection.execute('PRAGMA freelist_count').fetchone()[0]
                        return pages - free
                    before_pages = in_use_pages()
                    started = time.perf_counter()
                    connection.execute('CREATE INDEX embedding_lsh_bucket_chunk_path '
                                       'ON embedding_lsh(bucket,chunk,path)')
                    index_ms = round((time.perf_counter() - started) * 1000, 3)
                    index_kib = round((in_use_pages() - before_pages) * page_size / 1024, 1)
                measurements = []
                reference = None
                try:
                    for trial in range(trials + 1):  # first pass is an untimed warmup
                        start = time.perf_counter()
                        rows = _candidates(connection, policy, cap)
                        elapsed = (time.perf_counter() - start) * 1000
                        signatures = [(path, chunk) for path, chunk, _packed in rows]
                        if reference is not None and signatures != reference:
                            raise AssertionError('Unstable synthetic SQL ordering')
                        reference = signatures
                        if trial > 0:
                            measurements.append(elapsed)
                finally:
                    if policy == 'fourway_indexed_merge':
                        connection.execute('DROP INDEX embedding_lsh_bucket_chunk_path')
                results[policy] = _evaluate(rows, measurements)
                if index_ms is not None:
                    results[policy]['additional_index_build_ms'] = index_ms
                    results[policy]['additional_index_kib'] = index_kib
                if policy == 'current_exact_first':
                    baseline = search_semantic_mirror(connection, basis(0), 6,
                                                      cap, policy='exact_first')
                    if (TARGET in baseline) != results[policy]['late_best_retrieved_at_6']:
                        raise AssertionError('Experimental baseline diverged from Android SQL mirror')
            cases[case] = results
        return {
            'mode': 'in_memory_synthetic_lsh_fairness_host_only',
            'sqlite_version': sqlite3.sqlite_version,
            'sources': sources, 'chunks_per_source': chunks,
            'total_chunks': sources * chunks,
            'candidate_cap': cap, 'measured_trials': trials,
            'cases': cases,
            'limitations': [
                'alternative SQL is diagnostic only and is not used in Android',
                'one-chunk sampling can miss a later better chunk in the same source',
                'per-bucket quotas may strand capacity or duplicate source chunks',
                'per-bucket source sampling can repeat paths, starve later tables '
                'and choose a weaker arbitrary chunk within one table',
                'even the four-table source sample misses a later better chunk '
                'when it shares only the same exact table as an earlier chunk',
                'four-way indexed merging needs an extra derived SQLite index '
                'whose write, storage, migration and device energy costs are untested',
                'chunk interleaving may require an expensive temporary ORDER BY sort',
                'no representative model, real vault or physical-device latency/energy',
                'observed GROUP BY row choice is SQLite-plan-dependent',
            ],
        }
    finally:
        connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', type=int, default=360)
    parser.add_argument('--chunks', type=int, default=128)
    parser.add_argument('--cap', type=int, default=MAX_SEMANTIC_CANDIDATES)
    parser.add_argument('--trials', type=int, default=2)
    args = parser.parse_args()
    print(json.dumps(compare(args.sources, args.chunks, args.cap, args.trials),
                     indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
