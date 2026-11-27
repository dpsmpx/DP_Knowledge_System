"""Offline E5 ONNX reference gates without native ML packages or model downloads."""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest import mock

from scripts.e5_onnx_reference import (
    MODEL_PROFILE, TOKENIZER_PROFILE, SAMPLES, _read_regular,
    _stage_pinned_tokenizer, compare_reference,
    e5_io_descriptor_sha256, inspect_metadata, padded_input, pool_and_normalize,
    preflight, require_inline_model,
    session_sequence,
)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def package(folder: Path, *, tokenizer_config: bool = True) -> tuple[Path, str]:
    components = [
        ("WEIGHTS", "model.onnx", b"synthetic-onnx-weightless-test-graph"),
        ("TOKENIZER", "sentencepiece.bpe.model", b"synthetic-bpe-model-stub"),
        ("MODEL_CONFIG", "config.json", json.dumps(MODEL_PROFILE).encode()),
    ]
    if tokenizer_config:
        components.append(("TOKENIZER", "tokenizer_config.json",
                           json.dumps(TOKENIZER_PROFILE).encode()))
    files = []
    for kind, name, data in components:
        (folder / name).write_bytes(data)
        files.append({"kind": kind, "name": name, "sha256": sha(data), "bytes": len(data)})
    manifest = {
        "schema_version": 1, "role": "EMBEDDING", "format": "ONNX_UNVERIFIED",
        "weight_sha256": files[0]["sha256"],
        "source_id": "intfloat/multilingual-e5-small",
        "revision": "sha256:" + files[0]["sha256"],
        "license": "MIT", "files": files,
    }
    data = json.dumps(manifest, ensure_ascii=False, sort_keys=True).encode()
    location = folder / "dpks-model-package.json"
    location.write_bytes(data)
    return location, sha(data)


def overwrite_manifest(path: Path, edit) -> str:
    content = json.loads(path.read_text())
    edit(content)
    encoded = json.dumps(content, sort_keys=True).encode()
    path.write_bytes(encoded)
    return sha(encoded)


class Input:
    def __init__(self, name: str, value_type: str, shape: list):
        self.name, self.type, self.shape = name, value_type, shape


class Session:
    def __init__(self, inputs: list[Input], outputs: list[Input]):
        self.inputs, self.outputs = inputs, outputs

    def get_inputs(self):
        return self.inputs

    def get_outputs(self):
        return self.outputs


def session(sequence=None, token_types=False, pooler=False) -> Session:
    seq = sequence if sequence is not None else "sequence"
    inputs = [Input("input_ids", "tensor(int64)", [1, seq]),
              Input("attention_mask", "tensor(int64)", [1, seq])]
    if token_types:
        inputs.append(Input("token_type_ids", "tensor(int64)", [1, seq]))
    outputs = [Input("last_hidden_state", "tensor(float)", [1, seq, 384])]
    if pooler:
        outputs.append(Input("pooler_output", "tensor(float)", [1, 384]))
    return Session(inputs, outputs)


