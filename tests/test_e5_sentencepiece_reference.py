"""No network or optional ML dependency is required for the host reference contract."""

from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from scripts.e5_sentencepiece_reference import (
    SAMPLES, checksum, golden_fingerprint, reference,
)


class DummyTokenizer:
    bos_token_id = 0
    pad_token_id = 1
    eos_token_id = 2
    unk_token_id = 3

    def __call__(self, text, **kwargs):
        assert kwargs == {
            "add_special_tokens": True,
            "truncation": False,
            "padding": False,
            "return_attention_mask": True,
        }
        return {"input_ids": [0, 11 + len(text), 2],
                "attention_mask": [1, 1, 1]}


class E5ReferenceTest(unittest.TestCase):
    def test_reference_uses_four_fixed_multilingual_samples_only(self):
        self.assertEqual(4, len(SAMPLES))
        result = reference(DummyTokenizer(), "a" * 64, "synthetic-stub")
        self.assertEqual("dpks-e5-token-id-reference-v1", result["schema"])
        self.assertEqual(set(SAMPLES), set(result["samples"]))
        self.assertEqual([0, 11 + len(SAMPLES["en_query"]), 2],
                         result["samples"]["en_query"]["ids"])
        self.assertEqual([1, 1, 1], result["samples"]["ru_query"]["attention_mask"])
        self.assertEqual(golden_fingerprint("a" * 64, result["samples"]),
                         result["golden_fingerprint_sha256"])

    def test_cross_language_fingerprint_fixture(self):
        samples = {name: {"ids": [0, 10 + index, 2],
                          "attention_mask": [1, 1, 1]}
                   for index, name in enumerate(sorted(SAMPLES))}
        self.assertEqual(
            "212e27cf4da57fe4795cfedcb37c7e208810e8063953dfefab92bbb651110d4c",
            golden_fingerprint("a" * 64, samples))
        samples["ru_query"]["attention_mask"][0] = 0
        with self.assertRaisesRegex(ValueError, "golden token"):
            golden_fingerprint("a" * 64, samples)
        with self.assertRaisesRegex(ValueError, "identity"):
            golden_fingerprint("wrong", samples)

    def test_wrong_special_ids_are_rejected(self):
        fake = DummyTokenizer()
        fake.pad_token_id = 0
        with self.assertRaisesRegex(ValueError, "special-token"):
            reference(fake, "a" * 64, "stub")

    def test_invalid_tokenizer_output_is_rejected(self):
        class EmptyTokenizer(DummyTokenizer):
            def __call__(self, text, **kwargs):
                return {"input_ids": [0, 2], "attention_mask": [1, 1]}

        with self.assertRaisesRegex(ValueError, "tensor contract"):
            reference(EmptyTokenizer(), "a" * 64, "stub")

    def test_checksum_bounded_and_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.model"
            path.write_bytes(b"synthetic-tokenizer-sidecar")
            self.assertEqual(hashlib.sha256(b"synthetic-tokenizer-sidecar").hexdigest(),
                             checksum(path))
            path.write_bytes(b"too short")
            with self.assertRaises(ValueError):
                checksum(path)


if __name__ == "__main__":
    unittest.main()
