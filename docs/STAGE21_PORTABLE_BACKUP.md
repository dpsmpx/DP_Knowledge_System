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

## Second slice: local WorkManager schedule (pending CI)

The user may explicitly opt into a 24-hour WorkManager schedule, which
Android can delay. A recovery passphrase is stored *only* in authenticated
Android Keystore-sealed preferences; it must be remembered separately for
clean-device import. A background worker reads the notebook and authoritative
journal, writes an encrypted temporary archive in app-specific external files,
decrypts and checks the completed archive, then renames it to a known-good
snapshot. Retention keeps up to three independently verified copies and never
removes the last verified copy before a new one is accepted.

These are **device-local** files, not off-device disaster recovery. Android
may delete app-specific files on uninstall and an unrecoverable hardware failure
loses them. Manual portable export to independent SAF storage is therefore
still essential. Scheduled background uploads are not enabled by this slice.

## Third slice: opt-in external SAF destination (pending CI)

The user can independently grant and revoke a persisted Android Storage Access
Framework tree permission. After producing and verifying a local encrypted
snapshot, the background job optionally copies **only ciphertext** to a newly
created file through the selected document provider and authenticates the
read-back bytes before reporting external success. Local and external success
are recorded separately. A failed newly created external file may be removed,
but previously verified external backups are never auto-deleted. This can work
with SAF-integrated Google Drive providers when installed and authorized; it is
**not** a native Google Drive OAuth integration and does not guarantee that a
provider has uploaded the read-back bytes durably to its remote server.

This differs from local daily backups: selecting an external provider is
**separate explicit consent**, and the provider may use the network. Failure,
revocation, quotas or missing provider support leave local backups intact.

No private vaults or credentials are used in fixtures or CI.

## Fourth slice: read-only offline journal branch comparison

The owner approved SAF/Syncthing/Drive as transport, explicit device pairing
and manual handling of divergent knowledge. A strict offline branch comparator
now validates both full hash chains and identifies identical, prefix,
diverged, and unrelated histories without modifying either journal.
The Android shell has a read-only action for selecting and comparing a
password-protected snapshot from another device. It does **not** import
foreign decisions without authenticated device pairing.

The separate [sync protocol](STAGE21_SYNC_PROTOCOL.md) defines the remaining
identity, causal inbox, conflict-resolution and transport contracts.
