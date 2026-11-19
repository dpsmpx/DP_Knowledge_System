# DP Knowledge — Android shell (Stage 22)

Standalone Jetpack Compose application (`com.dpsmpx.dpks`).

This module **replaces** the Markor-based Knowledge Hub as the product UI.
`third_party/markor/` remains in the repository for license attribution and
optional editor component extraction only.

## Status

| Phase | State |
|---|---|
| 22.0 Shell (tabs, theme, navigation) | **this tree** |
| 22.1 Library + local index adapters | next |
| 22.2–22.5 Review / Knowledge / Assistant / More | planned |

Architecture: [`docs/UI_ARCHITECTURE.md`](../docs/UI_ARCHITECTURE.md).

## Build

Requires JDK 17+, Android SDK 35.

```bash
cd android
# Once: generate wrapper if missing
# gradle wrapper --gradle-version 8.11.1
./gradlew :app:assembleDebug
```

Python `ksys` bridge is **not** wired in 22.0. Offline UI shell only.

## Principles baked into empty states

- Markdown notes are user-owned; the app does not rewrite them silently.
- Candidates are not facts.
- Core paths work without LLM or network.
