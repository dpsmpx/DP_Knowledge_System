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
| Candidates ≠ facts | Review screens always label epistemic role; no auto-promote styling |
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
| Theming | Material You dynamic color + explicit light/dark/system |
| Language | Kotlin; Python core remains via bridge (Chaquopy or successor) |

Markor source under `third_party/markor/` stays as **reference and optional
editor components only**. The launcher Activity is **not** Markor's.

## 3. Information architecture

### Top-level destinations (bottom navigation)

```
┌────────────┬────────────┬────────────┬────────────┬────────────┐
│  Library   │  Review    │  Knowledge │ Assistant  │   More     │
└────────────┴────────────┴────────────┴────────────┴────────────┘
```

1. **Library** — vault notes, search, open editor, index status chip
2. **Review** — candidate queue, batch select (2–8), single-decision flow
3. **Knowledge** — active decisions, contradictions, audit summary, provenance
4. **Assistant** — Ask core (deterministic) + optional LLM (opt-in consent)
5. **More** — processes, backup/export, settings, about, device QA (debug)

Secondary routes (pushed on top of a tab):

- `note/{relativePath}` — editor / preview
- `decision/{id}` — retract / revise / split / merge
- `candidate/{id}` — human intake (claim / hypothesis / fact / reject / defer)
- `audit/{category}` — paginated findings
- `process/{jobId}` — detailed progress + cancel
- `onboarding` — first-run vault pick + principles

### Global chrome

- Top app bar: context title, search (Library), overflow only when needed
- Floating / sticky **process strip** when any background job is running
  (index, bulk analysis, embedding, backup) — tappable → process detail
- Snackbars for non-blocking success/failure; dialogs only for irreversible
  or privacy-sensitive actions (LLM send, journal restore, policy enable)

## 4. Screen contracts (MVP of rewrite)

### 4.1 Library

- Lazy list of notes (path, title from first heading or filename, mtime)
- Search field with debounce; FTS results capped and labelled if truncated
- Filter chips: All / Unverified / Recently changed
- Pull-to-refresh triggers metadata scan (cancellable)
- Empty / no-permission / partial-scan states are first-class, not toasts only
- Long-press or overflow: Inspect (read-only core), possible backlinks

### 4.2 Review

- Paginated candidates from private semantic index
- Multi-select 2–8 → batch accept **as claims only** (existing BATCH-17 rules)
- Single tap → interpretation picker → role → confirm dialog with source hash
- Explicit copy: suggestions are not facts until the user decides

### 4.3 Knowledge

- Active journal records (role, short canonical text, source path)
- Actions: retract preview, revise, split, merge (same safety as Stage 17)
- Audit entry point with severity-separated categories
- Stale / unverified badges when source hash no longer matches

### 4.4 Assistant

- Tab or segmented control: **Core** | **LLM**
- Core: controlled-language query → epistemic status + evidence list
- LLM: endpoint/model (session or Keystore later), exact excerpt consent,
  plain-HTTP LAN warning, answer marked UNVERIFIED

### 4.5 More

- Background jobs toggle + last run status
- Encrypted journal export/import (existing `.dpks` format for now)
- Settings: theme, auto-analysis on refresh, notification permission
- About / licenses (Markor Apache-2.0 attribution preserved)
- Device QA buttons only in debug / prerelease builds

## 5. Design system

- Material 3 components (`MaterialTheme`, `NavigationBar`, `ListItem`,
  `FilterChip`, `LinearProgressIndicator`, `ModalBottomSheet`)
- Dynamic color when available; fallback brand seed (deep teal / slate)
- Typography: readable for long evidence text; monospace optional for KSF
- Motion: short shared-axis transitions; respect reduced-motion
- Accessibility: contentDescription on icon-only controls; 48 dp targets;
  TalkBack order follows reading order of evidence

### Epistemic color language (consistent across app)

| Role | Visual |
|---|---|
| Fact (approved) | primary container |
| Claim | secondary / neutral emphasis |
| Hypothesis | tertiary |
| Unknown / deferred | outline |
| Contested / conflict | error container |
| Unverified / stale source | warning amber badge |

## 6. Module layout (new tree)

```
android/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/dpsmpx/dpks/
        DpksApplication.kt
        MainActivity.kt
        ui/
          theme/Theme.kt
          navigation/DpksNavHost.kt
          library/LibraryScreen.kt
          review/ReviewScreen.kt
          knowledge/KnowledgeScreen.kt
          assistant/AssistantScreen.kt
          more/MoreScreen.kt
          components/…
        data/          # adapters to index, journal, bridge
        domain/        # pure models shared with UI
      res/
  # optional later: core-android library module for bridge without UI
```

Python `ksys/` remains at repository root. Build stages it into the app
(same approach as current Chaquopy sync task). Markor is **not** a Gradle
dependency of the new app.

## 7. Migration plan from prototype

| Phase | Deliverable |
|---|---|
| **22.0** | This doc + empty Compose shell (5 tabs, theme, navigation) — **this PR series** |
| **22.1** | Library screen wired to existing `KnowledgeLocalIndex` / scanner APIs (port Java → Kotlin adapters) |
| **22.2** | Review + single decision + batch (reuse Stage 17 journal rules) |
| **22.3** | Knowledge / decisions / audit surfaces |
| **22.4** | Assistant (core + LLM consent) |
| **22.5** | Processes strip, backup UI, settings, onboarding |
| **22.6** | Drop Markor launcher path from release flavor; keep `third_party/markor` for license and optional editor extraction |

Device QA scripts and synthetic fixtures stay; they target the new package.

## 8. Non-goals for the first UI PRs

- Full Obsidian visual parity
- Canvas / graph as primary navigation
- Shipping on-device LLM binary inside the APK
- Multi-window / foldable polish (later)
- Changing journal crypto or Python IR

## 9. Acceptance for Stage 22 overall

- User can open the app, pick a vault, browse 20k list without jank,
  run search, open a note, complete one human decision flow, export journal,
  all without CLI and without Markor chrome.
- Absence of LLM does not break any primary path.
- Dynamic light/dark works; basic TalkBack pass on main tabs.
- Signed prerelease `com.dpsmpx.dpks` installs beside nothing that depends on
  the old Markor activity entry points.

## 10. References

- [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) — Q-09 UI
- [OWNER_DECISIONS.md](OWNER_DECISIONS.md) — standalone Android-first
- [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) — Stage 22
- [TECHNICAL_DEBT.md](TECHNICAL_DEBT.md) — UX-20, UI-41
- Prototype: `third_party/markor/.../knowledge/KnowledgeHubActivity.java`
