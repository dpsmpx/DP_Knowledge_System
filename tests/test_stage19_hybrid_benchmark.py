"""Regression for the offline, deterministic 20k Android LSH/RRF SQL mirror."""

import json
from pathlib import Path
import sqlite3
import unittest

from scripts.benchmark_stage19_hybrid import (
    DIMENSIONS, LSH_BITS, LSH_SAMPLES, LSH_TABLES,
    MAX_SEMANTIC_CANDIDATES, NEGATIVE_CASE, RRF_K, SYNONYM_CASE,
    basis, benchmark, build_synthetic_embeddings, lsh_buckets, lsh_probes,
    rrf_combine, search_semantic_mirror,
)
from scripts.benchmark_stage19_retrieval import build_synthetic_index


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'third_party/markor/app/src/main/java/net/gsantner/markor/knowledge'


class Stage19HybridBenchmarkTests(unittest.TestCase):
    def test_python_lsh_constants_and_golden_vector_match_production_java(self):
        lsh = (JAVA / 'KnowledgeEmbeddingLsh.java').read_text()
        index = (JAVA / 'KnowledgeLocalIndex.java').read_text()
        ranking = (JAVA / 'KnowledgeHybridRanking.java').read_text()
        self.assertEqual((DIMENSIONS, LSH_TABLES, LSH_BITS, LSH_SAMPLES),
                         (8, 4, 10, 24))
        for text in ('TABLES = 4', 'BITS = 10', 'SAMPLES_PER_PLANE = 24',
                     '0x9e3779b9', '0x85ebca6b', '0xc2b2ae35',
                     '0x7feb352d', '0x846ca68b'):
            self.assertIn(text, lsh)
        self.assertIn(f'MAX_SEMANTIC_CANDIDATES = {MAX_SEMANTIC_CANDIDATES:_}', index)
        self.assertIn(f'RRF_K = {RRF_K}', ranking)
        self.assertEqual((1023, 1723, 3022, 3807), lsh_buckets(basis(0)))
        probes = lsh_probes(basis(0))
        self.assertEqual(44, len(probes))
        for table, signature in enumerate(lsh_buckets(basis(0))):
            self.assertEqual(signature, probes[table * 11])
            for bit in range(10):
                self.assertEqual(signature ^ (1 << bit), probes[table * 11 + bit + 1])
        with self.assertRaises(ValueError):
            lsh_buckets((1.0,))

    def test_rrf_fixture_unique_ranks_deterministic_ties_and_bounds(self):
        lexical = ['a.md', 'shared.md', 'a.md', 'b.md']
        semantic = ['c.md', 'shared.md']
        baseline = rrf_combine(['a.md', 'shared.md', 'b.md'], semantic)
        self.assertEqual(baseline, rrf_combine(lexical, semantic))
        self.assertEqual(baseline, rrf_combine(['', 'a.md', 'shared.md', 'b.md'], semantic))
        self.assertEqual(['shared.md', 'a.md', 'c.md', 'b.md'], baseline)
        for left, right, limit in (([], [], 25), (['a.md'] * 41, [], 24),
                                   ([], [], 0)):
            with self.assertRaises(ValueError):
                rrf_combine(left, right, limit)

    def test_lsh_sql_respects_scan_generation_complete_model_and_oversize(self):
        with sqlite3.connect(':memory:') as connection:
            labels = build_synthetic_index(connection, 512)
            extra = build_synthetic_embeddings(connection, 512, labels)
            expected = extra['synonym'].pop()
            self.assertEqual(3, len(search_semantic_mirror(connection, basis(5))))
            self.assertIn(expected, search_semantic_mirror(connection, basis(5)))
            connection.execute("UPDATE notes SET generation='stale' WHERE path=?",
                               (expected,))
            self.assertNotIn(expected, search_semantic_mirror(connection, basis(5)))
            connection.execute('UPDATE notes SET generation=? WHERE path=?',
                               ('synthetic-stage19', expected))
            connection.execute('UPDATE notes SET oversized=1 WHERE path=?', (expected,))
            self.assertNotIn(expected, search_semantic_mirror(connection, basis(5)))
            connection.execute('UPDATE notes SET oversized=0 WHERE path=?', (expected,))
            connection.execute('UPDATE embedding_notes SET completed=0 WHERE path=?',
                               (expected,))
            self.assertNotIn(expected, search_semantic_mirror(connection, basis(5)))
            connection.execute('UPDATE embedding_notes SET completed=1 WHERE path=?',
                               (expected,))
            connection.execute('UPDATE embedding_notes SET model_key=? WHERE path=?',
                               ('wrong-synthetic-model', expected))
            self.assertNotIn(expected, search_semantic_mirror(connection, basis(5)))
            for limit in (0, 41):
                with self.assertRaises(ValueError):
                    search_semantic_mirror(connection, basis(0), limit)

    def test_labeled_diverse_queries_expose_gain_and_false_positive_cost(self):
        data = benchmark(512, 2)
        self.assertEqual(512, data['embeddings'])
        self.assertEqual(7, len(data['queries']))
        self.assertEqual(data['mode'], 'synthetic_android_lsh_rrf_sql_mirror_not_model_or_device')
        for record in data['queries'][:5]:
            self.assertEqual(4, record['known_relevant'])
            for channel in ('lexical', 'synthetic_semantic', 'rrf_hybrid'):
                self.assertEqual(1, record[channel]['recall_at_6'])
                self.assertEqual(2, record[channel]['false_positive_at_6'])
        by_case = {item['case']: item for item in data['queries']}
        synonym = by_case[SYNONYM_CASE]
        self.assertEqual(0, synonym['lexical']['recall_at_6'])
        self.assertEqual(1, synonym['synthetic_semantic']['recall_at_6'])
        self.assertEqual(1, synonym['rrf_hybrid']['recall_at_6'])
        self.assertEqual(2, synonym['rrf_hybrid']['false_positive_at_6'])
        negative = by_case[NEGATIVE_CASE]
        self.assertEqual(0, negative['known_relevant'])
        self.assertIsNone(negative['rrf_hybrid']['recall_at_6'])
        self.assertEqual(2, negative['rrf_hybrid']['false_positive_at_6'])
        self.assertEqual(0, negative['lexical']['retrieved_at_6'])
        self.assertNotIn('.md', json.dumps(data))  # no synthetic note paths in output
        self.assertIn('not a model evaluation', ' '.join(data['limitations']))
        for stage in ('lexical_lookup', 'semantic_lookup', 'rrf_fusion'):
            self.assertGreaterEqual(data[stage]['p95_ms'], data[stage]['p50_ms'])

    def test_large_corpus_preserves_labels_without_real_vectors_or_external_io(self):
        data = benchmark(20_000, 2)
        self.assertEqual(20_000, data['synthetic_notes'])
        self.assertEqual(20_000, data['embeddings'])
        self.assertEqual(1, data['queries'][0]['rrf_hybrid']['recall_at_6'])
        self.assertEqual(0, data['queries'][5]['lexical']['recall_at_6'])
        self.assertEqual(1, data['queries'][5]['rrf_hybrid']['recall_at_6'])
        self.assertIsNone(data['queries'][6]['rrf_hybrid']['recall_at_6'])

    def test_rejects_unbounded_workload_before_allocating(self):
        for count, repeats in ((0, 1), (511, 1), (20_001, 1), (512, 0), (512, 31)):
            with self.assertRaises(ValueError):
                benchmark(count, repeats)


if __name__ == '__main__':
    unittest.main()
