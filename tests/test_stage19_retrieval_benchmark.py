"""Contract regression for the entirely synthetic Stage 19 lexical baseline."""

import sqlite3
import unittest

from scripts.benchmark_stage19_retrieval import (
    benchmark,
    fts_evidence_query,
    search_path_order_or_baseline,
    search_production_lexical_mirror,
)


class Stage19RetrievalBenchmarkTests(unittest.TestCase):
    def test_only_bounded_queries_and_no_raw_fts_syntax(self):
        self.assertEqual('"amber*" OR "violet*"',
                         fts_evidence_query('amber, violet; amber'))
        self.assertEqual('', fts_evidence_query('a/b; 23'))
        self.assertEqual(8, len(fts_evidence_query(
            'alpha bravo charlie delta echo foxtrot golf hotel india').split(' OR ')))

    def test_measures_current_sql_shortlist_and_known_ground_truth(self):
        result = benchmark(notes=2048, repeats=2)
        self.assertEqual(2048, result['synthetic_notes'])
        self.assertIn('not_android_or_hybrid', result['mode'])
        self.assertEqual(4, len(result['queries']))
        self.assertTrue(all(q['known_relevant'] == 4 for q in result['queries']))
        for row in result['queries']:
            self.assertEqual(24, row['retrieved_24'])
            self.assertEqual(0, row['baseline_or_path_recall_at_24'])
            self.assertEqual(1, row['recall_at_6'])
            self.assertGreaterEqual(row['recall_at_24'], row['recall_at_6'])
            self.assertLessEqual(row['recall_at_24'], 1)
            self.assertNotIn('group-', str(row))  # no synthetic source path in report

    def test_rejects_unbounded_workloads_before_allocating(self):
        for count, repeats in ((0, 1), (511, 1), (20_001, 1), (512, 0), (512, 31)):
            with self.assertRaises(ValueError):
                benchmark(count, repeats)

    def test_uses_sqlite_fts4_not_substring_matching(self):
        with sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE notes (id INTEGER, path TEXT, title TEXT,'
                               ' generation TEXT, oversized INTEGER)')
            connection.execute('CREATE VIRTUAL TABLE documents_fts'
                               ' USING fts4(path,title,body,tokenize=unicode61)')
            connection.execute('INSERT INTO notes VALUES (1,?,?,?,0)',
                               ('safe.md', 'test', 'synthetic-stage19'))
            connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                               ' VALUES (1,?,?,?)', ('safe.md', 'test', 'Amber violet'))
            self.assertEqual(['safe.md'], search_production_lexical_mirror(
                connection, 'amber violet', 24))
            self.assertEqual([], search_production_lexical_mirror(
                connection, 'cobalt willow', 24))

    def test_partial_or_fallback_retains_matches_without_inventing_evidence(self):
        with sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE notes (id INTEGER, path TEXT, title TEXT,'
                               ' generation TEXT, oversized INTEGER)')
            connection.execute('CREATE VIRTUAL TABLE documents_fts'
                               ' USING fts4(path,title,body,tokenize=unicode61)')
            connection.execute('INSERT INTO notes VALUES (1,?,?,?,0)',
                               ('partial.md', 'test', 'synthetic-stage19'))
            connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                               ' VALUES (1,?,?,?)', ('partial.md', 'test', 'Only amber'))
            self.assertEqual(['partial.md'], search_production_lexical_mirror(
                connection, 'amber violet', 6))
            self.assertEqual(['partial.md'], search_path_order_or_baseline(
                connection, 'amber violet', 6))

    def test_conjunction_is_ranked_before_alphabetically_earlier_partial_hit(self):
        with sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE notes (id INTEGER, path TEXT, title TEXT,'
                               ' generation TEXT, oversized INTEGER)')
            connection.execute('CREATE VIRTUAL TABLE documents_fts'
                               ' USING fts4(path,title,body,tokenize=unicode61)')
            for ident, path, body in ((1, 'a.md', 'Only amber'),
                                      (2, 'z.md', 'Both amber violet')):
                connection.execute('INSERT INTO notes VALUES (?,?,?,?,0)',
                                   (ident, path, 'test', 'synthetic-stage19'))
                connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                                   ' VALUES (?,?,?,?)', (ident, path, 'test', body))
            self.assertEqual(['a.md', 'z.md'], search_path_order_or_baseline(
                connection, 'amber violet', 2))
            self.assertEqual(['z.md', 'a.md'], search_production_lexical_mirror(
                connection, 'amber violet', 2))


if __name__ == '__main__':
    unittest.main()
