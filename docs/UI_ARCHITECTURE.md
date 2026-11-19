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
| minSdk | **26** (Android 8.0) — raised from prototype 24; owner allows higher |
| targetSdk / compileSdk | 35 |
| UI toolkit | **Jetpack Compose** + Material 3 |
| Navigation | Navigation Compose |
| Theming | Material You dynamic color + fallback deep-teal seed; light/dark/system |
| Locales | English (default) + Russian (`values-ru`) |
| Language | Kotlin; Python core remains via bridge (Chaquopy or successor) |

Markor source under `third_party/markor/` stays as **reference and optional
editor components only**. The launcher Activity is **not** Markor's.

## 3. Information architecture

### Top-level destinations (bottom navigation)

Short labels (fit bottom bar in EN and RU):

```
┌──────────┬──────────┬──────────┬──────────┬──────────┐
│  Notes   │  Inbox   │ Journal  │   Ask    │   More   │
│ Заметки  │Входящие  │  Журнал  │ Спросить │   Ещё    │
└──────────┴──────────┴──────────┴──────────┴──────────┘
```

1. **Notes** — vault files, search, open editor, index status
2. **Inbox** — candidate queue, batch select (2–8), single-decision flow
3. **Journal** — active approved records, contradictions, audit, provenance
4. **Ask** — Core (deterministic) | LLM (opt-in consent) via segmented tabs
5. **More** — processes, backup/export, appearance, about, device QA (debug)

Secondary routes (pushed on top of a tab):

- `note/{relativePath}` — editor / preview
- `decision/{id}` — retract / revise / split / merge
- `candidate/{id}` — human intake (claim / hypothesis / fact / reject / defer)
- `audit/{category}` — paginated findings
- `process/{jobId}` — detailed progress + cancel
- `onboarding` — first-run vault pick + principles

### Global chrome

- Top app bar: context title; search on Notes later; overflow only when needed
- **Process strip** above the bottom bar (always present, idle = “Ready” / «Готово»);
  becomes tappable when index/analysis/embedding/backup runs
- Snackbars for non-blocking success/failure; dialogs only for irreversible
  or privacy-sensitive actions (LLM send, journal restore, policy enable)

## 4. Screen contracts (MVP of rewrite)

### 4.1 Notes

- Lazy list of notes (path, title from first heading or filename, mtime)
- Search field with debounce; FTS results capped and labelled if truncated
- Filter chips: All / Unverified / Recent
- Pull-to-refresh triggers metadata scan (cancellable)
- Empty / no-permission / partial-scan states are first-class
- Long-press or overflow: Inspect (read-only core), possible backlinks

### 4.2 Inbox

- Paginated candidates from private semantic index
- Multi-select 2–8 → batch accept **as claims only** (BATCH-17 rules)
- Single tap → interpretation picker → role → confirm dialog with source hash
- Copy always reminds: suggestions are not facts until the user decides

### 4.3 Journal

- Active journal records (role, short canonical text, source path)
- Actions: retract preview, revise, split, merge (Stage 17 safety)
- Audit entry with severity-separated categories
- Stale / contested badges when source hash no longer matches

### 4.4 Ask

- `PrimaryTabRow`: **Core** | **LLM** (Ядро | LLM)
- Core: controlled-language query → epistemic status + evidence list
- LLM: endpoint/model, exact excerpt consent, plain-HTTP LAN warning,
  answer marked UNVERIFIED

### 4.5 More

- Background jobs + last run status
- Encrypted journal export/import (`.dpks` for now)
- Appearance: system / light / dark (dynamic color default on)
- About / licenses (Markor Apache-2.0 attribution preserved)
- Device QA only in debug / prerelease builds

## 5. Design system

- Material 3 (`MaterialTheme`, `NavigationBar`, `ListItem`, `FilterChip`,
  `PrimaryTabRow`, `LinearProgressIndicator`, `ModalBottomSheet`)
- Dynamic color on API 31+; fallback seed `#0B4F60` (deep teal)
- Full `surfaceContainer*` hierarchy for strip / lists / sheets
- Typography tuned for long evidence text; monospace optional for KSF
- Motion: short shared-axis; respect reduced-motion
- Accessibility: contentDescription on icon-only controls; 48 dp targets

### Epistemic color language (chips / badges only)

| Role | Visual |
|---|---|
| Fact | green container (`EpistemicColors.Fact*`) |
| Claim | blue-slate container |
| Hypothesis | tertiary / violet container |
| Unknown / deferred | outline |
| Contested | error container |
| Stale / unverified source | amber badge |

## 6. Module layout

```
android/app/src/main/java/com/dpsmpx/dpks/
  DpksApplication.kt
  MainActivity.kt
  ui/
    theme/Theme.kt, Type.kt
    navigation/DpksNavHost.kt
    notes/NotesScreen.kt
    review/InboxScreen.kt
    journal/JournalScreen.kt
    assistant/AskScreen.kt
    more/MoreScreen.kt
  data/   # 22.1+
  domain/ # 22.1+
```

Python `ksys/` stays at repository root. Markor is **not** a Gradle dependency.

## 7. Migration plan

| Phase | Deliverable |
|---|---|
| **22.0** | Doc + Compose shell (5 tabs, theme, EN/RU, process strip) — **current** |
| **22.1** | Notes + adapters to local index / vault scanner |
| **22.2** | Inbox + single decision + batch |
| **22.3** | Journal / decisions / audit |
| **22.4** | Ask (core + LLM consent) |
| **22.5** | Live process strip, backup UI, appearance prefs, onboarding |
| **22.6** | Release path without Markor launcher; keep `third_party/markor` for license |

## 8. Non-goals for the first UI PRs

- Full Obsidian visual parity
- Canvas / graph as primary navigation
- On-device LLM binary inside the APK
- Multi-window / foldable polish (later)
- Changing journal crypto or Python IR

## 9. Acceptance for Stage 22 overall

- Open app → pick vault → browse 20k list without jank → search → open note →
  complete one human decision → export journal — without CLI or Markor chrome.
- No LLM does not break primary paths.
- Dynamic light/dark; basic TalkBack on main tabs.
- Signed prerelease `com.dpsmpx.dpks` is independent of old Markor entry points.

## 10. References

- [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) — Q-09 UI
- [OWNER_DECISIONS.md](OWNER_DECISIONS.md) — standalone Android-first
- [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) — Stage 22
- [TECHNICAL_DEBT.md](TECHNICAL_DEBT.md) — UX-20, UI-41
- Prototype: `third_party/markor/.../knowledge/KnowledgeHubActivity.java`
