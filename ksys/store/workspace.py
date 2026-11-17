"""Рабочее пространство базы знаний: где лежат журнал, индекс и отчёты.

Авторитетный файл — только ``journal`` (JSONL, в git). ``db`` — производный индекс.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Workspace:
    root: Path

    @property
    def journal_path(self) -> Path:
        return self.root / "data" / "formal" / "journal.jsonl"

    @property
    def db_path(self) -> Path:
        return self.root / "var" / "ks.sqlite"

    @property
    def reports_dir(self) -> Path:
        return self.root / "reports"

    @property
    def config_path(self) -> Path:
        return self.root / "ks.json"

    @property
    def rules_dir(self) -> Path:
        return self.root / "rules"

    @property
    def schemas_dir(self) -> Path:
        return self.root / "schemas"

    def config(self) -> dict:
        if self.config_path.exists():
            return json.loads(self.config_path.read_text(encoding="utf-8"))
        return {}

    def init(self, vault: str | None = None) -> None:
        for d in (self.journal_path.parent, self.db_path.parent, self.reports_dir):
            d.mkdir(parents=True, exist_ok=True)
        if not self.journal_path.exists():
            self.journal_path.write_text("", encoding="utf-8")
        cfg = self.config()
        if vault is not None:
            cfg["vault"] = vault
        cfg.setdefault("actor", "human:me")
        self.config_path.write_text(json.dumps(cfg, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                                    encoding="utf-8")
