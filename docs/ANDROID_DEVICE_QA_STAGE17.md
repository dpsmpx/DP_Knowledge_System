# Stage 17: Android device QA — human decision lifecycle slices

This is a **partial Stage 17 implementation**. One-record retraction and
revision, one-to-two split, two-to-one merge and bounded human batch acceptance
exist. Strict automated policies and broader multi-role batch approval are **not** complete.

## One-button synthetic acceptance check

1. Install the signed APK from the latest `knowledge-stage17-*` GitHub prerelease.
   Verify the accompanying `.sha256` file after downloading both artifacts.
2. Open the Knowledge System screen in the app and tap **Run device QA + copy**.
   The report is copied automatically. The synthetic `DECISION-17` and
   `REVISE-17`, `SPLIT-17`, `MERGE-17` and `BATCH-17` must all report `PASS`.
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

`REVISE-17` independently exercises the real Python `revise` path:

- Accept a synthetic fact and preview a different, single-node replacement.
- Reject a stale-head revision without changing the active record.
- Append one `revise` journal operation, retire the old record and activate
  only the replacement (while keeping history intact).
- Reject a repeated revision of the now-obsolete record.
- Check that the source hash is identical before and after the revision.

`SPLIT-17` exercises the new one-to-two atomic journal transition:

- Accept one synthetic fact; preview two distinct formally interpreted successors.
- Reject stale-head confirmation before any append.
- Append a **single** `split` entry, retire the old record and activate both
  independent successors; preserve back-references in journal replay.
- Reject repeating the split on an obsolete record.
- Verify the synthetic source hash is unchanged.

`MERGE-17` exercises an independent two-to-one transition:

- Explicitly accept two different synthetic facts of the same role.
- Preview one new, human-authored formally interpreted replacement.
- Reject a stale journal head without changing either accepted record.
- Append **one** `merge` event, retire both originals and activate one new
  successor with links back to both source record IDs.
- Reject replay on retired originals; preserve both synthetic source hashes.

`BATCH-17` exercises a bounded two-claim operation:

- Reparse two independent synthetic Markdown sources and select one reading each.
- Preview both without initializing the journal.
- Reject stale journal heads and altered note contents **without any acceptance**.
- Append one `batch_accept` event activating two separate claims, retaining
  source-specific provenance and the original read-only Markdown content.
- Reject replay of a previously committed batch.
- Refuse an unreviewed batch containing two directly opposite new claims,
  even if neither conflicts with the existing journal; separate human review
  remains available, and no claim is discarded or automatically judged false.

In the actual UI, choose **Review candidates** and select one candidate to
use the previous single-decision screen, or **2–8** candidates on the current
page to preview a batch. The confirmation dialog now displays a bounded
original-source excerpt (up to 500 characters) beside **each** chosen
interpretation and canonical statement; shortened excerpts are marked, and
the full original note can be opened separately. Confirm only after inspecting
these readings and their original context. This intentionally narrow batch **always uses
the `claim` role**: neither facts nor hypotheses are promoted by bulk action.
Conflicts against active knowledge, direct opposition **within the selected
batch**, already active, duplicate, unresolved and invalid interpretations
are blocked from this combined operation. A rejection dialog directs the user
to inspect individual entries. Contradictory claims are not prohibited from
separate explicit human decisions. The commit rereads the sources and checks the preview fingerprint
and journal head. All changes belong to one journal event; the app never edits
the original Markdown. Do not approve suggestions without reading their sources.

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

For a manual revision, choose **Revise with a new statement…** for a claim,
hypothesis or fact. Type a new standalone statement and optionally a reason;
select the formally analyzed reading. The final dialog shows the old and new
representations and conflict/diagnostic counts. **Cancel** must leave the
journal unchanged. Confirm only on an isolated test journal you can restore.

The current screen allows a **single human-approved retraction or revision**,
or a human-approved split into exactly two independent statements. For split,
enter two separate statements, choose each formal reading and inspect the final
comparison; cancelling must not append. Test only with a backed-up disposable
journal. This is not automatic knowledge derivation: the user independently
stands behind both new statements. The old record and provenance remain in the journal.

For manual merge, explicitly choose a second active record of the same role,
enter a new standalone statement and select its formal reading. Compare both
old canonical records to the new one before confirming. Cancel must leave
both original records active; test only in a disposable backed-up journal.
Do not interpret this as automatically proven semantic equivalence.
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
- Full Stage 17: split/merge/revision edge-cases, rich per-item batch roles and
  approval controls, versioned policy records, verifiably safe auto-approval;
  no automatic fact promotion.

The previous Android 15 owner reports confirmed `DECISION-17`, `REVISE-17`
and `SPLIT-17` in isolated synthetic tests, while two SQLite warnings and
manual/future checks remained. **They do not validate either the `MERGE-17` or
the new `BATCH-17` test**; run the current APK on an actual device before marking
these steps passed.
