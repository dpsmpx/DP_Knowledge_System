"""Offline, dependency-free E5 package generator safety and compatibility tests."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.e5_package_manifest import build_manifest
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
