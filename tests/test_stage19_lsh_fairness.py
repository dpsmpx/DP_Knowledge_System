"""Controlled in-memory comparison of bounded Stage 19 candidate policies."""

import json
import unittest

from scripts.benchmark_stage19_lsh_fairness import POLICIES, compare


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
        self.assertFalse(early['current_exact_first']['late_best_retrieved_at_6'])
        self.assertFalse(later['current_exact_first']['late_best_retrieved_at_6'])
        self.assertTrue(early['one_chunk_per_source']['late_best_retrieved_at_6'])
        self.assertFalse(later['one_chunk_per_source']['late_best_retrieved_at_6'])
        for policy in ('chunk_interleaved_sort', 'equal_exact_bucket_quotas'):
            self.assertTrue(early[policy]['late_best_retrieved_at_6'])
            self.assertTrue(later[policy]['late_best_retrieved_at_6'])
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

    def test_invalid_or_unbounded_experiment_configuration_rejected(self):
        for args in ((7, 16, 1200, 1), (90, 3, 1200, 1),
                     (90, 16, 0, 1), (90, 16, 45001, 1),
                     (90, 16, 1440, 1), (90, 16, 1200, 0),
                     (90, 16, 1200, 11)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                compare(*args)


if __name__ == '__main__':
    unittest.main()
