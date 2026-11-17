# Publication and privacy boundary

This repository contains only the reusable knowledge-system engine, technical documentation, and synthetic fixtures. It must not contain a personal vault, credentials, account identifiers, imported fragments, generated reports, journals, model memories, or full historical Git objects from private sources.

## Rules for contributions

1. Do not commit private Markdown notes, real-person profile fixtures, `.obsidian` data, `ks.json`, `data/`, `reports/`, `var/`, `.env`, secrets, or environment-specific absolute paths.
2. Use newly invented synthetic text for tests. Copying a sentence from a private note into a test is still disclosure.
3. Add files by explicit allowlist and review each diff, including documentation, screenshots, JSON, comments, and workflows.
4. Test public commits and PRs without access to the author's private files. Never configure a CI workflow that reads personal vaults or privileged tokens from untrusted contributions.
5. If private content is accidentally pushed, removing the file in a later commit is insufficient: it remains in history. Contain the exposure, rotate any exposed credentials, and follow the repository host's history-redaction procedures.

## Boundaries

This is a research prototype. The Android application is planned; no third-party application code is bundled in this migration. Automated pattern checks reduce accidental leaks but do not prove that arbitrary prose is non-sensitive. Human review remains required.
