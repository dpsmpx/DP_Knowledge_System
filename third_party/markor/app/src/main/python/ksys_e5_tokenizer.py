"""Local, pinned SentencePiece/XLM-R ID adapter for the DPKS preview flavor.

The SentencePiece native Android wheel is supplied at APK build time. The
*tokenizer model* is always provided manually and SHA-verified at runtime.
No Transformers runtime, network, E5 weights, vault data, or implicit truncation.
"""

from __future__ import annotations

import hashlib
import os
import re
import stat


MAX_MODEL_BYTES = 64 * 1024 * 1024
MAX_SEQUENCE = 512
MAX_TEXT_UNITS = 8192 + len("passage: ")
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_CONTROL_TOKENS = ("<s>", "</s>", "<unk>", "<pad>", "<mask>")


def _verified_model(path: str, expected_bytes: int, expected_sha256: str) -> bytes:
    if not isinstance(path, str) or not path or not isinstance(expected_bytes, int) \
            or not 16 <= expected_bytes <= MAX_MODEL_BYTES \
            or not isinstance(expected_sha256, str) \
            or _SHA256.fullmatch(expected_sha256) is None:
        raise ValueError("Invalid pinned SentencePiece model input")

    # No following symlinks and no unbounded read. Hash *the exact same bytes*
    # that are handed to the native parser, avoiding a path-swap race.
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size != expected_bytes:
            raise ValueError("SentencePiece model size mismatch")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            payload = stream.read(expected_bytes + 1)
        if len(payload) != expected_bytes \
                or hashlib.sha256(payload).hexdigest() != expected_sha256:
            raise ValueError("SentencePiece model content differs from pinned SHA-256")
        return payload
    finally:
        os.close(fd)


def encode(model_path: str, expected_bytes: int, expected_sha256: str,
           prepared_text: str) -> list[int]:
    """Encode E5-prefixed text with the original SentencePiece algorithm.

    Same fairseq offset and special IDs as the *slow* XLMRobertaTokenizer
    in Transformers 4.57.1: raw SP unknown (0) -> 3, all other pieces +1;
    BOS=0, EOS=2. Refuses overlength input rather than silently truncating.
    """
    if not isinstance(prepared_text, str) \
            or not prepared_text.startswith(("query: ", "passage: ")) \
            or prepared_text in ("query: ", "passage: ") \
            or not 1 <= len(prepared_text.encode("utf-16-le")) // 2 <= MAX_TEXT_UNITS \
            or any(s in prepared_text for s in _CONTROL_TOKENS):
        raise ValueError("Unsafe or unprefixed E5 text")
    if any(0xD800 <= ord(char) <= 0xDFFF for char in prepared_text):
        raise ValueError("Malformed E5 Unicode")

    payload = _verified_model(model_path, expected_bytes, expected_sha256)
    # Import lazily: upstream Markor flavors don't ship this native wheel.
    import sentencepiece as spm
    processor = spm.SentencePieceProcessor()
    if not processor.LoadFromSerializedProto(payload):
        raise ValueError("Native SentencePiece parser rejected pinned model")
    if processor.unk_id() != 0 or processor.bos_id() != 1 \
            or processor.eos_id() != 2 or processor.pad_id() != -1 \
            or processor.id_to_piece(0) != "<unk>" \
            or processor.id_to_piece(1) != "<s>" \
            or processor.id_to_piece(2) != "</s>":
        raise ValueError("Unexpected XLM-R raw SentencePiece special IDs")

    pieces = processor.encode(prepared_text, out_type=str)
    if not 1 <= len(pieces) <= MAX_SEQUENCE - 2:
        raise ValueError("E5 text needs more than 512 tokens or has no content")
    ids = [0]
    for piece in pieces:
        raw_id = processor.piece_to_id(piece)
        if not 0 <= raw_id < processor.get_piece_size():
            raise ValueError("Unrecognized SentencePiece piece")
        ids.append(raw_id + 1 if raw_id else 3)
    ids.append(2)
    return ids
