#!/usr/bin/env python3
"""Explicit, offline host-side E5 Small ONNX embedding reference.

Only a user-supplied, SHA-pinned, single-file ONNX package is accepted.
All input texts are hard-coded synthetic examples. This tool never downloads
dependencies or models, reads a note vault, writes embeddings, or changes the
Android model-readiness gate. Real ONNX inference happens ONLY when invoked.
Requires preinstalled transformers==4.57.1, sentencepiece, numpy, onnx and
onnxruntime. External-data ONNX packages need a separate future verifier.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import struct
import tempfile
from typing import Any

try:
    from .e5_sentencepiece_reference import (
        REFERENCE_TRANSFORMERS_VERSION, SAMPLES, reference,
    )
    from .e5_package_manifest import (
        OFFICIAL_CONFIG_SHA256, OFFICIAL_ONNX, OFFICIAL_SENTENCEPIECE_SHA256,
        OFFICIAL_TOKENIZER_CONFIG_SHA256,
    )
except ImportError:  # Direct `python scripts/e5_onnx_reference.py` execution.
    from e5_sentencepiece_reference import (
        REFERENCE_TRANSFORMERS_VERSION, SAMPLES, reference,
    )
    from e5_package_manifest import (
        OFFICIAL_CONFIG_SHA256, OFFICIAL_ONNX, OFFICIAL_SENTENCEPIECE_SHA256,
        OFFICIAL_TOKENIZER_CONFIG_SHA256,
    )


MAX_MANIFEST_BYTES = 64 * 1024
MAX_CONFIG_BYTES = 64 * 1024
MAX_INLINE_ONNX_BYTES = 1024 * 1024 * 1024
MAX_COMPARE_BYTES = 1024 * 1024
MAX_OTHER_BYTES = 64 * 1024 * 1024
MAX_OTHER_TOTAL_BYTES = 256 * 1024 * 1024
EXPECTED_SOURCE = "intfloat/multilingual-e5-small"
HEX = re.compile(r"[0-9a-f]{64}\Z")
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
REVISION = re.compile(r"[0-9a-f]{40}\Z")
LOCAL_REVISION_DOMAIN = "dpks-e5-local-package-v1\n"

MODEL_PROFILE: dict[str, Any] = {
    "model_type": "bert",
    "tokenizer_class": "XLMRobertaTokenizer",
    "position_embedding_type": "absolute",
    "hidden_act": "gelu",
    "hidden_size": 384,
    "max_position_embeddings": 512,
    "vocab_size": 250037,
    "num_hidden_layers": 12,
    "num_attention_heads": 12,
    "intermediate_size": 1536,
    "type_vocab_size": 2,
    "pad_token_id": 0,  # Not the XLM-R tokenizer's <pad>=1.
}
TOKENIZER_PROFILE: dict[str, Any] = {
    "tokenizer_class": "XLMRobertaTokenizer",
    "bos_token": "<s>", "cls_token": "<s>",
    "eos_token": "</s>", "sep_token": "</s>",
    "pad_token": "<pad>", "unk_token": "<unk>",
    "model_max_length": 512,
    "sp_model_kwargs": {},
}


@dataclass(frozen=True)
class CheckedPackage:
    manifest_sha256: str
    model_path: Path
    model_sha256: str
    sentencepiece_path: Path
    sentencepiece_sha256: str


def _sha(value: Any) -> bool:
    return isinstance(value, str) and HEX.fullmatch(value) is not None


def _open_regular(path: Path):
    """Open once with O_NOFOLLOW; never validate one path then read another."""
    if not hasattr(os, "O_NOFOLLOW"):
        raise ValueError("Host lacks safe no-follow local file access")
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0))
    except OSError as invalid:
        raise ValueError("Package component is a link, missing or inaccessible") from invalid
    return os.fdopen(fd, "rb")


def _same_file(before: os.stat_result, after: os.stat_result) -> bool:
    return (before.st_dev, before.st_ino, before.st_mode, before.st_size,
            before.st_mtime_ns, before.st_ctime_ns) == (
            after.st_dev, after.st_ino, after.st_mode, after.st_size,
            after.st_mtime_ns, after.st_ctime_ns)


def _read_regular(path: Path, limit: int, expected_sha: str | None = None) -> bytes:
    with _open_regular(path) as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not 1 <= before.st_size <= limit:
            raise ValueError("Required local file is not regular or exceeds the bound")
        data = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
    if len(data) != before.st_size or not _same_file(before, after):
        raise ValueError("Local file changed during reading")
    if expected_sha is not None and (not _sha(expected_sha)
                                     or hashlib.sha256(data).hexdigest() != expected_sha):
        raise ValueError("Local file differs from pinned SHA-256")
    return data


def _digest_file(path: Path, expected_size: int, expected_sha: str) -> None:
    digest = hashlib.sha256()
    total = 0
    with _open_regular(path) as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size != expected_size:
            raise ValueError("Package component is a link, missing or has the wrong size")
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            total += len(chunk)
            if total > expected_size:
                raise ValueError("Package component grew during hashing")
            digest.update(chunk)
        after = os.fstat(stream.fileno())
    if not _same_file(before, after):
        raise ValueError("Package component metadata changed during hashing")
    if total != expected_size or digest.hexdigest() != expected_sha:
        raise ValueError("Package component differs from pinned size/SHA-256")


def _stage_pinned_tokenizer(package: CheckedPackage, folder: Path) -> Path:
    """Give native SentencePiece a verified private snapshot, not a mutable input path."""
    tokenizer = _read_regular(package.sentencepiece_path, MAX_OTHER_BYTES,
                              package.sentencepiece_sha256)
    destination = folder / "sentencepiece.bpe.model"
    with destination.open("xb") as output:
        output.write(tokenizer)
    return destination


def _unique_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON object key")
        result[key] = value
    return result


def _object(data: bytes) -> dict[str, Any]:
    try:
        value = json.loads(data.decode("utf-8", "strict"), object_pairs_hook=_unique_pairs,
                           parse_constant=lambda _: (_ for _ in ()).throw(
                               ValueError("Non-finite JSON number")))
    except (UnicodeError, json.JSONDecodeError) as invalid:
        raise ValueError("Invalid UTF-8 JSON") from invalid
    if not isinstance(value, dict):
        raise ValueError("Expected JSON object")
    return value


def _profile(data: bytes, expected: dict[str, Any]) -> None:
    if not 2 <= len(data) <= MAX_CONFIG_BYTES:
        raise ValueError("E5 configuration size outside bounds")
    actual = _object(data)
    for key, value in expected.items():
        if key not in actual or type(actual[key]) is not type(value) or actual[key] != value:
            raise ValueError("Non-canonical E5 configuration field: " + key)


def local_content_set_revision(files: list[dict[str, Any]]) -> str | None:
    """Reproduce the Android E5 assembly's *local*, non-upstream revision.

    Only the exact four-file E5 package shape is eligible. The identifier
    names a set of independently pinned bytes; it does not establish their
    origin, authorize inference, or assert a common publisher Git revision.
    """
    if not isinstance(files, list) or len(files) != 4:
        return None
    by_name = {item["name"]: item for item in files}
    if len(by_name) != 4:
        return None
    weights = [item for item in files if item["kind"] == "WEIGHTS"]
    if len(weights) != 1 or weights[0]["name"] not in {"model.onnx", "model_O4.onnx"}:
        return None
    ordered = [(weights[0]["name"], "WEIGHTS"),
               ("sentencepiece.bpe.model", "TOKENIZER"),
               ("config.json", "MODEL_CONFIG"),
               ("tokenizer_config.json", "TOKENIZER")]
    if set(by_name) != {name for name, _ in ordered}:
        return None
    identity = LOCAL_REVISION_DOMAIN
    for name, kind in ordered:
        item = by_name[name]
        if item["kind"] != kind or not _sha(item["sha256"]) \
                or type(item["bytes"]) is not int or item["bytes"] < 1:
            return None
        identity += f'{name}:{item["sha256"]}:{item["bytes"]}\n'
    return "sha256:" + hashlib.sha256(identity.encode("ascii")).hexdigest()


def require_official_onnx_pins(manifest: dict[str, Any], variant: str) -> None:
    """Opt-in manifest-only upstream *file* pins, checked before native ML.

    Manifest declarations are still authenticated by the caller's independent
    manifest hash and later checked against real bytes by preflight. File pins
    alone do NOT attest to a common upstream revision or publisher signature.
    """
    if variant not in OFFICIAL_ONNX:
        raise ValueError("Unknown official E5 ONNX variant")
    weight_name, weight_sha = OFFICIAL_ONNX[variant]
    expected = {
        ("WEIGHTS", weight_name): weight_sha,
        ("TOKENIZER", "sentencepiece.bpe.model"): OFFICIAL_SENTENCEPIECE_SHA256,
        ("MODEL_CONFIG", "config.json"): OFFICIAL_CONFIG_SHA256,
        ("TOKENIZER", "tokenizer_config.json"): OFFICIAL_TOKENIZER_CONFIG_SHA256,
    }
    files = manifest.get("files")
    if manifest.get("schema_version") != 1 or manifest.get("license") != "MIT" \
            or manifest.get("source_id") != EXPECTED_SOURCE \
            or manifest.get("weight_sha256") != weight_sha \
            or not isinstance(files, list) or len(files) != 4:
        raise ValueError("Official E5 ONNX package declaration differs from pinned profile")
    seen: set[tuple[str, str]] = set()
    for item in files:
        if not isinstance(item, dict) or set(item) != {"kind", "name", "sha256", "bytes"}:
            raise ValueError("Invalid official E5 ONNX component declaration")
        if not isinstance(item["kind"], str) or not isinstance(item["name"], str) \
                or type(item["bytes"]) is not int or item["bytes"] < 1:
            raise ValueError("Invalid official E5 ONNX component type/length")
        key = item["kind"], item["name"]
        if key in seen or key not in expected or item["sha256"] != expected[key]:
            raise ValueError("Official E5 ONNX component differs from independent file pin")
        if (key == ("MODEL_CONFIG", "config.json") and item["bytes"] != 653) \
                or (key == ("TOKENIZER", "tokenizer_config.json")
                    and item["bytes"] != 443):
            raise ValueError("Official E5 ONNX JSON length differs from file pin")
        seen.add(key)
    if seen != set(expected):
        raise ValueError("Incomplete official E5 ONNX component set")


def preflight(manifest_path: Path, expected_manifest_sha256: str,
              official_onnx_variant: str | None = None) -> CheckedPackage:
    """Verify every declared file before importing optional native ML modules."""
    if not _sha(expected_manifest_sha256):
        raise ValueError("Expected independent manifest SHA-256 is required")
    folder = manifest_path.parent
    if not stat.S_ISDIR(folder.lstat().st_mode):
        raise ValueError("Package directory is not a real directory")
    data = _read_regular(manifest_path, MAX_MANIFEST_BYTES)
    actual_manifest_sha256 = hashlib.sha256(data).hexdigest()
    if actual_manifest_sha256 != expected_manifest_sha256:
        raise ValueError("Package manifest SHA-256 differs from independent pin")
    manifest = _object(data)
    if set(manifest) != {"schema_version", "role", "format", "weight_sha256",
                         "source_id", "revision", "license", "files"}:
        raise ValueError("Unexpected package manifest fields")
    if type(manifest["schema_version"]) is not int or manifest["schema_version"] != 1:
        raise ValueError("Only self-contained ONNX package schema v1 is executable here")
    if manifest["role"] != "EMBEDDING" or manifest["format"] != "ONNX_UNVERIFIED" \
            or manifest["source_id"] != EXPECTED_SOURCE or not _sha(manifest["weight_sha256"]):
        raise ValueError("Not a declared canonical E5 Small ONNX package")
    revision = manifest["revision"]
    if not isinstance(revision, str) or not (REVISION.fullmatch(revision) or
            revision.startswith("sha256:") and _sha(revision[7:])):
        raise ValueError("Invalid declared E5 revision")
    if not isinstance(manifest["license"], str) or not 1 <= len(manifest["license"]) <= 128:
        raise ValueError("Missing declared license")
    if official_onnx_variant is not None:
        require_official_onnx_pins(manifest, official_onnx_variant)
    files = manifest["files"]
    if not isinstance(files, list) or not 3 <= len(files) <= 16:
        raise ValueError("Unexpected E5 package component count")
    names: set[str] = set()
    components: dict[str, list[Path]] = {}
    other_total = 0
    for item in files:
        if not isinstance(item, dict) or set(item) != {"kind", "name", "sha256", "bytes"}:
            raise ValueError("Invalid E5 manifest component")
        kind, name, digest, length = (item[k] for k in ("kind", "name", "sha256", "bytes"))
        if not isinstance(name, str) or NAME.fullmatch(name) is None or ".." in name \
                or name.lower() in names or not _sha(digest) \
                or type(length) is not int or length < 1:
            raise ValueError("Invalid or duplicate E5 component name/size/digest")
        if kind not in {"WEIGHTS", "TOKENIZER", "MODEL_CONFIG"}:
            raise ValueError("Unsupported E5 package component kind")
        names.add(name.lower())
        if kind == "WEIGHTS":
            if not name.endswith(".onnx") or length < 16 or length > MAX_INLINE_ONNX_BYTES:
                raise ValueError("ONNX exceeds the host-only in-memory verification limit")
            if digest != manifest["weight_sha256"]:
                raise ValueError("Model SHA-256 and WEIGHTS entry disagree")
        else:
            if length > MAX_OTHER_BYTES:
                raise ValueError("E5 sidecar exceeds 64 MiB")
            other_total += length
        path = folder / name
        _digest_file(path, length, digest)
        components.setdefault(kind, []).append(path)
    if other_total > MAX_OTHER_TOTAL_BYTES or len(components.get("WEIGHTS", [])) != 1:
        raise ValueError("Invalid E5 package component totals")
    if revision.startswith("sha256:") and revision != "sha256:" + manifest["weight_sha256"]:
        if manifest["license"] != "MIT" or revision != local_content_set_revision(files):
            raise ValueError("Invalid E5 local content-set revision")
    tokenizers = [p for p in components.get("TOKENIZER", []) if p.suffix == ".model"]
    configs = [p for p in components.get("MODEL_CONFIG", []) if p.name == "config.json"]
    if len(tokenizers) != 1 or len(configs) != 1:
        raise ValueError("A single E5 SentencePiece and config.json are required")
    component_pins = {item["name"]: item["sha256"] for item in files}
    config_path = configs[0]
    _profile(_read_regular(config_path, MAX_CONFIG_BYTES,
                           component_pins[config_path.name]), MODEL_PROFILE)
    opt_config = [p for paths in components.values() for p in paths
                  if p.name == "tokenizer_config.json"]
    if len(opt_config) > 1:
        raise ValueError("Duplicate E5 tokenizer configuration")
    if opt_config:
        _profile(_read_regular(opt_config[0], MAX_CONFIG_BYTES,
                               component_pins[opt_config[0].name]), TOKENIZER_PROFILE)
    weights = components["WEIGHTS"][0]
    tokenizer = tokenizers[0]
    return CheckedPackage(actual_manifest_sha256, weights, manifest["weight_sha256"],
                          tokenizer, next(item["sha256"] for item in files
                                          if item["name"] == tokenizer.name))


def _axis(value: Any, *, batch: bool = False, hidden: bool = False) -> int | None:
    if hidden:
        if type(value) is not int or value != 384:
            raise ValueError("E5 hidden dimension is not 384")
        return value
    if isinstance(value, str) and value:
        return None
    if value is None or (type(value) is int and value == -1):
        return None
    if type(value) is not int or (batch and value != 1) \
            or (not batch and not 3 <= value <= 512):
        raise ValueError("Invalid E5 ONNX fixed or dynamic axis")
    return value


def session_sequence(session: Any) -> int | None:
    """Pure metadata preflight; do not run ONNX before this returns."""
    raw_inputs = session.get_inputs()
    raw_outputs = session.get_outputs()
    inputs = {entry.name: entry for entry in raw_inputs}
    outputs = {entry.name: entry for entry in raw_outputs}
    if len(inputs) != len(raw_inputs) or len(outputs) != len(raw_outputs):
        raise ValueError("Duplicated E5 ONNX input/output name")
    if len(inputs) not in (2, 3) or set(inputs) - {
        "input_ids", "attention_mask", "token_type_ids"
    } or not {"input_ids", "attention_mask"}.issubset(inputs) \
            or len(outputs) not in (1, 2) or set(outputs) - {
                "last_hidden_state", "pooler_output"
            } or "last_hidden_state" not in outputs:
        raise ValueError("Unknown E5 ONNX input/output names")
    fixed: set[int] = set()
    for entry in inputs.values():
        if entry.type != "tensor(int64)" or len(entry.shape) != 2:
            raise ValueError("Invalid E5 INT64 input type/rank")
        _axis(entry.shape[0], batch=True)
        seq = _axis(entry.shape[1])
        if seq is not None:
            fixed.add(seq)
    hidden = outputs["last_hidden_state"]
    if hidden.type != "tensor(float)" or len(hidden.shape) != 3:
        raise ValueError("Invalid E5 FLOAT32 hidden state")
    _axis(hidden.shape[0], batch=True)
    seq = _axis(hidden.shape[1])
    _axis(hidden.shape[2], hidden=True)
    if seq is not None:
        fixed.add(seq)
    if "pooler_output" in outputs:
        pooler = outputs["pooler_output"]
        if pooler.type != "tensor(float)" or len(pooler.shape) != 2:
            raise ValueError("Invalid E5 pooler output")
        _axis(pooler.shape[0], batch=True)
        _axis(pooler.shape[1], hidden=True)
    if len(fixed) > 1:
        raise ValueError("E5 ONNX fixed sequence axes disagree")
    return next(iter(fixed)) if fixed else None


def e5_io_descriptor_sha256(session: Any) -> str:
    """Cross-language DPKS-E5-IO-v1 SHA over exact allowlisted ORT metadata.

    The Android protobuf reader and isolated worker implement the same
    canonical format. Symbolic axis names are deliberately reduced to -1;
    no model-specific strings are emitted in this diagnostic fingerprint.
    """
    session_sequence(session)  # Fail closed on unknown names/types/ranks/axes.
    inputs = {item.name: item for item in session.get_inputs()}
    outputs = {item.name: item for item in session.get_outputs()}
    canonical = ["DPKS-E5-IO-v1;"]
    for role, name, members, dtype in (
            ("I", "input_ids", inputs, "INT64"),
            ("I", "attention_mask", inputs, "INT64"),
            ("I", "token_type_ids", inputs, "INT64"),
            ("O", "last_hidden_state", outputs, "FLOAT32"),
            ("O", "pooler_output", outputs, "FLOAT32")):
        if name not in members:
            continue
        axes = members[name].shape
        canonical.append(f"{role}:{name}:{dtype}:{len(axes)}")
        for axis in axes:
            canonical.append(":" + str(-1 if axis is None or isinstance(axis, str) else axis))
        canonical.append(";")
    return hashlib.sha256("".join(canonical).encode("ascii")).hexdigest()


def padded_input(ids: list[int], sequence: int | None) -> tuple[list[int], list[int]]:
    if not isinstance(ids, list) or not 3 <= len(ids) <= 512 or ids[0] != 0 \
            or ids[-1] != 2 or any(type(i) is not int or i < 0 or i >= 250037
                                    for i in ids) or any(i in (0, 1, 2) for i in ids[1:-1]):
        raise ValueError("Unexpected XLM-R E5 token sequence")
    target = len(ids) if sequence is None else sequence
    if target < len(ids) or target > 512:
        raise ValueError("E5 sample cannot fit ONNX fixed sequence")
    padding = target - len(ids)
    return ids + [1] * padding, [1] * len(ids) + [0] * padding


def _f32(value: float) -> float:
    try:
        return struct.unpack("<f", struct.pack("<f", value))[0]
    except (OverflowError, struct.error) as invalid:
        raise ValueError("E5 embedding outside float32 range") from invalid


def pool_and_normalize(hidden: list[list[float]], mask: list[int]) -> list[float]:
    """Independent Python scalar implementation of Android masked-mean/L2."""
    if not isinstance(hidden, list) or len(hidden) != len(mask) \
            or not 3 <= len(mask) <= 512 or any(type(b) is not int or b not in (0, 1)
                                              for b in mask):
        raise ValueError("Unexpected E5 hidden state or mask")
    sums = [0.0] * 384
    active = sum(mask)
    if active < 3:
        raise ValueError("Too few active E5 tokens")
    if mask != [1] * active + [0] * (len(mask) - active):
        raise ValueError("E5 attention mask is not right-padded")
    for index, row in enumerate(hidden):
        if not isinstance(row, list) or len(row) != 384:
            raise ValueError("E5 hidden state has wrong shape")
        for dim, element in enumerate(row):
            if not isinstance(element, (int, float)) or not math.isfinite(element):
                raise ValueError("E5 hidden state contains non-finite values")
            if mask[index]:
                sums[dim] += float(element)
    avg = [_f32(s / active) for s in sums]
    norm2 = sum(v * v for v in avg)
    if not math.isfinite(norm2) or norm2 <= 1e-10:
        raise ValueError("E5 output has zero/invalid norm")
    norm = math.sqrt(norm2)
    return [_f32(v / norm) for v in avg]


def compare_reference(expected: dict[str, Any], actual: dict[str, Any],
                      tolerance: float = 0.00001) -> dict[str, Any]:
    """Compare a future Android export to this fixed, non-private reference."""
    fields = ("manifest_sha256", "model_sha256", "sentencepiece_sha256",
              "golden_token_fingerprint_sha256")
    if any(expected.get(f) != actual.get(f) for f in fields) \
            or set(expected.get("samples", {})) != set(SAMPLES) \
            or set(actual.get("samples", {})) != set(SAMPLES):
        raise ValueError("E5 reference identity or sample set differs")
    if "e5_io_descriptor_sha256" in expected or "e5_io_descriptor_sha256" in actual:
        left = expected.get("e5_io_descriptor_sha256")
        right = actual.get("e5_io_descriptor_sha256")
        if not _sha(left) or not _sha(right) or left != right:
            raise ValueError("E5 exact ONNX I/O descriptor fingerprint differs")
    maximum = 0.0
    for name in SAMPLES:
        lhs = expected["samples"][name]
        rhs = actual["samples"][name]
        if lhs["ids"] != rhs["ids"] or lhs["attention_mask"] != rhs["attention_mask"]:
            raise ValueError("E5 tokenizer IDs or attention masks differ")
        a, b = lhs["embedding"], rhs["embedding"]
        if not isinstance(a, list) or not isinstance(b, list) or len(a) != 384 or len(b) != 384:
            raise ValueError("Unexpected E5 reference embedding size")
        for x, y in zip(a, b):
            if type(x) not in (int, float) or type(y) not in (int, float) \
                    or not math.isfinite(x) or not math.isfinite(y):
                raise ValueError("Invalid E5 reference number")
            maximum = max(maximum, abs(x - y))
    return {"within_tolerance": maximum <= tolerance,
            "maximum_absolute_error": maximum, "tolerance": tolerance}


def require_inline_model(model_proto: Any) -> None:
    """Reject external tensor payloads at any nesting depth before native ORT.

    Protobuf reflection covers main/nested graphs, sparse tensors, node
    attributes and local functions. The raw tensor bytes are never decoded.
    """
    def walk(message: Any) -> None:
        if message.DESCRIPTOR.full_name == "onnx.TensorProto":
            if message.data_location != 0 or len(message.external_data):
                raise ValueError("External ONNX tensor data is not allowed in host QA")
        for field, value in message.ListFields():
            if field.type != field.TYPE_MESSAGE:
                continue
            if field.label == field.LABEL_REPEATED:
                for element in value:
                    walk(element)
            else:
                walk(value)
    walk(model_proto)


def _verified_cpu_session(package: CheckedPackage) -> tuple[Any, str]:
    """Open a metadata-checked ORT session; never call Run or resolve external shards."""
    try:
        import onnx
        import onnxruntime as ort
    except ImportError as missing:
        raise RuntimeError("Offline E5 metadata requires preinstalled onnx and onnxruntime") \
            from missing
    model = _read_regular(package.model_path, MAX_INLINE_ONNX_BYTES,
                          package.model_sha256)
    require_inline_model(onnx.load_model_from_string(model))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    session = ort.InferenceSession(model, sess_options=options,
                                   providers=["CPUExecutionProvider"])
    session_sequence(session)
    return session, ort.__version__


def inspect_metadata(package: CheckedPackage) -> dict[str, Any]:
    """Explicit CPU metadata-only reference; no tokenizer, session.run, or note IO."""
    session, runtime_version = _verified_cpu_session(package)
    return {
        "schema": "dpks-e5-io-reference-v1",
        "manifest_sha256": package.manifest_sha256,
        "model_sha256": package.model_sha256,
        "e5_io_descriptor_sha256": e5_io_descriptor_sha256(session),
        "inputs": len(session.get_inputs()),
        "outputs": len(session.get_outputs()),
        "onnxruntime_version": runtime_version,
    }


def execute(package: CheckedPackage) -> dict[str, Any]:
    """Load optional native runtimes only after the complete SHA/config preflight."""
    try:
        import numpy as np
        import sentencepiece  # noqa: F401 - required by slow tokenizer
        import transformers
        from transformers import XLMRobertaTokenizer
    except ImportError as missing:
        raise RuntimeError("Install numpy, sentencepiece and transformers "
                           "offline before running the E5 reference") from missing
    if transformers.__version__ != REFERENCE_TRANSFORMERS_VERSION:
        raise ValueError("Unexpected Transformers slow tokenizer version")
    # The upstream slow tokenizer accepts a *path*. A source path can change
    # after preflight, so hand it a private snapshot of the verified bytes.
    with tempfile.TemporaryDirectory(prefix="dpks-e5-reference-") as temporary:
        safe_model = _stage_pinned_tokenizer(package, Path(temporary))
        tokenizer = XLMRobertaTokenizer(vocab_file=str(safe_model),
                                        model_max_length=512)
        ids_reference = reference(tokenizer, package.sentencepiece_sha256,
                                  transformers.__version__)

    # The same verified CPU session path is used for metadata-only and full QA.
    session, runtime_version = _verified_cpu_session(package)
    sequence = session_sequence(session)
    io_descriptor_sha = e5_io_descriptor_sha256(session)
    has_types = any(item.name == "token_type_ids" for item in session.get_inputs())
    result: dict[str, Any] = {
        "schema": "dpks-e5-onnx-embedding-reference-v1",
        "manifest_sha256": package.manifest_sha256,
        "model_sha256": package.model_sha256,
        "sentencepiece_sha256": package.sentencepiece_sha256,
        "golden_token_fingerprint_sha256": ids_reference["golden_fingerprint_sha256"],
        "e5_io_descriptor_sha256": io_descriptor_sha,
        "transformers_version": transformers.__version__,
        "onnxruntime_version": runtime_version,
        "samples": {},
    }
    for name in SAMPLES:
        encoded = ids_reference["samples"][name]
        ids, mask = padded_input(encoded["ids"], sequence)
        feed = {"input_ids": np.asarray([ids], dtype=np.int64),
                "attention_mask": np.asarray([mask], dtype=np.int64)}
        if has_types:
            feed["token_type_ids"] = np.zeros((1, len(ids)), dtype=np.int64)
        hidden = session.run(["last_hidden_state"], feed)
        if len(hidden) != 1 or hidden[0].dtype != np.float32 \
                or hidden[0].shape != (1, len(ids), 384):
            raise ValueError("Unexpected native E5 last_hidden_state type or shape")
        vector = pool_and_normalize(hidden[0][0].tolist(), mask)
        result["samples"][name] = {
            "ids": encoded["ids"], "attention_mask": encoded["attention_mask"],
            "padded_sequence_length": len(ids), "embedding": vector,
        }
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True,
                        help="local dpks-model-package.json; never downloaded")
    parser.add_argument("--expected-manifest-sha256", required=True,
                        help="independently pinned lowercase SHA-256 of the manifest")
    parser.add_argument("--compare", type=Path,
                        help="optional, local Android-shaped reference JSON to compare")
    parser.add_argument("--metadata-only", action="store_true",
                        help="inspect exact E5 ORT I/O only; never call Run or tokenize text")
    parser.add_argument("--official-onnx-variant", choices=sorted(OFFICIAL_ONNX),
                        help="opt-in independent official file SHA pins; no download or provenance proof")
    args = parser.parse_args()
    if args.metadata_only and args.compare:
        parser.error("--compare requires the full reference; cannot use --metadata-only")
    package = preflight(args.manifest, args.expected_manifest_sha256,
                        official_onnx_variant=args.official_onnx_variant)
    output = inspect_metadata(package) if args.metadata_only else execute(package)
    if args.compare:
        candidate = _object(_read_regular(args.compare, MAX_COMPARE_BYTES))
        verdict = compare_reference(output, candidate)
        output["comparison"] = verdict
    print(json.dumps(output, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False))
    return 2 if args.compare and not output["comparison"]["within_tolerance"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
