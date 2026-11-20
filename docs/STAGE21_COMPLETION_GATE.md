# Stage 21 — completion and release gate (20 November 2026)

**Status: NOT PASS.** This checklist is a truthful engineering handoff, not
release approval. No APK was published or delivered. Before slice 19, the
pre-existing PR workflow **did build an unsigned debug APK on an ephemeral
GitHub Actions runner** (run #195, 44691540899); that workflow uploaded no
artifacts. From slice 19, Stage 21 CI skips all APK assembly tasks.

| Area | Current evidence | Gate |
| --- | --- | --- |
| Encrypted full snapshot + manifest | Versioned authenticated ciphertext, write/readback verification, clean-library restore; Android unit CI passed | Independent device restore |
| Opt-in 24-hour local daily backup | WorkManager, Keystore-sealed passphrase, retention and verified copies | Battery/OEM, reboot, low-storage device QA |
| Off-device backup | Separately consented SAF tree copy/readback | Real provider durability/revoked-permission tests |
| Settings | Safe v1/v2 settings remain compatible; v3 explicitly gated secret-entry transfer with unchecked per-export/per-import choice and second export warning; background archives stay v2/no secrets | Android v1/v2/v3 migration, security review of confidential URLs |
| Original Markdown exchange | Both encrypted-snapshot and direct plaintext `.md` SAF/manual routes: hash/excerpt compare, explicit per-file keep-both, exclusive create; no automatic overwrite | Real SAF provider, device lifecycle/storage, full large-document compare |
| Peer trust | Nonexportable signing identity, out-of-band code, revocation | Two physical devices and rotation QA |
| Divergent branches | Signed encrypted inbox, strict hash comparison, paged human review, separate AES/Keystore protected proper-prefix high-water history surviving inbox deletion | Device migration/recovery of trust ledger, rollback/replay acceptance |
| Human resolutions | Audit Defer/Reject, signed-head acknowledgment, source-reverified acceptance, contested/keep-local, anchored retraction | Device UI + concurrent-write review |
| Explicit contest warning | Per-record decision flags and general-query warning | TalkBack and user acceptance |
| Synthetic scale | 20,000-event fork verification, no false merge or records, tamper and 8 MiB rejection | **PASS in Python only**; not a 20k-file vault or Android stress test |
| Java unit suite | v3 secrets [#199](https://github.com/dpsmpx/DP_Knowledge_System/actions/runs/44710699191) and Markdown correction [#202](https://github.com/dpsmpx/DP_Knowledge_System/actions/runs/44713363668) passed both flavors; APK assembly skipped | New high-water unit tests and physical integration tests |

Local-JVM settings fixtures require the real `org.json` test-only library
because the Android stub API provides no working `JSONObject` parser outside
an Android runtime; the dependency does **not** enter the shipped application.
Protocol allowlist unit cases include the new signed-branch and conflict
APIs. The GitHub Actions Android SDK/Gradle runner passed these unit tasks;
it explicitly skipped package-ID and APK content inspections as requested.

## Deliberate non-features

- No automatic journal merge, last-write-wins, schema/alias/revision replay,
  remote Markdown overwrite, network peer polling, or model download.
- LLM API keys currently live only in the foreground session, not in app
  preferences, and cannot be transferred. The v3 secret allowlist handles
  only persisted embedding/LLM endpoints, including credentials embedded in
  their URLs. Android Keystore private keys are never exported. No background
  backup code path can attach the v3 secret entry.
- A valid signature proves a paired signing key, not human approval. Each
  accepted/retracted decision is a new local journal event, with local source
  and causal validation appropriate to that operation.
- An unresolved cross-device contest is an audit-layer conflict, not an
  automatically inferred contradictory fact. Query results say so.
- Manual SAF/provider transport is the supported path until an owner-approved
  alternative is specified. The provider may not guarantee cloud durability
  immediately after a local readback.
- Persistent proper-prefix SHA-256 commitments now survive deletion of the
  encrypted inbox under an independent device Keystore key. A missing ledger
  with the key still present fails closed. It is not a hardware monotonic
  counter, and migration/recovery of this evidence is not yet implemented.

## Closing conditions

1. Implement the **approved** scope in root `TODO.md`: opt-in v3 confidential
   endpoint export/restore is implemented, pending Android CI and device QA;
   encrypted-snapshot and plain Markdown SAF compare/keep-both are implemented;
   still implement manual handling of every
   foreign operation; durable anti-rollback high-water evidence. Owner choices
   were collected via polls on 20 November 2026; unimplemented behavior must
   remain disabled until reviewed and tested.
2. Android Java unit CI **passed** on both flavors. Physical integration,
   real APK installation and release need a new explicit owner instruction.
   The Stage 21 draft PR's *new* Android CI job is restricted to unit tests:
   debug APK assembly, package-ID and embedded-runtime APK inspection are
   disabled for this branch until that instruction arrives. Earlier runs
   under the old workflow did build unsigned, unpublished ephemeral APKs.
3. Complete **QA-21-01..06** on real devices, including 20,000 **files**,
   constrained storage, interruption and SAF provider behavior.
4. Complete the approved manually reviewed operation handlers and protected
   high-water ledger, including controlled transfer to a restored device.
   Extend the existing in-app QA buttons with safe, synthetic Stage 21 checks.
5. Re-review security/privacy, provenance, UI accessibility and the PR; only
   then remove the draft label and declare Stage 21 accepted.

No code, test fixture or documentation in this PR uses real private notes,
auth tokens, endpoint passwords or cloud account data.
