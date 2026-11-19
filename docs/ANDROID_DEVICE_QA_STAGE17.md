# Stage 17: Android device QA — decision retraction slice

This is a **partial Stage 17 implementation**. The full lifecycle (edit,
split, merge), batch review and strict automated policies are **not** complete.

## One-button synthetic acceptance check

1. Install the signed APK from the latest `knowledge-stage17-*` GitHub prerelease.
   Verify the accompanying `.sha256` file after downloading both artifacts.
2. Open the Knowledge System screen in the app and tap **Run device QA + copy**.
   The report is copied automatically. The synthetic `DECISION-17` step must
   report `PASS`.
3. Optionally tap **20k stress + copy** for the separate indexing load check.
4. Send only the **redacted** report, not your real notes, API tokens, journal
   files, or vault paths. The diagnostic test creates and removes its own
   temporary private-cache workspace; it never changes the real knowledge
   journal or Markdown vault.

`DECISION-17` exercises the actual embedded Python knowledge engine:

- Create an explicitly accepted synthetic fact in an isolated journal.
- List that fact and preview the consequences of retracting it without writes.
- Reject a request with an outdated journal head, leaving the fact active.
- Append an explicit retraction and confirm the record becomes inactive.
- Reject an attempt to retract the same record again.
- Confirm the synthetic source hash has not changed.

All output is count/boolean only. Any unexpected exception is reported only
by type, without a path, message or note text.

## Manual interface check (optional; backed-up data only)

1. Before modifying any **real** decisions, export and independently preserve
   the current encrypted journal backup. Uninstalling or clearing app data may
   remove the journal; a complete encrypted application backup is not yet ready.
2. Tap **Active decisions / retract**. Confirm the list contains only active
   records. No change occurs merely from opening the list.
3. Open an entry and inspect the role, canonical representation, predicted
   effect on derived assertions, and warning. Tap **Cancel** to perform no
   action, or **Retract knowledge record** only if the decision really should
   be withdrawn.
4. Reopen the list. The withdrawn record should no longer be active; its
   original journal entry must still be present in the append-only history.
5. Repeat the preview after any concurrent decision change: the stale preview
   must fail rather than silently retract a different state.

The current screen allows a **single human-approved retraction**, not bulk
or automatic changes. The old record and provenance remain in the journal.
Original Markdown is never modified by this feature.

## Outstanding QA and development debts

- `INDEX-INTEGRITY` / `SQLITE-RO-CHECK`: read-only SQLite `quick_check`
  discrepancy remains open; independent structural counts are consistent.
  Neither real corruption nor universal engine safety has been established.
- `MANUAL-01`: real local/cloud models, embedding provider and network privacy.
- `MANUAL-02`: process death, reboot, notification permission, scheduling under
  Android/OEM restrictions, power management, interrupted operations.
- `DEBT-01`: complete encrypted export/import, default daily backups, cloud
  synchronization and permission handling.
- Full Stage 17: revision, split/merge, versioned policy records, batch
  acceptance and verifiably safe auto-approval; no automatic fact promotion.

The previous Android 15 owner report showed `RECONCILE-16 PASS`, while two
SQLite warnings and manual/future checks remained. It does **not** substitute
for running the new `DECISION-17` test on the target device.
