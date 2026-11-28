"""Portable journal validation/recovery, entirely with synthetic decisions."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "third_party" / "markor" / "app" / "src" / "main" / "python"))
from ksys_android_bridge import journal_export_info, journal_restore_preview, journal_restore_commit
from ksys.core.clock import FixedClock
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace


class BackupTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        ws = Workspace(self.root / "source")
        ws.init()
        kb = KnowledgeBase(ws, FixedClock(), use_cache=False)
        kb.accept_text("Кофе полезен.")
        self.original = ws.journal_path.read_text(encoding="utf-8")
        self.source = ws

    def test_export_and_restore_keep_hash_chain(self):
        summary = json.loads(journal_export_info(str(self.source.root)))
        preview = json.loads(journal_restore_preview(str(self.root / "restored"), self.original))
        self.assertEqual(summary["head"], preview["head"])
        restored = json.loads(journal_restore_commit(
            str(self.root / "restored"), self.original, preview["sha256"]))
        self.assertEqual(preview["head"], restored["head"])
        self.assertEqual(self.original, Workspace(self.root / "restored")
                         .journal_path.read_text(encoding="utf-8"))

    def test_empty_decision_journal_has_no_export_payload(self):
        empty = Workspace(self.root / "no_decisions")
        with self.assertRaisesRegex(ValueError, "Journal is empty"):
            journal_export_info(str(empty.root))
        self.assertFalse(empty.journal_path.is_file())

    def test_restore_refuses_existing_journal_or_changed_preview(self):
        with self.assertRaises(ValueError):
            journal_restore_preview(str(self.source.root), self.original)
        with self.assertRaises(ValueError):
            journal_restore_commit(str(self.root / "other"), self.original, "wrong-hash")
        self.assertFalse((self.root / "other").exists())

    def test_rejects_tampered_backup(self):
        tampered = self.original.replace('"actor":"human:me"', '"actor":"intruder"')
        with self.assertRaises(ValueError):
            journal_restore_preview(str(self.root / "restored"), tampered)
        self.assertFalse((self.root / "restored").exists())


if __name__ == "__main__":
    unittest.main()
