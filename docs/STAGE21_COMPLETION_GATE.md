# Stage 21 — completion and release gate (20 November 2026)

**Status: NOT PASS.** This checklist is a truthful engineering handoff, not
release approval. No APK was built or distributed (owner restriction).

| Area | Current evidence | Gate |
| --- | --- | --- |
| Encrypted full snapshot + manifest | Versioned authenticated ciphertext, write/readback verification, clean-library restore | Android CI + independent device restore |
| Opt-in 24-hour local daily backup | WorkManager, Keystore-sealed passphrase, retention and verified copies | Battery/OEM, reboot, low-storage device QA |
| Off-device backup | Separately consented SAF tree copy/readback | Real provider durability/revoked-permission tests |
| Settings | Model labels plus v2 typed analysis preference, opt-in restore; all secrets excluded | Owner decision on portable secrets, v1/v2 device migration |
| Peer trust | Nonexportable signing identity, out-of-band code, revocation | Two physical devices and rotation QA |
| Divergent branches | Signed encrypted inbox, strict hash comparison, paged human review | External transport/rollback/replay acceptance |
| Human resolutions | Audit Defer/Reject, signed-head acknowledgment, source-reverified acceptance, contested/keep-local, anchored retraction | Device UI + concurrent-write review |
| Explicit contest warning | Per-record decision flags and general-query warning | TalkBack and user acceptance |
| Synthetic scale | 20,000-event fork verification, no false merge or records, tamper and 8 MiB rejection | **PASS in Python only**; not a 20k-file vault or Android stress test |
| Java unit suite | New tests cover archive, peer rollback boundary, safe settings, etc. | Android CI not run in this environment |

Local-JVM settings fixtures require the real `org.json` test-only library
because the Android stub API provides no working `JSONObject` parser outside
an Android runtime; the dependency does **not** enter the shipped application.
Protocol allowlist unit cases include the new signed-branch and conflict
APIs. These Java tests still need an Android SDK/Gradle CI runner.

## Deliberate non-features

- No automatic journal merge, last-write-wins, schema/alias/revision replay,
  remote Markdown overwrite, network peer polling, or model download.
- A valid signature proves a paired signing key, not human approval. Each
  accepted/retracted decision is a new local journal event, with local source
  and causal validation appropriate to that operation.
- An unresolved cross-device contest is an audit-layer conflict, not an
  automatically inferred contradictory fact. Query results say so.
- Manual SAF/provider transport is the supported path until an owner-approved
  alternative is specified. The provider may not guarantee cloud durability
  immediately after a local readback.
- The rollback guard applies only while a longer authenticated branch from
  the same signer remains retained. Globally durable high-water evidence is
  not yet implemented.

## Closing conditions

1. Resolve **TODO-21-01..03** in root `TODO.md`; preserve safe defaults until
   the owner explicitly chooses anything broader.
2. Run Android Java CI and compile the actual integrated app; no APK release
   without a new explicit owner instruction.
3. Complete **QA-21-01..06** on real devices, including 20,000 **files**,
   constrained storage, interruption and SAF provider behavior.
4. Agree on policy for unsupported journal operations and whether a stronger
   persistent rollback high-water ledger is required for the release target.
5. Re-review security/privacy, provenance, UI accessibility and the PR; only
   then remove the draft label and declare Stage 21 accepted.

No code, test fixture or documentation in this PR uses real private notes,
auth tokens, endpoint passwords or cloud account data.
