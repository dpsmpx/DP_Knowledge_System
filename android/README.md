# DP Knowledge — Android shell (Stage 22)

Standalone Jetpack Compose application (`com.dpsmpx.dpks`).

## Status

| Phase | State |
|---|---|
| 22.0 Shell (tabs, theme, navigation, EN/RU) | done |
| **22.1 Notes + SAF + FTS index + LazyColumn** | **done** |
| 22.2 Inbox / decisions | next |
| 22.3–22.5 Journal / Ask / More | planned |

Architecture: [`docs/UI_ARCHITECTURE.md`](../docs/UI_ARCHITECTURE.md).

## 22.1 behaviour

- User picks a folder via **Storage Access Framework** (persistable read URI).
- `VaultScanner` walks the tree (`.md` / `.markdown`), skips hidden names, caps at 200 000 notes.
- `NotesIndex` stores a disposable SQLite FTS4 projection in `noBackupFilesDir`.
- Incomplete / truncated scans **never delete** preserved rows; they are marked unverified.
- Notes larger than 2 MiB are indexed by filename/title only.
- UI shows up to 250 hits, filter chips (All / Unverified / Recent), cancellable refresh.

Markdown files are **not** rewritten.

## Build

```bash
cd android
./gradlew :app:assembleDebug
```

Requires JDK 17+, Android SDK 35. Gradle wrapper may need to be generated once on a machine with the Android SDK.
