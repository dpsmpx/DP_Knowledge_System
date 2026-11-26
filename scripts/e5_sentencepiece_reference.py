#!/usr/bin/env python3
"""Offline, read-only E5 token-ID reference from a user-supplied .model file.

No Hugging Face download/auth, no model weights, no vault/notes. The JSON
printed to stdout uses only four hard-coded synthetic strings. Requires the
optional host-side `transformers` and `sentencepiece` packages, never bundled
in the Android APK. A future Android tokenizer must match these IDs exactly.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


SAMPLES = {
    "en_query": "query: hello world",
    "ru_query": "query: Привет мир",
    "zh_passage": "passage: 北京天气",
    "emoji_query": "query: 😀 emoji",
}
MAX_BYTES = 64 * 1024 * 1024


def checksum(path: Path) -> str:
    if not path.is_file() or not 16 <= path.stat().st_size <= MAX_BYTES:
        raise ValueError("SentencePiece file is missing or outside size bounds")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def reference(tokenizer: Any, digest: str, version: str) -> dict[str, Any]:
    result: dict[str, Any] = {
        "schema": "dpks-e5-token-id-reference-v1",
        "model_sha256": digest,
        "transformers_version": version,
        "special_ids": {
            "bos": tokenizer.bos_token_id,
            "pad": tokenizer.pad_token_id,
            "eos": tokenizer.eos_token_id,
            "unk": tokenizer.unk_token_id,
        },
        "samples": {},
    }
    if result["special_ids"] != {"bos": 0, "pad": 1, "eos": 2, "unk": 3}:
        raise ValueError("Unexpected XLM-R special-token mapping")
    for name, text in SAMPLES.items():
        encoded = tokenizer(text, add_special_tokens=True, truncation=False,
                            padding=False, return_attention_mask=True)
        ids = encoded["input_ids"]
        mask = encoded["attention_mask"]
        if not 3 <= len(ids) <= 512 or ids[0] != 0 or ids[-1] != 2 \
                or mask != [1] * len(ids):
            raise ValueError("Unexpected E5 reference tensor contract")
        result["samples"][name] = {"ids": ids, "attention_mask": mask}
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sentencepiece", required=True, type=Path,
                        help="local sentencepiece.bpe.model; never downloaded by this script")
    parser.add_argument("--expected-sha256", required=True,
                        help="lowercase SHA-256 pinned independently of this tool")
    args = parser.parse_args()
    if len(args.expected_sha256) != 64 or any(c not in "0123456789abcdef"
                                                   for c in args.expected_sha256):
        parser.error("--expected-sha256 must be 64 lowercase hex characters")
    actual = checksum(args.sentencepiece)
    if actual != args.expected_sha256:
        raise ValueError("SentencePiece digest differs from the expected pinned SHA-256")
    try:
        import transformers
        from transformers import XLMRobertaTokenizer
        import sentencepiece  # noqa: F401 - ensure the actual slow SP backend exists
    except ImportError as missing:
        raise SystemExit("Offline host QA needs installed transformers and sentencepiece; "
                         "no packages were downloaded") from missing
    tokenizer = XLMRobertaTokenizer(vocab_file=str(args.sentencepiece), model_max_length=512)
    print(json.dumps(reference(tokenizer, actual, transformers.__version__),
                     ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
