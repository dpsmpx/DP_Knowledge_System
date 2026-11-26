"""Controlled in-memory comparison of bounded Stage 19 candidate policies."""

import json
import unittest

from scripts.benchmark_stage19_lsh_fairness import (
    FOURWAY_QUERY, POLICIES, _candidates, _database, compare,
)
from scripts.benchmark_stage19_hybrid import MODEL_KEY
from scripts.benchmark_stage19_retrieval import GENERATION


class Stage19LshFairnessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # <2k chunks keep CI fast; the real 46,080-chunk host benchmark uses
        # the same adversarial construction with cap=45,000.
        cls.data = compare(90, 16, 1200, 1)

    def test_saturation_exposes_current_policy_starvation_and_alternative_tradeoffs(self):
        cases = self.data['cases']
        early = cases['single_late_exact_chunk']
        later = cases['late_best_among_other_exact_chunks']
        same = cases['late_better_same_exact_bucket']
        self.assertFalse(early['current_exact_first']['late_best_retrieved_at_6'])
        self.assertFalse(later['current_exact_first']['late_best_retrieved_at_6'])
        self.assertTrue(early['one_chunk_per_source']['late_best_retrieved_at_6'])
        self.assertFalse(later['one_chunk_per_source']['late_best_retrieved_at_6'])
        for policy in ('chunk_interleaved_sort', 'equal_exact_bucket_quotas'):
            self.assertTrue(early[policy]['late_best_retrieved_at_6'])
            self.assertTrue(later[policy]['late_best_retrieved_at_6'])
        self.assertTrue(early['per_bucket_source_sample']['late_best_retrieved_at_6'])
        self.assertTrue(later['per_bucket_source_sample']['late_best_retrieved_at_6'])
        self.assertFalse(same['per_bucket_source_sample']['late_best_retrieved_at_6'])
        self.assertFalse(same['equal_exact_bucket_quotas']['late_best_retrieved_at_6'])
        self.assertFalse(same['one_chunk_per_source']['late_best_retrieved_at_6'])
        for record in (early, later, same):
            self.assertTrue(record['fourway_indexed_merge']['late_best_retrieved_at_6'])
            self.assertTrue(record['chunk_interleaved_sort']['late_best_retrieved_at_6'])
            self.assertTrue(record['source_chunk_quota_then_fill']['late_best_retrieved_at_6'])
            self.assertTrue(record['source_sample_quota_fill']['late_best_retrieved_at_6'])
            self.assertGreater(record['fourway_indexed_merge']['additional_index_kib'], 0)
        self.assertLess(early['equal_exact_bucket_quotas']['sources_seen'],
                        early['one_chunk_per_source']['sources_seen'])

    def test_every_policy_obeys_raw_budget_and_output_discloses_only_counts(self):
        self.assertEqual(1440, self.data['total_chunks'])
        self.assertEqual(1200, self.data['candidate_cap'])
        for case in self.data['cases'].values():
            self.assertEqual(set(POLICIES), set(case))
            for record in case.values():
                self.assertLessEqual(record['raw_chunk_rows'], 1200)
                self.assertLessEqual(record['distinct_chunk_rows'], record['raw_chunk_rows'])
                self.assertLessEqual(record['sources_seen'], 90)
                self.assertGreaterEqual(record['lookup_p50_ms'], 0)
        self.assertNotIn('.md', json.dumps(self.data))
        self.assertIn('not used in Android', ' '.join(self.data['limitations']))

    def test_uneven_source_lengths_expose_quota_failure_and_sample_tradeoff(self):
        probes = self.data['uneven_distribution_probe']
        mixed = probes['mixed_18_short_72_long']
        skew = probes['two_long_88_short']
        self.assertEqual((90, 18, 1746, 1200),
                         (mixed['sources'], mixed['short_sources'],
                          mixed['total_chunks'], mixed['candidate_cap']))
        self.assertEqual((90, 88, 344, 100),
                         (skew['sources'], skew['short_sources'],
                          skew['total_chunks'], skew['candidate_cap']))
        self.assertFalse(mixed['policies']['current_exact_first']['late_best_retrieved_at_6'])
        self.assertTrue(mixed['policies']['source_chunk_quota_then_fill']
                        ['late_best_retrieved_at_6'])
        self.assertEqual(90, mixed['policies']['source_chunk_quota_then_fill']['sources_seen'])
        self.assertFalse(skew['policies']['source_chunk_quota_then_fill']
                         ['late_best_retrieved_at_6'])
        self.assertEqual(89, skew['policies']['source_chunk_quota_then_fill']['sources_seen'])
        for policy in ('source_sample_quota_fill', 'chunk_interleaved_sort',
                       'one_chunk_per_source'):
            self.assertTrue(skew['policies'][policy]['late_best_retrieved_at_6'])
        for data in probes.values():
            for result in data['policies'].values():
                self.assertLessEqual(result['raw_chunk_rows'], data['candidate_cap'])

    def test_skewed_budget_sweep_exposes_early_offset_bias_and_late_chunk_limit(self):
        sweep = self.data['skew_budget_sweep']
        self.assertEqual((90, 88, 344), (sweep['sources'],
                                         sweep['short_sources'], sweep['total_chunks']))
        expected_first_hits = {
            '0': (217, 90, 90, 90),
            '1': (217, 216, 90, 91),
            '3': (217, 217, 90, 93),
            '127': (217, 217, 90, 217),
        }
        for offset, expected in expected_first_hits.items():
            outcomes = sweep['single_exact_chunk'][offset]
            for policy, first_cap in zip(sweep['policies'], expected):
                observed = next((cap for cap in sweep['candidate_caps']
                                 if outcomes[str(cap)][policy]['exact_chunk_seen']), None)
                self.assertEqual(first_cap, observed, (offset, policy))
            for cap in sweep['candidate_caps']:
                for record in outcomes[str(cap)].values():
                    self.assertLessEqual(record['raw_rows'], cap)
                    self.assertLessEqual(record['unique_rows'], record['raw_rows'])

    def test_recovering_source_is_not_recovering_its_later_best_chunk(self):
        same = self.data['skew_budget_sweep']['late_better_same_bucket']
        for policy in ('source_chunk_quota_then_fill', 'source_sample_quota_fill'):
            self.assertTrue(same['90'][policy]['source_seen'])
            self.assertEqual(.99, same['90'][policy]['best_cosine'])
            self.assertEqual(.99, same['100'][policy]['best_cosine'])
        self.assertEqual(.999, same['100']['chunk_interleaved_sort']['best_cosine'])
        self.assertFalse(same['90']['current_exact_first']['source_seen'])
        self.assertNotIn('.md', json.dumps(self.data['skew_budget_sweep']))

    def test_quota_passes_are_disjoint_even_when_the_source_index_is_saturated(self):
        connection = _database(90, 16)
        try:
            rows = _candidates(connection, 'source_chunk_quota_then_fill', 1200)
            self.assertEqual(1200, len(rows))
            self.assertEqual(len(rows), len({(path, chunk) for path, chunk, _ in rows}))
            self.assertEqual(90, len({path for path, _, _ in rows}))
        finally:
            connection.close()

    def test_fourway_index_merge_matches_global_chunk_sort_without_temp_sort(self):
        connection = _database(90, 16)
        try:
            expected = _candidates(connection, 'chunk_interleaved_sort', 1200)
            connection.execute('CREATE INDEX embedding_lsh_bucket_chunk_path '
                               'ON embedding_lsh(bucket,chunk,path)')
            actual = _candidates(connection, 'fourway_indexed_merge', 1200)
            self.assertEqual([(path, chunk) for path, chunk, _ in expected],
                             [(path, chunk) for path, chunk, _ in actual])
            self.assertLessEqual(len(actual), 1200)
            plan = [row[3] for row in connection.execute(
                'EXPLAIN QUERY PLAN ' + FOURWAY_QUERY,
                (1023, MODEL_KEY, 8, GENERATION))]
            self.assertTrue(any('embedding_lsh_bucket_chunk_path' in item for item in plan))
            self.assertFalse(any('TEMP B-TREE' in item for item in plan))
        finally:
            connection.close()

    def test_invalid_or_unbounded_experiment_configuration_rejected(self):
        for args in ((7, 16, 1200, 1), (90, 3, 1200, 1),
                     (90, 16, 0, 1), (90, 16, 45001, 1),
                     (90, 16, 1440, 1), (90, 16, 1200, 0),
                     (90, 16, 1200, 11)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                compare(*args)


if __name__ == '__main__':
    unittest.main()
