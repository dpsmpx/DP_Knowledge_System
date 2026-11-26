#!/usr/bin/env python3
"""Offline, read-only E5 token-ID reference from a user-supplied .model file.

No Hugging Face download/auth, no model weights, no vault/notes. The JSON
printed to stdout uses only four hard-coded synthetic strings. Requires the
host-side `transformers==4.57.1` and `sentencepiece` packages, and does not
bundle them in any output. DPKS independently uses an Android native wheel.
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
REFERENCE_TRANSFORMERS_VERSION = "4.57.1"


def checksum(path: Path) -> str:
    if not path.is_file() or not 16 <= path.stat().st_size <= MAX_BYTES:
        raise ValueError("SentencePiece file is missing or outside size bounds")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def golden_fingerprint(model_sha256: str, samples: dict[str, Any]) -> str:
    """Canonical ID digest shared with Android KnowledgeE5GoldenQa.

    Only four synthetic texts are allowed. Model SHA is part of the digest;
    these fingerprints are not evidence that any ONNX model is runnable.
    """
    if len(model_sha256) != 64 or any(c not in "0123456789abcdef"
                                      for c in model_sha256) \
            or set(samples) != set(SAMPLES):
        raise ValueError("Unexpected E5 golden reference identity")
    lines = ["dpks-e5-golden-v1", model_sha256]
    for name in sorted(SAMPLES):
        item = samples[name]
        ids = item["ids"]
        mask = item["attention_mask"]
        if not isinstance(ids, list) or not 3 <= len(ids) <= 512 \
                or ids[0] != 0 or ids[-1] != 2 \
                or any(type(n) is not int or n < 0 for n in ids) \
                or mask != [1] * len(ids):
            raise ValueError("Unexpected E5 golden token sequence")
        lines.append(name + "=" + ",".join(str(item) for item in ids))
    return hashlib.sha256(("\n".join(lines) + "\n").encode("utf-8")).hexdigest()


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
    result["golden_fingerprint_sha256"] = golden_fingerprint(
        digest, result["samples"])
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sentencepiece", required=True, type=Path,
                        help="local sentencepiece.bpe.model; never downloaded by this script")
    parser.add_argument("--expected-sha256", required=True,
                        help="lowercase SHA-256 pinned independently of this tool")
    parser.add_argument("--android-fingerprint", default="",
                        help="optional 64-character fingerprint copied from manual DPKS QA; "
                             "exit code 2 if it differs")
    args = parser.parse_args()
    if len(args.expected_sha256) != 64 or any(c not in "0123456789abcdef"
                                                   for c in args.expected_sha256):
        parser.error("--expected-sha256 must be 64 lowercase hex characters")
    if args.android_fingerprint and (len(args.android_fingerprint) != 64 or any(
            c not in "0123456789abcdef" for c in args.android_fingerprint)):
        parser.error("--android-fingerprint must be 64 lowercase hex characters")
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
    if transformers.__version__ != REFERENCE_TRANSFORMERS_VERSION:
        raise ValueError("Expected pinned slow XLMRobertaTokenizer from transformers "
                         + REFERENCE_TRANSFORMERS_VERSION)
    tokenizer = XLMRobertaTokenizer(vocab_file=str(args.sentencepiece), model_max_length=512)
    output = reference(tokenizer, actual, transformers.__version__)
    matching = not args.android_fingerprint or (
        args.android_fingerprint == output["golden_fingerprint_sha256"])
    if args.android_fingerprint:
        output["android_fingerprint_matches"] = matching
    print(json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if matching else 2


if __name__ == "__main__":
    raise SystemExit(main())
