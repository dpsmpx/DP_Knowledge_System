"""Synthetic, offline tests for the Android native SentencePiece adapter."""

import hashlib
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SRC = Path(__file__).resolve().parents[1] / "third_party/markor/app/src/main/python"
sys.path.insert(0, str(SRC))
import ksys_e5_tokenizer as adapter  # noqa: E402


class FakeSentencePiece:
    def __init__(self, count=3, ids=None, valid=True):
        self.count = count
        self.ids = ids or {"▁query": 4, ":": 5, "▁test": 0}
        self.valid = valid
        self.loaded = None

    def LoadFromSerializedProto(self, payload):
        self.loaded = payload
        return self.valid

    def unk_id(self):
        return 0

    def bos_id(self):
        return 1

    def eos_id(self):
        return 2

    def pad_id(self):
        return -1

    def id_to_piece(self, index):
        return ("<unk>", "<s>", "</s>")[index]

    def encode(self, text, out_type):
        assert out_type is str
        assert text.startswith("query: ")
        return ["▁query", ":", "▁test"] * self.count

    def piece_to_id(self, piece):
        return self.ids[piece]

    def get_piece_size(self):
        return 25


class AndroidE5TokenizerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dpks-e5-qa-")
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "synthetic.model"
        # This is deliberately NOT a valid native model; the fake native parser
        # isolates the adapter boundary and test never downloads a tokenizer.
        self.payload = b"test-only-untrusted-bytes"
        self.path.write_bytes(self.payload)
        self.sha = hashlib.sha256(self.payload).hexdigest()

    def encode(self, sp=None, text="query: hello"):
        native = sp or FakeSentencePiece()
        with patch.dict(sys.modules, {"sentencepiece": SimpleNamespace(
                SentencePieceProcessor=lambda: native)}):
            result = adapter.encode(str(self.path), len(self.payload), self.sha, text)
        return result, native

    def test_xlmr_fairseq_offset_and_unmodified_pinned_bytes(self):
        result, processor = self.encode()
        self.assertEqual([0, 5, 6, 3, 5, 6, 3, 5, 6, 3, 2], result)
        self.assertEqual(self.payload, processor.loaded)

    def test_missing_or_modified_or_oversized_model_fails_closed(self):
        for sha, size in [("0" * 64, len(self.payload)),
                          (self.sha, len(self.payload) + 1),
                          ("A" * 64, len(self.payload)),
                          (self.sha, adapter.MAX_MODEL_BYTES + 1)]:
            with self.subTest(sha=sha, size=size), self.assertRaises(ValueError):
                self.encode_pinned(sha, size)
        self.path.write_bytes(self.payload + b"!oops!")
        with self.assertRaises(ValueError):
            self.encode()

    def encode_pinned(self, sha, size):
        with patch.dict(sys.modules, {"sentencepiece": SimpleNamespace(
                SentencePieceProcessor=FakeSentencePiece)}):
            return adapter.encode(str(self.path), size, sha, "query: hello")

    def test_symlink_cannot_replace_pinned_model(self):
        link = Path(self.temp.name) / "link.model"
        link.symlink_to(self.path)
        with self.assertRaises(OSError):
            adapter.encode(str(link), len(self.payload), self.sha, "query: hello")

    def test_invalid_text_and_special_token_injection_are_rejected(self):
        for text in ["hello", "query: ", "query: \ud800", "query: <mask>",
                     "passage: <s>", "query: x" * 5000]:
            with self.subTest(text=text[:30]), self.assertRaises(ValueError):
                self.encode(text=text)

    def test_never_silently_truncates_or_accepts_invalid_native_metadata(self):
        with self.assertRaisesRegex(ValueError, "512"):
            self.encode(FakeSentencePiece(count=171))
        with self.assertRaisesRegex(ValueError, "parser"):
            self.encode(FakeSentencePiece(valid=False))
        wrong_ids = FakeSentencePiece()
        wrong_ids.unk_id = lambda: 1
        with self.assertRaisesRegex(ValueError, "special IDs"):
            self.encode(wrong_ids)


if __name__ == "__main__":
    unittest.main()
