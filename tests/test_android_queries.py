"""Read-only mobile queries use the existing deterministic inference engine."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party" / "markor" / "app" / "src" / "main" / "python"))
from ksys_android_bridge import ask_knowledge, knowledge_diagnostics
from ksys.core.clock import FixedClock
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace


class MobileQueryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "knowledge"

    def test_unknown_does_not_mean_false_and_query_is_read_only(self):
        before = json.loads(ask_knowledge(str(self.root), "Кофе полезен?"))
        self.assertEqual("dpksys.query.v1", before["protocol"])
        self.assertEqual("UNKNOWN", before["status"])
        self.assertIsNone(before["journal_head"])
        self.assertFalse(self.root.exists())

    def test_evidence_and_explicit_contradiction_are_preserved(self):
        ws = Workspace(self.root)
        ws.init()
        kb = KnowledgeBase(ws, FixedClock(), use_cache=False)
        kb.accept_text("Кофе полезен.", role="fact")
        kb.accept_text("Кофе не полезен.", role="fact")
        original = ws.journal_path.read_bytes()
        result = json.loads(ask_knowledge(str(self.root), "Кофе полезен?"))
        self.assertEqual("BOTH", result["status"])
        self.assertTrue(result["sections"]["evidence"])
        self.assertTrue(result["sections"]["contradictions"])
        self.assertEqual(original, ws.journal_path.read_bytes())
        diagnostics = json.loads(knowledge_diagnostics(str(self.root)))
        self.assertGreaterEqual(diagnostics["contradiction_count"], 1)
        self.assertEqual(2, diagnostics["authoritative_count"])

    def test_mutating_requests_cannot_be_submitted_via_query_route(self):
        with self.assertRaises(ValueError):
            ask_knowledge(str(self.root), "Добавь факт: кофе полезен.")
        self.assertFalse(self.root.exists())


if __name__ == "__main__":
    unittest.main()
