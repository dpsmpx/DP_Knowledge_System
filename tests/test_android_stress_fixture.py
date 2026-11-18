"""Safety and determinism checks for the Android test-vault generator."""

import tempfile
import unittest
from pathlib import Path

from scripts.generate_android_stress_vault import create_fixture


class StressVaultFixtureTests(unittest.TestCase):
    def test_fixture_is_synthetic_and_hashes_are_reproducible(self):
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary) / "first"
            second = Path(temporary) / "second"
            self.assertEqual(create_fixture(first, 1001), create_fixture(second, 1001))
            self.assertEqual(len(list(first.rglob("*.md"))), 1001)
            text = (first / "group-000" / "synthetic-000000.md").read_text()
            self.assertIn("Only fake content", text)

    def test_existing_vault_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "personal-do-not-touch"
            root.mkdir()
            sentinel = root / "note.md"
            sentinel.write_text("private sentinel")
            with self.assertRaises(FileExistsError):
                create_fixture(root, 25)
            self.assertEqual(sentinel.read_text(), "private sentinel")

    def test_refuses_excessive_or_invalid_requests(self):
        with tempfile.TemporaryDirectory() as temporary:
            for count in (0, -1, 200001):
                with self.assertRaises(ValueError):
                    create_fixture(Path(temporary) / "unsafe", count)


if __name__ == "__main__":
    unittest.main()
