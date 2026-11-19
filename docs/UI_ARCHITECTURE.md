# DP Knowledge System — UI Architecture (Stage 22)

**Status:** in progress · **Branch:** `feature/ui-rewrite`  
**Decision (owner, 2026-11-19):** полный новый UI с нуля; самостоятельное
приложение; совместимость со старыми Markor-сборками **не требуется**.

This document is the single source of truth for the Android shell rewrite.
It does **not** change the deterministic knowledge core contracts
(Markdown authority, journal, provenance, UNKNOWN ≠ FALSE).

## 1. Product constraints that the UI must respect

| Constraint | UI consequence |
|---|---|
| Markdown is authoritative | Editor never silent-rewrites; risky ops show diff + confirm + ZIP backup path |
| Candidates ≠ facts | Inbox always labels epistemic role; no auto-promote styling |
| 20 000 notes | Virtualized lists only; no full-list adapters; progress never blocks navigation |
| Offline-first | App is fully usable without LLM or network; LLM is an optional surface |
| Journal is authoritative | Decisions UI is append-only in feel; retract/revise/split/merge are explicit |
| No private data in logs/CI | UI never dumps note text into crash reports by default |

## 2. Application identity

| Item | Value |
|---|---|
| Package / applicationId | `com.dpsmpx.dpks` |
| Display name | DP Knowledge |
| minSdk | **26** (Android 8.0) |
| targetSdk / compileSdk | 35 |
| UI toolkit | **Jetpack Compose** + Material 3 |
| Navigation | Navigation Compose |
| Theming | Material You dynamic color + fallback deep-teal seed |
| Locales | English (default) + Russian (`values-ru`) |
| Language | Kotlin; Python core via bridge in later phases |

## 3. Information architecture

### Top-level destinations (bottom navigation)

```
┌──────────┬──────────┬──────────┬──────────┬──────────┐
│  Notes   │  Inbox   │ Journal  │   Ask    │   More   │
│ Заметки  │Входящие  │  Журнал  │ Спросить │   Ещё    │
└──────────┴──────────┴──────────┴──────────┴──────────┘
```

1. **Notes** — vault files, search, open editor (later), index status — **22.1 live**
2. **Inbox** — candidate queue, batch, single-decision
3. **Journal** — approved records, contradictions, audit, provenance
4. **Ask** — Core | LLM tabs
5. **More** — processes, backup, appearance, about

### Global chrome

- Top app bar + **process strip** above bottom bar
- Snackbars for non-blocking feedback; dialogs for irreversible / privacy actions

## 4. Notes (22.1) — implemented behaviour

- SAF `OpenDocumentTree` with persistable read URI
- `VaultScanner` (DocumentFile BFS): `.md` / `.markdown`, skip hidden, max 200 000
- `NotesIndex`: SQLite FTS4 in `noBackupFilesDir`; max 2 MiB body per note
- Incomplete / truncated scans **preserve** unseen rows and mark them unverified
- UI: search field, filter chips (All / Unverified / Recent), LazyColumn (≤250),
  cancellable refresh with progress text
- Markdown is never written by this path

## 5. Design system

Material 3, dynamic color, `EpistemicColors` for badges only, EN+RU strings.

## 6. Module layout

```
android/app/src/main/java/com/dpsmpx/dpks/
  data/   VaultScanner, NotesIndex, NotesRepository, VaultPreferences, MarkdownMeta
  domain/ NoteModels
  ui/notes/ NotesScreen, NotesViewModel
  ui/navigation, theme, review, journal, assistant, more
```

## 7. Migration plan

| Phase | Deliverable | State |
|---|---|---|
| **22.0** | Compose shell, 5 tabs, theme, EN/RU | done |
| **22.1** | Notes + SAF + FTS + LazyColumn | **done** |
| **22.2** | Inbox + decisions + batch | next |
| **22.3** | Journal / audit | planned |
| **22.4** | Ask (core + LLM consent) | planned |
| **22.5** | Live process strip, backup, appearance, onboarding | planned |
| **22.6** | Release without Markor launcher | planned |

## 8. Non-goals for early UI PRs

- Obsidian parity, graph-first nav, on-device LLM binary in APK, journal crypto changes.

## 9. References

- PRODUCT_REQUIREMENTS / OWNER_DECISIONS / DEVELOPMENT_PLAN / TECHNICAL_DEBT
- Prototype scanner/index under `third_party/markor/.../knowledge/`
