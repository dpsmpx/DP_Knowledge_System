#!/usr/bin/env python3
"""Generate an entirely fake Markdown vault for repeatable Android endurance QA.

Never overwrites an existing directory. Do not run against a personal vault.
"""

import argparse
import hashlib
from pathlib import Path


def create_fixture(destination: Path, notes: int) -> dict[str, str]:
    if not 1 <= notes <= 200_000:
        raise ValueError("notes must be between 1 and 200000")
    if destination.exists() or destination.is_symlink():
        raise FileExistsError("Refusing to modify an existing directory")
    if not destination.parent.is_dir():
        raise FileNotFoundError("Destination parent must already exist")
    destination.mkdir()
    samples: dict[str, str] = {}
    for number in range(notes):
        group = destination / f"group-{number // 1000:03d}"
        group.mkdir(exist_ok=True)
        item = group / f"synthetic-{number:06d}.md"
        payload = (
            f"# Synthetic test {number}\n\n"
            "Only fake content: reproducible Android load fixture.\n\n"
            f"topic:: synthetic-{number % 37}\n\n"
            "This sentence tests local Markdown indexing and search without personal data.\n"
        ).encode("utf-8")
        item.write_bytes(payload)
        if number in (0, notes // 2, notes - 1):
            samples[str(item.relative_to(destination))] = hashlib.sha256(payload).hexdigest()
    return samples


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path,
                        help="NEW directory inside a dedicated disposable test location")
    parser.add_argument("--notes", type=int, default=20_000)
    arguments = parser.parse_args()
    samples = create_fixture(arguments.directory, arguments.notes)
    print(f"Generated {arguments.notes} synthetic notes in {arguments.directory}")
    print("Reference SHA-256 samples (relative path then hash):")
    for path, digest in samples.items():
        print(f"{path}: {digest}")


if __name__ == "__main__":
    main()
