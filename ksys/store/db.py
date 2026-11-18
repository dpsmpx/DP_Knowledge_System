"""Производный SQLite-индекс. Может быть удалён в любой момент и пересобран.

Версия схемы БД в ``meta.db_schema``; при несовпадении индекс пересоздаётся
(авторитетные данные — в журнале, так что это безопасно).
"""
from __future__ import annotations

import sqlite3
from pathlib import Path

DB_SCHEMA_VERSION = "4"

DDL = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS documents (
    path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER NOT NULL, status TEXT NOT NULL,
    kind TEXT NOT NULL, flags TEXT NOT NULL DEFAULT '[]', fragment_count INTEGER NOT NULL DEFAULT 0,
    versions TEXT NOT NULL DEFAULT '', error TEXT);

CREATE TABLE IF NOT EXISTS fragments (
    id TEXT PRIMARY KEY, doc TEXT NOT NULL, locator TEXT NOT NULL, block_index INTEGER NOT NULL,
    block_type TEXT NOT NULL, unit_index INTEGER NOT NULL, unit_role TEXT NOT NULL,
    start INTEGER NOT NULL, end INTEGER NOT NULL, text TEXT NOT NULL, plain TEXT NOT NULL,
    depth INTEGER NOT NULL, heading_path TEXT NOT NULL, inline TEXT NOT NULL, meta TEXT NOT NULL,
    text_hash TEXT NOT NULL, kind TEXT NOT NULL, formalization TEXT NOT NULL, cues TEXT NOT NULL,
    ordinal INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS fragments_doc ON fragments(doc, ordinal);
CREATE INDEX IF NOT EXISTS fragments_kind ON fragments(kind);
CREATE INDEX IF NOT EXISTS fragments_text_hash ON fragments(text_hash);

CREATE TABLE IF NOT EXISTS candidates (
    id TEXT PRIMARY KEY, origin TEXT NOT NULL, reading_id TEXT NOT NULL, nodes TEXT NOT NULL,
    paraphrase TEXT NOT NULL, diagnostics TEXT NOT NULL, ambiguity TEXT NOT NULL,
    input_hash TEXT NOT NULL, ordinal INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS candidates_origin ON candidates(origin);

-- Disposable, exact-match cross-document claims. Every row still belongs to
-- an unapproved parser candidate and has its own source fragment provenance.
CREATE TABLE IF NOT EXISTS candidate_atoms (
    candidate_id TEXT NOT NULL,
    fragment_id TEXT NOT NULL,
    doc TEXT NOT NULL,
    locator TEXT NOT NULL,
    atom_key TEXT NOT NULL,
    predicate TEXT NOT NULL,
    negated INTEGER NOT NULL,
    PRIMARY KEY (candidate_id, atom_key, negated));
CREATE INDEX IF NOT EXISTS candidate_atoms_doc ON candidate_atoms(doc);
CREATE INDEX IF NOT EXISTS candidate_atoms_key ON candidate_atoms(atom_key, doc);

CREATE TABLE IF NOT EXISTS journal (
    seq INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, op TEXT NOT NULL, ts TEXT NOT NULL,
    actor TEXT NOT NULL, data TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS records (
    id TEXT PRIMARY KEY, role TEXT NOT NULL, status TEXT NOT NULL, node TEXT NOT NULL,
    knowledge_type TEXT, confidence TEXT, provenance TEXT NOT NULL, created_at TEXT, updated_at TEXT,
    journal TEXT NOT NULL, supersedes TEXT NOT NULL, superseded_by TEXT, ksf TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS records_status ON records(status, role);

CREATE TABLE IF NOT EXISTS items (
    aid TEXT PRIMARY KEY, predicate TEXT NOT NULL, arg0 TEXT, arg1 TEXT, negated INTEGER NOT NULL,
    ksf TEXT NOT NULL, layer TEXT NOT NULL, base TEXT NOT NULL, assertion TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS items_pred ON items(predicate, arg0, arg1);

CREATE TABLE IF NOT EXISTS justifications (
    id TEXT PRIMARY KEY, conclusion TEXT NOT NULL, rule TEXT NOT NULL, premises TEXT NOT NULL,
    layer TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS justifications_conclusion ON justifications(conclusion);

CREATE TABLE IF NOT EXISTS justification_premises (
    justification TEXT NOT NULL, premise TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS jp_premise ON justification_premises(premise);

CREATE TABLE IF NOT EXISTS labels (key TEXT NOT NULL, form TEXT NOT NULL, label TEXT NOT NULL,
    PRIMARY KEY (key, form));
CREATE TABLE IF NOT EXISTS aliases (alias TEXT PRIMARY KEY, canonical TEXT NOT NULL);
"""


def connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(path))
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    con.execute("PRAGMA foreign_keys=ON")
    current = None
    try:
        row = con.execute("SELECT value FROM meta WHERE key='db_schema'").fetchone()
        current = row[0] if row else None
    except sqlite3.OperationalError:
        pass
    if current not in (None, DB_SCHEMA_VERSION):
        con.close()
        for suffix in ("", "-wal", "-shm"):
            p = Path(str(path) + suffix)
            if p.exists():
                p.unlink()
        con = sqlite3.connect(str(path))
        con.execute("PRAGMA journal_mode=WAL")
    con.executescript(DDL)
    con.execute("INSERT OR REPLACE INTO meta(key, value) VALUES('db_schema', ?)", (DB_SCHEMA_VERSION,))
    con.commit()
    return con


def get_meta(con: sqlite3.Connection, key: str, default: str | None = None) -> str | None:
    row = con.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    return row[0] if row else default


def set_meta(con: sqlite3.Connection, key: str, value: str) -> None:
    con.execute("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)", (key, value))