class E5OnnxReferenceTest(unittest.TestCase):
    def test_metadata_only_cpu_path_never_runs_model_or_tokenizer(self):
        with tempfile.TemporaryDirectory() as temp:
            path, pin = package(Path(temp))
            checked = preflight(path, pin)
            fake_onnx = types.ModuleType("onnx")
            fake_onnx.load_model_from_string = lambda data: {"verified_bytes": sha(data)}
            fake_ort = types.ModuleType("onnxruntime")
            fake_ort.__version__ = "synthetic-test"
            fake_ort.SessionOptions = lambda: types.SimpleNamespace()
            fake_ort.GraphOptimizationLevel = types.SimpleNamespace(ORT_DISABLE_ALL="none")
            fake_ort.InferenceSession = lambda data, sess_options, providers: session()
            with mock.patch.dict(sys.modules, {"onnx": fake_onnx, "onnxruntime": fake_ort}):
                with mock.patch("scripts.e5_onnx_reference.require_inline_model") as gate:
                    output = inspect_metadata(checked)
            gate.assert_called_once()
            self.assertEqual("dpks-e5-io-reference-v1", output["schema"])
            self.assertEqual(checked.manifest_sha256, output["manifest_sha256"])
            self.assertEqual(checked.model_sha256, output["model_sha256"])
            self.assertEqual(2, output["inputs"])
            self.assertEqual(1, output["outputs"])
            self.assertEqual(
                "5e4d3a1bbbde5299429c3d789e15596cdd8271a0e04f0199278def6f9403e09b",
                output["e5_io_descriptor_sha256"])
            self.assertNotIn("samples", output)
            self.assertNotIn("embedding", output)

    def test_e5_io_v1_matches_android_canonical_fixtures(self):
        dynamic = Session([
            Input("input_ids", "tensor(int64)", ["batch", "sequence"]),
            Input("attention_mask", "tensor(int64)", ["batch", "sequence"]),
        ], [Input("last_hidden_state", "tensor(float)",
                  ["batch", "sequence", 384])])
        self.assertEqual(
            "ba6fae2bc91e325112ad050672b5dc3375ca84550480f358acfbf8075fc79640",
            e5_io_descriptor_sha256(dynamic))
        variant = session(sequence=6, token_types=True, pooler=True)
        self.assertEqual(
            "faecd1bd73a62364cdc9675b97ce35cf8af0c5a4131a750f9678dcd9bc329b85",
            e5_io_descriptor_sha256(variant))
        variant.inputs.reverse()
        variant.outputs.reverse()
        self.assertEqual(
            "faecd1bd73a62364cdc9675b97ce35cf8af0c5a4131a750f9678dcd9bc329b85",
            e5_io_descriptor_sha256(variant))
        variant.outputs[0].shape[0] = "dynamic_batch"
        self.assertNotEqual(
            "faecd1bd73a62364cdc9675b97ce35cf8af0c5a4131a750f9678dcd9bc329b85",
            e5_io_descriptor_sha256(variant))
        variant.inputs.append(Input("unknown", "tensor(int64)", [1, 6]))
        with self.assertRaisesRegex(ValueError, "Unknown"):
            e5_io_descriptor_sha256(variant)

    def test_native_tokenizer_uses_exact_pinned_private_snapshot(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            location, pin = package(folder)
            checked = preflight(location, pin)
            with tempfile.TemporaryDirectory() as temp_snapshot:
                staged = _stage_pinned_tokenizer(checked, Path(temp_snapshot))
                original_bytes = staged.read_bytes()
                self.assertEqual(sha(original_bytes), checked.sentencepiece_sha256)
                # Even if the original source is later replaced, the native
                # tokenizer receives the snapshot already verified by SHA.
                checked.sentencepiece_path.write_bytes(b"x" * len(original_bytes))
                self.assertEqual(original_bytes, staged.read_bytes())
                with self.assertRaisesRegex(ValueError, "pinned SHA-256"):
                    _stage_pinned_tokenizer(checked, folder)

    def test_post_preflight_native_inputs_reject_same_size_swap_and_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            location, pin = package(folder)
            checked = preflight(location, pin)
            old_bytes = checked.model_path.read_bytes()
            checked.model_path.write_bytes(b"x" * len(old_bytes))
            with self.assertRaisesRegex(ValueError, "pinned SHA-256"):
                _read_regular(checked.model_path, 1024, checked.model_sha256)
            checked.model_path.write_bytes(old_bytes)
            checked.model_path.rename(folder / "other.onnx")
            checked.model_path.symlink_to(folder / "other.onnx")
            with self.assertRaisesRegex(ValueError, "link"):
                _read_regular(checked.model_path, 1024, checked.model_sha256)

    def test_pinned_package_preflight_accepts_only_local_canonical_files(self):
        with tempfile.TemporaryDirectory() as temp:
            path, pin = package(Path(temp))
            checked = preflight(path, pin)
            self.assertEqual(pin, checked.manifest_sha256)
            self.assertEqual("model.onnx", checked.model_path.name)
            self.assertEqual("sentencepiece.bpe.model", checked.sentencepiece_path.name)
            self.assertEqual(sha((Path(temp) / "model.onnx").read_bytes()),
                             checked.model_sha256)
        with tempfile.TemporaryDirectory() as temp:
            path, pin = package(Path(temp), tokenizer_config=False)
            self.assertEqual(pin, preflight(path, pin).manifest_sha256)

    def test_wrong_pin_content_and_links_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path, pin = package(folder)
            with self.assertRaisesRegex(ValueError, "independent pin"):
                preflight(path, "0" * 64)
            (folder / "model.onnx").write_bytes(b"synthetic-onnx-weightless-test-graph!")
            with self.assertRaisesRegex(ValueError, "wrong size"):
                preflight(path, pin)
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path, pin = package(folder)
            original = folder / "sentencepiece.bpe.model"
            original.rename(folder / "original.model")
            original.symlink_to(folder / "original.model")
            with self.assertRaisesRegex(ValueError, "link"):
                preflight(path, pin)

    def test_manifest_path_traversal_duplicate_and_external_data_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path, _ = package(folder)
            pin = overwrite_manifest(path, lambda m: m["files"][0].update(name="../out.onnx"))
            with self.assertRaisesRegex(ValueError, "component name"):
                preflight(path, pin)
            path.write_bytes(b'{"schema_version":1,"schema_version":1}')
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                preflight(path, sha(path.read_bytes()))
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path, _ = package(folder)
            pin = overwrite_manifest(path, lambda m: m.update(schema_version=2))
            with self.assertRaisesRegex(ValueError, "schema v1"):
                preflight(path, pin)

    def test_bad_canonical_config_and_manifest_claims_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path, pin = package(folder)
            config = folder / "config.json"
            config.write_bytes(json.dumps({**MODEL_PROFILE, "hidden_size": 768}).encode())
            with self.assertRaisesRegex(ValueError, "wrong size|SHA-256"):
                preflight(path, pin)
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path, _ = package(folder)
            pin = overwrite_manifest(path, lambda m: m.update(source_id="some-other-e5"))
            with self.assertRaisesRegex(ValueError, "canonical"):
                preflight(path, pin)
            def bad_length(manifest):
                manifest["source_id"] = "intfloat/multilingual-e5-small"
                manifest["files"][0]["bytes"] = True
            pin = overwrite_manifest(path, bad_length)
            with self.assertRaisesRegex(ValueError, "name/size/digest"):
                preflight(path, pin)

    def test_metadata_gates_two_and_three_inputs_with_optional_pooler(self):
        self.assertIsNone(session_sequence(session(pooler=True)))
        self.assertEqual(32, session_sequence(session(sequence=32, token_types=True)))
        self.assertEqual(512, session_sequence(session(sequence=512, pooler=True)))
        invalid = session(sequence=8)
        invalid.outputs[0].shape[-1] = 768
        with self.assertRaisesRegex(ValueError, "hidden dimension"):
            session_sequence(invalid)
        invalid = session(sequence=8)
        invalid.inputs[1].shape[-1] = 9
        with self.assertRaisesRegex(ValueError, "disagree"):
            session_sequence(invalid)
        invalid = session()
        invalid.inputs.append(Input("mystery", "tensor(int64)", [1, 10]))
        with self.assertRaisesRegex(ValueError, "Unknown"):
            session_sequence(invalid)
        invalid = session()
        invalid.inputs[0].type = "tensor(int32)"
        with self.assertRaisesRegex(ValueError, "INT64"):
            session_sequence(invalid)
        invalid = session()
        invalid.inputs.append(Input("input_ids", "tensor(int64)", [1, 8]))
        with self.assertRaisesRegex(ValueError, "Duplicated"):
            session_sequence(invalid)

    def test_right_padding_and_special_token_refusals(self):
        self.assertEqual(([0, 5, 2, 1, 1], [1, 1, 1, 0, 0]),
                         padded_input([0, 5, 2], 5))
        self.assertEqual(([0, 5, 2], [1, 1, 1]), padded_input([0, 5, 2], None))
        for ids, sequence in [([0, 5, 2], 2), ([0, 1, 2], 3),
                              ([0, 250037, 2], None), ([5, 3, 2], 3),
                              ([0, 3, 0, 2], 4)]:
            with self.assertRaises(ValueError):
                padded_input(ids, sequence)

    def test_scalar_pooler_excludes_padding_and_rejects_invalid_outputs(self):
        rows = [[0.0] * 384 for _ in range(5)]
        rows[0][0] = 1.0
        rows[1][1] = 2.0
        rows[2][2] = 3.0
        rows[3][0] = 10000.0
        rows[4][0] = 10000.0
        output = pool_and_normalize(rows, [1, 1, 1, 0, 0])
        norm = math.sqrt(1 + 4 + 9)
        self.assertAlmostEqual(1 / norm, output[0], places=6)
        self.assertAlmostEqual(2 / norm, output[1], places=6)
        self.assertAlmostEqual(3 / norm, output[2], places=6)
        self.assertTrue(all(x == 0 for x in output[3:]))
        rows[4][0] = float("nan")
        with self.assertRaisesRegex(ValueError, "non-finite"):
            pool_and_normalize(rows, [1, 1, 1, 0, 0])
        with self.assertRaisesRegex(ValueError, "zero/invalid norm"):
            pool_and_normalize([[0.0] * 384 for _ in range(3)], [1, 1, 1])
        with self.assertRaisesRegex(ValueError, "right-padded"):
            pool_and_normalize([[1.0] * 384 for _ in range(5)], [1, 1, 0, 1, 0])

    def test_reference_comparison_is_bound_to_identity_tokens_and_tolerance(self):
        samples = {name: {"ids": [0, 5, 2], "attention_mask": [1, 1, 1],
                          "embedding": [0.0] * 384} for name in SAMPLES}
        doc = {"manifest_sha256": "a" * 64, "model_sha256": "b" * 64,
               "sentencepiece_sha256": "c" * 64,
               "golden_token_fingerprint_sha256": "d" * 64, "samples": samples}
        clone = json.loads(json.dumps(doc))
        self.assertTrue(compare_reference(doc, clone)["within_tolerance"])
        clone["samples"]["ru_query"]["embedding"][0] = 0.0001
        self.assertFalse(compare_reference(doc, clone)["within_tolerance"])
        clone["samples"]["ru_query"]["ids"] = [0, 6, 2]
        with self.assertRaisesRegex(ValueError, "tokenizer IDs"):
            compare_reference(doc, clone)
        clone = json.loads(json.dumps(doc))
        clone["manifest_sha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "identity"):
            compare_reference(doc, clone)
        doc["e5_io_descriptor_sha256"] = "a" * 64
        clone = json.loads(json.dumps(doc))
        self.assertTrue(compare_reference(doc, clone)["within_tolerance"])
        clone["e5_io_descriptor_sha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "descriptor fingerprint"):
            compare_reference(doc, clone)
        clone.pop("e5_io_descriptor_sha256")
        with self.assertRaisesRegex(ValueError, "descriptor fingerprint"):
            compare_reference(doc, clone)

    def test_reflective_external_tensor_gate_covers_nested_fields(self):
        class Field:
            TYPE_MESSAGE = 11
            LABEL_REPEATED = 3
            def __init__(self, is_repeated=False):
                self.type = 11
                self.label = 3 if is_repeated else 1

        class Message:
            def __init__(self, name, children=None, external=False):
                self.DESCRIPTOR = type("Descriptor", (), {"full_name": name})()
                self.data_location = 1 if external else 0
                self.external_data = ["location"] if external else []
                self.children = children or []
            def ListFields(self):
                return [(Field(True), self.children)] if self.children else []

        inner = Message("onnx.TensorProto", external=True)
        root = Message("onnx.ModelProto", [Message("onnx.GraphProto", [inner])])
        with self.assertRaisesRegex(ValueError, "External ONNX"):
            require_inline_model(root)
        require_inline_model(Message("onnx.ModelProto", [Message("onnx.TensorProto")]))


if __name__ == "__main__":
    unittest.main()
