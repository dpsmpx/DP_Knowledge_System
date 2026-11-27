"""Offline, dependency-free E5 package generator safety and compatibility tests."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.e5_package_manifest import (
    OFFICIAL_CONFIG_SHA256, OFFICIAL_ONNX, OFFICIAL_SENTENCEPIECE_SHA256,
    OFFICIAL_TOKENIZER_CONFIG_SHA256, _with_official_pins,
    build_manifest,
)
from scripts.e5_onnx_reference import MODEL_PROFILE, TOKENIZER_PROFILE, preflight


REVISION = "f" * 40


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def samples(folder: Path) -> list[str]:
    components = [
        ("WEIGHTS", "model.onnx", b"synthetic ONNX test fixture, not real weights"),
        ("TOKENIZER", "sentencepiece.bpe.model", b"synthetic SentencePiece test fixture"),
        ("MODEL_CONFIG", "config.json", b'{"model_type":"bert"}'),
        ("TOKENIZER", "tokenizer_config.json", b'{"tokenizer_class":"XLMRobertaTokenizer"}'),
    ]
    result = []
    for kind, name, data in components:
        (folder / name).write_bytes(data)
        result.append(f"{kind}:{name}:{sha(data)}")
    return result


class E5PackageManifestTest(unittest.TestCase):
    def test_official_catalog_matches_published_blob_hashes(self):
        self.assertEqual(
            ("model.onnx", "ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665"),
            OFFICIAL_ONNX["original"])
        self.assertEqual(
            ("model_O4.onnx", "4654c156f3e4171abc9c716cdb771bf9116455d15ac1aab364aeeede0e3205b0"),
            OFFICIAL_ONNX["o4"])
        self.assertEqual(
            "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865",
            OFFICIAL_SENTENCEPIECE_SHA256)
        self.assertEqual("bbb7c1333fc4b3e27fbc9cd5d2070aabcc1d4dfb99917c3633e772f97545a6b6",
                         OFFICIAL_CONFIG_SHA256)
        self.assertEqual("a1d6bc8734a6f635dc158508bef000f8e2e5a759c7d92f984b2c86e5ff53425b",
                         OFFICIAL_TOKENIZER_CONFIG_SHA256)

    def test_android_and_host_canonical_e5_file_pins_agree(self):
        java = (Path(__file__).resolve().parents[1] / "third_party" / "markor" / "app"
                / "src" / "main" / "java" / "net" / "gsantner" / "markor" /
                "knowledge" / "KnowledgeModelCatalog.java").read_text(encoding="utf-8")
        for _, digest in OFFICIAL_ONNX.values():
            self.assertIn('"' + digest + '"', java)
        for digest in (OFFICIAL_SENTENCEPIECE_SHA256, OFFICIAL_CONFIG_SHA256,
                       OFFICIAL_TOKENIZER_CONFIG_SHA256):
            self.assertIn('"' + digest + '"', java)

    def test_official_variant_supplies_four_fixed_package_file_pins(self):
        provided: list[str] = []
        for variant in ("original", "o4"):
            with self.subTest(variant=variant):
                components = _with_official_pins(provided, variant, "MIT")
                model_name, model_sha = OFFICIAL_ONNX[variant]
                self.assertEqual([f"WEIGHTS:{model_name}:{model_sha}",
                                  "TOKENIZER:sentencepiece.bpe.model:"
                                  + OFFICIAL_SENTENCEPIECE_SHA256,
                                  "MODEL_CONFIG:config.json:" + OFFICIAL_CONFIG_SHA256,
                                  "TOKENIZER:tokenizer_config.json:"
                                  + OFFICIAL_TOKENIZER_CONFIG_SHA256], components)
                self.assertEqual([], provided)
        self.assertIs(provided, _with_official_pins(provided, None, "MIT"))

    def test_official_variant_never_overrides_explicit_pins_or_license(self):
        config = "MODEL_CONFIG:config.json:" + "a" * 64
        weight = "WEIGHTS:model.onnx:" + "b" * 64
        tokenizer = "TOKENIZER:sentencepiece.bpe.model:" + "c" * 64
        tokenizer_config = "TOKENIZER:tokenizer_config.json:" + "d" * 64
        for components, variant, license_id in [
            ([weight], "original", "MIT"),
            ([tokenizer], "o4", "MIT"),
            ([config], "original", "MIT"),
            ([tokenizer_config], "o4", "MIT"),
            ([], "original", "Apache-2.0"),
            ([], "fake", "MIT"),
            (["not a component"], "original", "MIT"),
        ]:
            with self.subTest(components=components, variant=variant, license=license_id):
                with self.assertRaises(ValueError):
                    _with_official_pins(components, variant, license_id)

    def test_official_variant_hashes_local_files_and_rejects_substitutions(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            components = samples(folder)
            with self.assertRaisesRegex(ValueError, "independent SHA-256"):
                build_manifest(folder, REVISION, "MIT", [],
                               official_onnx_variant="original")
            # Synthetic bytes prove the opt-in wiring without downloading actual weights.
            synthetic_model_sha = sha((folder / "model.onnx").read_bytes())
            synthetic_tokenizer_sha = sha((folder / "sentencepiece.bpe.model").read_bytes())
            synthetic_config_sha = sha((folder / "config.json").read_bytes())
            synthetic_tokenizer_config_sha = sha((folder / "tokenizer_config.json").read_bytes())
            with patch.dict(OFFICIAL_ONNX, {
                "original": ("model.onnx", synthetic_model_sha),
            }), patch("scripts.e5_package_manifest.OFFICIAL_SENTENCEPIECE_SHA256",
                     synthetic_tokenizer_sha), patch(
                     "scripts.e5_package_manifest.OFFICIAL_CONFIG_SHA256",
                     synthetic_config_sha), patch(
                     "scripts.e5_package_manifest.OFFICIAL_TOKENIZER_CONFIG_SHA256",
                     synthetic_tokenizer_config_sha):
                manifest, _ = build_manifest(folder, REVISION, "MIT", [],
                                             official_onnx_variant="original")
            result = json.loads(manifest)
            self.assertEqual(synthetic_model_sha, result["weight_sha256"])
            self.assertEqual(4, len(result["files"]))
            self.assertEqual("ONNX_UNVERIFIED", result["format"])

    def test_complete_manifest_is_deterministic_and_matches_android_schema(self):
        with tempfile.TemporaryDirectory() as temp:
            args = samples(Path(temp))
            left, digest = build_manifest(Path(temp), REVISION, "MIT", args)
            right, other_digest = build_manifest(Path(temp), REVISION, "MIT", args[::-1])
            self.assertEqual((left, digest), (right, other_digest))
            self.assertEqual(sha(left), digest)
            result = json.loads(left)
            self.assertEqual({"schema_version", "role", "format", "weight_sha256",
                              "source_id", "revision", "license", "files"}, set(result))
            self.assertEqual(1, result["schema_version"])
            self.assertEqual("EMBEDDING", result["role"])
            self.assertEqual("ONNX_UNVERIFIED", result["format"])
            self.assertEqual("intfloat/multilingual-e5-small", result["source_id"])
            self.assertEqual(sha((Path(temp) / "model.onnx").read_bytes()),
                             result["weight_sha256"])
            self.assertEqual(4, len(result["files"]))
            self.assertEqual(["model.onnx", "config.json", "sentencepiece.bpe.model",
                              "tokenizer_config.json"], [item["name"] for item in result["files"]])

    def test_generated_manifest_passes_independent_host_package_preflight(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            args = samples(folder)
            replacements = {
                "config.json": json.dumps(MODEL_PROFILE).encode(),
                "tokenizer_config.json": json.dumps(TOKENIZER_PROFILE).encode(),
            }
            for name, data in replacements.items():
                (folder / name).write_bytes(data)
            refreshed = []
            for item in args:
                kind, name, digest = item.split(":", 2)
                refreshed.append(f"{kind}:{name}:{sha(replacements[name])}"
                                 if name in replacements else item)
            manifest, digest = build_manifest(folder, REVISION, "MIT", refreshed)
            location = folder / "dpks-model-package.json"
            location.write_bytes(manifest)
            checked = preflight(location, digest)
            self.assertEqual(digest, checked.manifest_sha256)
            self.assertEqual(sha((folder / "model.onnx").read_bytes()),
                             checked.model_sha256)

    def test_external_shard_requires_schema_v2(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            args = samples(folder)
            (folder / "model.onnx_data").write_bytes(b"synthetic external tensor shard")
            args.append("ONNX_EXTERNAL_DATA:model.onnx_data:"
                        + sha((folder / "model.onnx_data").read_bytes()))
            manifest, _ = build_manifest(folder, REVISION, "MIT", args)
            self.assertEqual(2, json.loads(manifest)["schema_version"])

    def test_missing_required_files_duplicate_names_and_invalid_pins_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            args = samples(folder)
            rejected = [args[:2], args + [args[-1]],
                        args[:-1] + ["TOKENIZER:tokenizer_config.json:" + "0" * 64],
                        args[:-1] + ["TOKENIZER:../escape.json:" + "a" * 64],
                        args[:-1] + ["TOKENIZER:other.model:" + "a" * 64],
                        args[:-1] + ["MODEL_CONFIG:config.json:bad"],
                        args[:-1] + ["TOKENIZER:dpks-model-package.json:" + "a" * 64]]
            for example in rejected:
                with self.subTest(example=example), self.assertRaises(ValueError):
                    build_manifest(folder, REVISION, "MIT", example)
            for revision, license_id in [("main", "MIT"), (REVISION, "bad license")]:
                with self.assertRaises(ValueError):
                    build_manifest(folder, revision, license_id, args)

    def test_modified_bytes_and_symlink_are_rejected_before_manifest_output(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            args = samples(folder)
            (folder / "config.json").write_bytes(b'{"model_type":"best"}')
            with self.assertRaisesRegex(ValueError, "independent SHA-256"):
                build_manifest(folder, REVISION, "MIT", args)
            args = samples(folder)
            original = folder / "sentencepiece.bpe.model"
            original.rename(folder / "original.model")
            original.symlink_to("original.model")
            with self.assertRaises(OSError):
                build_manifest(folder, REVISION, "MIT", args)

    def test_cli_never_overwrites_existing_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            args = samples(folder)
            destination = folder / "dpks-model-package.json"
            command = [sys.executable, "scripts/e5_package_manifest.py", "--directory",
                       str(folder), "--revision", REVISION, "--license", "MIT"]
            for arg in args:
                command.extend(["--component", arg])
            command.extend(["--output", str(destination)])
            first = subprocess.run(command, capture_output=True, check=False)
            self.assertEqual(0, first.returncode, first.stderr)
            original = destination.read_bytes()
            self.assertEqual(sha(original),
                             first.stderr.decode().strip().split(": ")[-1])
            second = subprocess.run(command, capture_output=True, check=False)
            self.assertNotEqual(0, second.returncode)
            self.assertEqual(original, destination.read_bytes())


if __name__ == "__main__":
    unittest.main()
