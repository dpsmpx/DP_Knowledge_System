"""Разделение слоёв (раздел L): статическая проверка импортов между пакетами."""
import ast
import unittest
from pathlib import Path

PKG = Path(__file__).resolve().parents[1] / "ksys"

# слой → слои, которые ему ЗАПРЕЩЕНО импортировать
FORBIDDEN = {
    "core": {"ir", "source", "language", "parser", "semantics", "store", "validation", "inference", "provenance",
             "argumentation", "temporal", "query", "importer", "export", "dialogue", "cli", "demo"},
    "ir": {"source", "language", "parser", "semantics", "store", "inference", "query", "importer", "export",
           "dialogue", "cli", "demo"},
    "inference": {"source", "language", "parser", "semantics", "store", "importer", "export", "dialogue", "cli"},
    "argumentation": {"source", "language", "parser", "semantics", "store", "importer", "dialogue", "cli"},
    "temporal": {"source", "language", "parser", "semantics", "store", "importer", "dialogue", "cli"},
    "language": {"source", "parser", "semantics", "store", "inference", "query", "importer", "dialogue", "cli"},
    "parser": {"source", "semantics", "store", "inference", "query", "importer", "dialogue", "cli"},
    "semantics": {"store", "inference", "query", "importer", "dialogue", "cli", "export"},
    "source": {"parser", "semantics", "store", "inference", "query", "dialogue", "cli"},
    "importer": {"inference", "query", "dialogue", "cli", "export"},
    "provenance": {"source", "parser", "importer", "dialogue", "cli"},
    "query": {"source", "importer", "dialogue", "cli"},
    "store": {"query", "dialogue", "cli", "importer", "export"},
    "dialogue": {"cli", "importer"},
}


def imported_layers(path: Path) -> set:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.level >= 1:
            mod = (node.module or "").split(".")
            if node.level == 2 and mod and mod[0]:
                out.add(mod[0])
            elif node.level == 2 and not mod[0]:
                out.update(a.name for a in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("ksys."):
            out.add(node.module.split(".")[1])
        elif isinstance(node, ast.Call) and getattr(node.func, "id", "") == "__import__" and node.args:
            arg = node.args[0]
            if isinstance(arg, ast.Constant) and str(arg.value).startswith("ksys."):
                out.add(str(arg.value).split(".")[1])
    return out


class LayeringTests(unittest.TestCase):
    def test_no_forbidden_imports(self):
        violations = []
        for layer, forbidden in FORBIDDEN.items():
            for f in sorted((PKG / layer).glob("*.py")):
                bad = imported_layers(f) & forbidden
                if bad:
                    violations.append(f"{layer}/{f.name} imports {sorted(bad)}")
        self.assertEqual(violations, [])

    def test_parser_and_semantics_never_write_journal(self):
        for layer in ("parser", "semantics", "language", "importer"):
            for f in (PKG / layer).glob("*.py"):
                src = f.read_text(encoding="utf-8")
                self.assertNotIn("journal.append", src, f)
                self.assertNotIn("Journal(", src, f)

    def test_core_has_no_llm_or_network_dependencies(self):
        banned = ("openai", "anthropic", "transformers", "torch", "requests", "urllib.request", "socket",
                  "sentence_transformers", "faiss", "chromadb")
        for f in PKG.rglob("*.py"):
            tree = ast.parse(f.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                names = [a.name for a in node.names] if isinstance(node, ast.Import) else \
                    [node.module or ""] if isinstance(node, ast.ImportFrom) else []
                for n in names:
                    self.assertFalse(any(n == b or n.startswith(b + ".") for b in banned), f"{f}: {n}")


if __name__ == "__main__":
    unittest.main()
