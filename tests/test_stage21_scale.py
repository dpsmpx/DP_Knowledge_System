"""Stage 21 synthetic 20k-event journal stress: no vaults, keys or API calls.

Correctness/load smoke test only, NOT Android low-storage, SAF or battery QA.
No wall-clock performance threshold is asserted across CI hardware.
"""
from __future__ import annotations

import hashlib
import unittest

from ksys.core.ids import canonical_json
from ksys.store.journal import entry_id
from ksys.store.journal_sync import MAX_EXCHANGE_BYTES, preview_journals, validated_entries
from ksys.store.state import KnowledgeState


class LargeOfflineBranchTests(unittest.TestCase):
    @staticmethod
    def event(previous, seq, op, data):
        timestamp = "2026-11-20T00:00:00Z"
        actor = "human:synthetic-test-only"
        eid = entry_id(previous, seq, timestamp, actor, op, data, None)
        obj = {"seq": seq, "prev": previous, "id": eid, "ts": timestamp,
               "actor": actor, "op": op, "data": data}
        return canonical_json(obj) + "\n", eid

    def test_twenty_thousand_events_diverge_safely_without_mutation(self):
        prefix = []
        head = None
        for seq in range(1, 20_000):
            line, head = self.event(head, seq, "defer", {"candidates": [f"synthetic-{seq}"]})
            prefix.append(line)
        shared = "".join(prefix)
        local_last, local_head = self.event(head, 20_000, "reject", {
            "targets": ["F:synthetic-local"], "candidates": []})
        foreign_last, foreign_head = self.event(head, 20_000, "reject", {
            "targets": ["F:synthetic-foreign"], "candidates": []})
        local = shared + local_last
        foreign = shared + foreign_last
        self.assertLess(len(local.encode()), MAX_EXCHANGE_BYTES)
        self.assertLess(len(foreign.encode()), MAX_EXCHANGE_BYTES)
        original_digest = hashlib.sha256(local.encode()).hexdigest()

        compared = preview_journals(local, foreign)
        self.assertEqual("diverged", compared.relation)
        self.assertEqual(19_999, compared.common_events)
        self.assertEqual(20_000, compared.local_events)
        self.assertEqual(20_000, compared.remote_events)
        self.assertEqual(1, compared.local_only)
        self.assertEqual(1, compared.remote_only)
        self.assertEqual(head, compared.common_head)
        self.assertEqual(local_head, compared.local_head)
        self.assertEqual(foreign_head, compared.remote_head)
        self.assertFalse(compared.summary()["automatic_merge"])
        self.assertEqual(original_digest, hashlib.sha256(local.encode()).hexdigest())
        state = KnowledgeState.replay(validated_entries(local))
        self.assertEqual(0, len(state.records))
        self.assertEqual(local_head, state.head)

        with self.assertRaises(ValueError):
            validated_entries(local.replace("F:synthetic-local", "F:tampered", 1))
        with self.assertRaises(ValueError):
            validated_entries("x" * (MAX_EXCHANGE_BYTES + 1))


if __name__ == "__main__":
    unittest.main()
