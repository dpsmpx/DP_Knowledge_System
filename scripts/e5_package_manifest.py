#!/usr/bin/env python3
"""Create a DPKS E5 package manifest from independently SHA-pinned local files.

This tool never downloads models or dependencies, opens a note vault, or starts
an ML runtime. The revision/license are *user assertions*, not authentication
of an upstream repository. Every component requires an independently supplied
expected SHA-256; no digest is silently promoted to a trusted pin.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path


SHA = re.compile(r"[0-9a-f]{64}\Z")
REVISION = re.compile(r"(?:[0-9a-f]{40}|sha256:[0-9a-f]{64})\Z")
LICENSE = re.compile(r"[A-Za-z0-9][A-Za-z0-9.+-]{0,63}\Z")
NAME = re.compile(r"[A-Za-z0-9_-][A-Za-z0-9._+-]{0,95}\Z")
MAX_SIDECAR = 64 * 1024 * 1024
MAX_SIDECAR_TOTAL = 256 * 1024 * 1024
MAX_EXTERNAL_TOTAL = 8 * 1024 * 1024 * 1024
MAX_MANIFEST = 64 * 1024
MAX_FILES = 16
SOURCE = "intfloat/multilingual-e5-small"
KINDS = {"WEIGHTS", "TOKENIZER", "MODEL_CONFIG", "ONNX_EXTERNAL_DATA"}


def _component(argument: str) -> tuple[str, str, str]:
    parts = argument.split(":", 2)
    if len(parts) != 3:
        raise ValueError("Component must be KIND:filename:expected_sha256")
    kind, name, digest = parts
    if kind not in KINDS or not NAME.fullmatch(name) or ".." in name \
            or name == "dpks-model-package.json" or not SHA.fullmatch(digest):
        raise ValueError("Unsupported component kind, name or expected SHA-256")
    if kind == "WEIGHTS" and not name.endswith(".onnx"):
        raise ValueError("E5 weights must be a .onnx file")
    if kind == "TOKENIZER" and name not in {"sentencepiece.bpe.model", "tokenizer_config.json"}:
        raise ValueError("Unsupported E5 tokenizer companion")
    if kind == "MODEL_CONFIG" and name not in {"config.json", "tokenizer_config.json"}:
        raise ValueError("Unsupported E5 configuration companion")
    return kind, name, digest


def _verified_size(directory_fd: int, name: str, expected_sha: str,
                   max_bytes: int | None) -> int:
    """Hash from one no-follow FD; recheck the same inode after the read."""
    if not hasattr(os, "O_NOFOLLOW"):
        raise ValueError("This host lacks safe no-follow file access")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | os.O_NOFOLLOW
    fd = os.open(name, flags, dir_fd=directory_fd)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise ValueError("Package component is not a regular file")
        if max_bytes is not None and before.st_size > max_bytes:
            raise ValueError("Package component exceeds its size limit")
        digest = hashlib.sha256()
        total = 0
        while True:
            chunk = os.read(fd, 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if max_bytes is not None and total > max_bytes:
                raise ValueError("Package component exceeds its size limit")
            digest.update(chunk)
        after = os.fstat(fd)
        if total != before.st_size or (before.st_dev, before.st_ino,
                before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns,
                after.st_ctime_ns):
            raise ValueError("Package component changed during SHA-256 audit")
        if digest.hexdigest() != expected_sha:
            raise ValueError("Package component differs from independent SHA-256 pin")
        return total
    finally:
        os.close(fd)


def build_manifest(directory: Path, revision: str, license_id: str,
                   components: list[str]) -> tuple[bytes, str]:
    if not REVISION.fullmatch(revision) or not LICENSE.fullmatch(license_id):
        raise ValueError("A pinned revision and declared license are required")
    if not 3 <= len(components) <= MAX_FILES:
        raise ValueError("E5 requires three to sixteen local package files")
    specs = [_component(value) for value in components]
    names = [name for _, name, _ in specs]
    if len(set(names)) != len(names):
        raise ValueError("Duplicate E5 component filenames")
    if sum(kind == "WEIGHTS" for kind, _, _ in specs) != 1 \
            or sum(name == "sentencepiece.bpe.model" for name in names) != 1 \
            or sum(name == "config.json" for name in names) != 1:
        raise ValueError("Exactly one ONNX weight, SentencePiece and config.json required")

    files = []
    sidecars = external = 0
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        raise ValueError("This host lacks safe directory descriptor access")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
    folder = os.open(directory, flags)
    try:
        if not stat.S_ISDIR(os.fstat(folder).st_mode):
            raise ValueError("Package directory is not a real directory")
        for kind, name, digest in specs:
            limit = (None if kind == "WEIGHTS" else MAX_EXTERNAL_TOTAL
                     if kind == "ONNX_EXTERNAL_DATA" else MAX_SIDECAR)
            size = _verified_size(folder, name, digest, limit)
            if kind == "WEIGHTS":
                if size < 16:
                    raise ValueError("ONNX weights are smaller than the format minimum")
            elif kind == "ONNX_EXTERNAL_DATA":
                if size < 1 or size > MAX_EXTERNAL_TOTAL - external:
                    raise ValueError("External ONNX shards exceed package limits")
                external += size
            else:
                if not 1 <= size <= MAX_SIDECAR or size > MAX_SIDECAR_TOTAL - sidecars:
                    raise ValueError("E5 sidecars exceed package limits")
                sidecars += size
            files.append({"kind": kind, "name": name, "sha256": digest, "bytes": size})
    finally:
        os.close(folder)
    # Stable manifest independent of --component argument order.
    files.sort(key=lambda item: (item["kind"] != "WEIGHTS", item["name"]))
    weight = next(item for item in files if item["kind"] == "WEIGHTS")
    manifest = {
        "schema_version": 2 if external else 1,
        "role": "EMBEDDING", "format": "ONNX_UNVERIFIED",
        "weight_sha256": weight["sha256"], "source_id": SOURCE,
        "revision": revision, "license": license_id, "files": files,
    }
    encoded = (json.dumps(manifest, ensure_ascii=True, indent=2) + "\n").encode("utf-8")
    if len(encoded) > MAX_MANIFEST:
        raise ValueError("Manifest exceeds Android's 64 KiB limit")
    return encoded, hashlib.sha256(encoded).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path,
                        help="local directory containing all package files")
    parser.add_argument("--revision", required=True,
                        help="independently pinned 40-hex commit or sha256:64-hex revision")
    parser.add_argument("--license", required=True,
                        help="declared license identifier, never a verified grant")
    parser.add_argument("--component", action="append", required=True,
                        help="KIND:filename:expected_sha256; repeat for each local file")
    parser.add_argument("--output", type=Path,
                        help="write new manifest here (refuses to overwrite); default stdout")
    args = parser.parse_args()
    try:
        manifest, manifest_sha = build_manifest(
            args.directory, args.revision, args.license, args.component)
        if args.output is None:
            sys.stdout.buffer.write(manifest)
        else:
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL
                         | os.O_NOFOLLOW, 0o600)
            identity = os.fstat(fd)
            try:
                with os.fdopen(fd, "wb") as output:
                    output.write(manifest)
                    output.flush()
                    os.fsync(output.fileno())
            except BaseException:
                # Remove only our own incomplete inode, never a substituted path.
                try:
                    current = args.output.lstat()
                    if (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                        args.output.unlink()
                except FileNotFoundError:
                    pass
                raise
        print("Manifest SHA-256 (record independently): " + manifest_sha, file=sys.stderr)
    except (OSError, ValueError) as rejected:
        parser.exit(2, "E5 manifest refused: " + str(rejected) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
