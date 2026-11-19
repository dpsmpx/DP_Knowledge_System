# Stage 22 — Decisions workspace acceptance debt (QA-54)

Status: **OPEN** until CI and physical-device checks are attached to the PR.
These are new capabilities, not proof of an accepted Android release.

## Implemented scope in this branch

Four primary destinations and a dedicated Decisions screen, with category
filters and read-only detail views. Python read-only queries operate against
the existing journal and, if present, an already-created semantic SQLite index.
Source text and the source SHA shown in detail are **snapshots**. Existing
commit-time source and journal checks are not weakened.

## Required one-device checks (synthetic files only)

1. Cold launch: Library remains the first screen; open Decisions directly.
   An empty journal/index shows a stable empty state without creating either.
2. Run analysis on disposable notes. Check **To review**, search, 30-item
   pages, filter changes, and immediate navigation to Processes and Settings.
3. Open a candidate: source excerpt, machine reading and formal statement are
   visually separate. Check no candidate is labeled as an accepted fact.
   Opening the original note must not change the journal.
4. Review and accept a synthetic claim, hypothesis, and fact **separately**.
   Verify category counts refresh, provenance and the history event. Confirm
   the Markdown SHA remains unchanged. Simulate a source update between detail
   view and confirmation: commit must reject a stale preview.
5. On a backed-up disposable journal, reject, defer, revise, split, merge,
   and retract. Check no implicit write on opening/canceling details; the
   obsolete/history tabs show non-deleted past events. Never use real notes
   for these mutations.
6. Rotate, use Back and toolbar Back, test light/dark/dynamic color, font
   scale 200%, TalkBack, edge-to-edge navigation and a small screen. Verify
   all new labels and filters in RU/EN.
7. Stress with 20,000 synthetic notes: measure pending-list load, navigation,
   GC/RAM and scrolling. Do not treat Python unit tests as Android UX proof.
8. Check offline launch, background analysis during tab changes, process
   death/restart, and updated journal while a detail screen is open.

## Known limitations and follow-ups

- **QA-54 / OPEN:** the above physical-device checklist is unverified.
- **UX-55 / OPEN:** the detail screen is a read-only evidence surface but not
  yet an inline source-span diff or a single-stage all-action approval UI.
- **UX-56 / OPEN:** old rejection/deferral events do not have the same source
  provenance as accepted records; the UI must keep the distinction visible.
  Future journal schema/versioning should record richer decision evidence
  without rewriting existing append-only history.
- **UX-57 / OPEN:** category counts involve journal replay and, for pending
  candidates, a scan of the existing semantic index. Performance and
  cancellation during 20k-notes load have not been measured on devices.
- **EDITOR-58 / OPEN:** Markor retains Markdown edit/preview and file-save
  responsibility. Independent preview/edit toggles, inline backlinks,
  source-selection-to-review and a unified intelligent sidebar remain work.
- **SQLITE-46, QA-52, QA-53, MERGE-17, BATCH-17:** remain open; prior synthetic
  PASS is not a physical-device PASS and the SQLite read-only `quick_check`
  mismatch has not been diagnosed.
- Full original-vault backup, secure import/export and sync remain separate
  blocking product requirements.

This branch introduces no external services, note uploads or signing secrets.
Public CI must use synthetic fixtures only and must not print note paths or
user content.
