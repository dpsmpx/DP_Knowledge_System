"""Contract regression for the entirely synthetic Stage 19 lexical baseline."""

from pathlib import Path
import re
import sqlite3
import unittest

from scripts.benchmark_stage19_retrieval import (
    QUESTION_FILLERS,
    benchmark,
    fts_evidence_phrase,
    fts_evidence_query,
    search_path_order_or_baseline,
    search_production_lexical_mirror,
    search_two_pass_baseline,
)


class Stage19RetrievalBenchmarkTests(unittest.TestCase):
    def test_python_mirror_tracks_production_question_fillers(self):
        java = (Path(__file__).resolve().parents[1] / 'third_party/markor/app/src/main/'
                'java/net/gsantner/markor/knowledge/KnowledgeMarkdownMetadata.java').read_text()
        method = java.split('private static boolean isQuestionFiller(', 1)[1]
        method = method.split('private static void addTarget(', 1)[0]
        self.assertEqual(QUESTION_FILLERS, frozenset(re.findall(r'case "([^"]+)":', method)))

    def test_only_bounded_queries_and_no_raw_fts_syntax(self):
        self.assertEqual('"amber*" OR "violet*"',
                         fts_evidence_query('amber, violet; amber'))
        self.assertEqual('', fts_evidence_query('a/b; 23'))
        self.assertEqual(8, len(fts_evidence_query(
            'alpha bravo charlie delta echo foxtrot golf hotel india').split(' OR ')))
        self.assertEqual('"amber* violet*"', fts_evidence_phrase('Which amber violet?'))
        self.assertEqual('"синтез* память*"', fts_evidence_phrase('Какие синтез память?'))
        self.assertEqual('"which*"', fts_evidence_query('Which?'))
        self.assertEqual('', fts_evidence_phrase('alpha bravo charlie delta echo'))
        self.assertEqual('"amber* violet*"',
                         fts_evidence_phrase('Tell me about amber violet'))
        self.assertEqual('"память* синтез*"',
                         fts_evidence_phrase('Расскажи, что известно про память синтез?'))
        self.assertEqual('"tell*" OR "about*"', fts_evidence_query('tell about'))

    def test_measures_current_sql_shortlist_and_known_ground_truth(self):
        result = benchmark(notes=2048, repeats=2)
        self.assertEqual(2048, result['synthetic_notes'])
        self.assertIn('not_android_or_hybrid', result['mode'])
        self.assertEqual(5, len(result['queries']))
        self.assertEqual(5, len(result['natural_question_cases']))
        self.assertTrue(all(q['known_relevant'] == 4 for q in result['queries']))
        for row in result['queries']:
            self.assertEqual(24, row['retrieved_24'])
            self.assertEqual(0, row['baseline_or_path_recall_at_24'])
            self.assertEqual(0, row['baseline_two_pass_recall_at_24'])
            self.assertEqual(1, row['recall_at_6'])
            self.assertGreaterEqual(row['recall_at_24'], row['recall_at_6'])
            self.assertLessEqual(row['recall_at_24'], 1)
            self.assertNotIn('group-', str(row))  # no synthetic source path in report
        for row in result['natural_question_cases']:
            self.assertTrue(row['same_shortlist_as_bare_terms'])
            self.assertEqual(1, row['recall_at_6'])
            self.assertEqual(round(4 / 6, 4), row['precision_at_6'])
            self.assertEqual(1, row['recall_at_24'])

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

    def test_current_generation_and_oversized_notes_never_occupy_evidence_slots(self):
        with sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE notes (id INTEGER, path TEXT, title TEXT,'
                               ' generation TEXT, oversized INTEGER)')
            connection.execute('CREATE VIRTUAL TABLE documents_fts'
                               ' USING fts4(path,title,body,tokenize=unicode61)')
            for ident, path, generation, oversized in (
                    (1, 'a-stale.md', 'old-generation', 0),
                    (2, 'b-oversized.md', 'synthetic-stage19', 1),
                    (3, 'z-valid.md', 'synthetic-stage19', 0)):
                connection.execute('INSERT INTO notes VALUES (?,?,?,?,?)',
                                   (ident, path, 'test', generation, oversized))
                connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                                   ' VALUES (?,?,?,?)',
                                   (ident, path, 'test', 'amber violet'))
            self.assertEqual(['z-valid.md'], search_production_lexical_mirror(
                connection, 'amber violet', 24))
            self.assertEqual(['z-valid.md'], search_path_order_or_baseline(
                connection, 'amber violet', 24))

    def test_equal_casefold_paths_keep_a_stable_binary_tiebreaker(self):
        with sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE notes (id INTEGER, path TEXT, title TEXT,'
                               ' generation TEXT, oversized INTEGER)')
            connection.execute('CREATE VIRTUAL TABLE documents_fts'
                               ' USING fts4(path,title,body,tokenize=unicode61)')
            for ident, path in ((1, 'a.md'), (2, 'B.md'), (3, 'A.md')):
                connection.execute('INSERT INTO notes VALUES (?,?,?,?,0)',
                                   (ident, path, 'test', 'synthetic-stage19'))
                connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                                   ' VALUES (?,?,?,?)',
                                   (ident, path, 'test', 'amber violet'))
            for _ in range(3):
                self.assertEqual(['A.md', 'a.md', 'B.md'],
                                 search_production_lexical_mirror(
                                     connection, 'amber violet', 24))

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

    def test_adjacent_phrase_beats_earlier_separated_terms_without_hiding_conflicts(self):
        with sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE notes (id INTEGER, path TEXT, title TEXT,'
                               ' generation TEXT, oversized INTEGER)')
            connection.execute('CREATE VIRTUAL TABLE documents_fts'
                               ' USING fts4(path,title,body,tokenize=unicode61)')
            for ident, path, body in (
                    (1, 'a.md', 'amber synthetic-distance violet'),
                    (2, 'y.md', 'amber violet supports claim'),
                    (3, 'z.md', 'amber violet refutes claim')):
                connection.execute('INSERT INTO notes VALUES (?,?,?,?,0)',
                                   (ident, path, 'test', 'synthetic-stage19'))
                connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                                   ' VALUES (?,?,?,?)', (ident, path, 'test', body))
            self.assertEqual(['a.md', 'y.md'], search_two_pass_baseline(
                connection, 'amber violet', 2))
            self.assertEqual(['y.md', 'z.md'], search_production_lexical_mirror(
                connection, 'Which amber violet?', 2))
            # Retrieval retains opposite synthetic evidence; it cannot decide truth.
            self.assertEqual([], search_production_lexical_mirror(
                connection, 'cobalt willow', 2))

    def test_no_unjustified_synonym_expansion(self):
        with sqlite3.connect(':memory:') as connection:
            connection.execute('CREATE TABLE notes (id INTEGER, path TEXT, title TEXT,'
                               ' generation TEXT, oversized INTEGER)')
            connection.execute('CREATE VIRTUAL TABLE documents_fts'
                               ' USING fts4(path,title,body,tokenize=unicode61)')
            connection.execute('INSERT INTO notes VALUES (1,?,?,?,0)',
                               ('couch.md', 'Furniture', 'synthetic-stage19'))
            connection.execute('INSERT INTO documents_fts(docid,path,title,body)'
                               ' VALUES (1,?,?,?)', ('couch.md', 'Furniture', 'couch'))
            self.assertEqual(['couch.md'], search_production_lexical_mirror(
                connection, 'couch', 2))
            self.assertEqual([], search_production_lexical_mirror(
                connection, 'sofa', 2))


if __name__ == '__main__':
    unittest.main()
