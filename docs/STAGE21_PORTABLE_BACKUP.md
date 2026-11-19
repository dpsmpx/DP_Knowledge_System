# Stage 21 — encrypted portable snapshots (first implementation slice)

Status: **partial implementation; Stage 21 is not complete**. This document
records the actual behavior of the first Stage 21 PR, not a release claim.
Nothing in this PR ships an APK or starts a network transfer.

## Safety boundary

- In **Manage**, the legacy journal-only encrypted export/import remains intact.
- **Export full snapshot** explicitly selects an Android Storage Access Framework
  destination. The app encrypts the entire snapshot locally, reopens the saved
  ciphertext, authenticates all AES-256-GCM blocks, checks ZIP entries against a
  SHA-256 manifest, and reports success only after re-verification. The user
  must keep the passphrase; it is not stored, logged, or uploaded.
- The *DPKSB002* file format uses PBKDF2-HMAC-SHA1 (900,000 iterations,
  16-byte random salt) for Android API 24 compatibility and 1 MiB independent
  authenticated AES-GCM records with unique sequence-derived nonces. The
  authenticated terminal record is mandatory. A future Argon2id format needs
  its own versioned migration, not silent reinterpretation.
- Payload: every regular file in the selected notebook (not only Markdown),
  meta/journal.jsonl if present, meta/settings.json with an allowlist of
  **non-secret** model settings, and meta/manifest.dpks. Neither API keys,
  signing credentials, device-local secrets, FTS nor embeddings are exported.
- **Verify snapshot** validates in a temporary private cache location and never
  changes notes, settings, or journal.
- **Restore into a new library** validates the archive and checks the journal
  through the existing Python journal restore preview. It creates an entirely
  new sibling folder, copies verified source files, commits the journal only
  through the empty-journal, hash-checked API, and switches the selected
  notebook only after success. The original library is never overwritten.
  Conflicting journal histories are **rejected**, not merged.
- The ZIP importer checks manifest hashes and sizes, rejects duplicate names,
  folded-name collisions, traversal and symlink escapes, enforces individual
  and aggregate limits, and rejects incomplete encrypted records. An interrupted
  restore can leave an unselected newly created recovery folder; it must not
  silently delete original sources.
- Password-protected payloads are not compatible with the older journal-only
  *DPKSJ001* archive, and neither format should be mislabelled as the other.

## Explicitly missing / verification debt

This is **not** Stage 21 acceptance. The remaining scope includes:

1. A durable, battery-aware **daily encrypted backup** job, retention and
   recovery of the last known good copy; external SAF destination permissions,
   quota/error handling and restore drills.
2. Explicit user-controlled encrypted Google Drive backups (SAF or scoped
   OAuth flow); verified remote copy and disconnection.
3. Full policy/settings migration and versioned, reviewed handling of
   portable secrets with hardware-backed Keystore for nonexportable local keys.
4. Conflict-safe, causally tracked **multi-device journal sync** and
   migration from the single linear journal. A naive import of the other
   device's journal would destroy or hide divergent accepted decisions.
5. 20k-entry and low-storage load tests, device power interruption, activity
   rotation, background scheduling, actual SAF provider compatibility,
   accessibility and clean-device restore acceptance. These are **OPEN**, not PASS.
6. Android CI must compile and run the new pure-Java unit tests before merge.

No private vaults or credentials are used in fixtures or CI.
