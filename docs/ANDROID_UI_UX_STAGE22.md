# Android UI/UX Stage 22 — workspace, editor chrome and decision previews

Status: **first three slices integrated** (Preview #14 includes the dedicated
Decisions destination); the owner reports that decision review works in
their manual check. The full QA-54 checklist remains **OPEN**. **Fourth slice:
DPKS editor workspace improvements are under development, pending CI and
device QA**. This is *not* a replacement of the inherited Markor editor engine.

## Scope

- The DP Knowledge System screen has its own **Material 3 DayNight** theme
  with optional Android 12+ wallpaper-derived Dynamic Color. The legacy Markor
  editor's theme, editor functionality and file handling remain unchanged.
- Fixed bottom navigation separates **Library**, **Processes** and **Manage**.
  Switching sections only changes view visibility, **not** activity/workspace
  identity or background worker lifetime.
- The default **Library** screen shows selected vault identity, scan/index
  state, search, grounded Q&A actions and a bounded recycled note list. The
  long vertical stack of operational buttons no longer pushes search results
  offscreen. An empty-state message and 56dp minimum note-row height are added.
- **Processes** groups semantic analysis, embedding operations, optional
  background work and the two one-button device QA flows. In-progress jobs
  are indicated by a numerical badge on the Processes destination; analysis,
  embedding and device QA reports remain viewable without blocking navigation.
- **Manage** groups accepted decision review, knowledge audit, index
  diagnostics and *journal-only* encrypted export/import. The backup card
  explicitly warns that this is not a full app/vault backup.
- The existing actions and IDs are preserved and covered by static resource
  tests. Cancelling an embedding job no longer cancels an unrelated device QA
  run.

## Second slice — direct launch and source/decision surfaces

- In `com.dpsmpx.dpks` the launch icon opens **Knowledge Library directly**,
  through a flavor-controlled activity alias. MainActivity no longer owns the
  launcher intent. Non-DPKS Markor flavors retain their original launcher
  through the opposite alias flags. The DPKS launcher label is now
  **DP Knowledge System**, not the inherited test label.
- Opening a note from the library starts `KnowledgeDocumentActivity`. It hosts
  the existing Markor Markdown editor (editing, saving and preview functions
  are unchanged) under a dedicated Material 3 toolbar with explicit **back to
  library** navigation. Closing the note uses `finish()` so the library's
  Android task is not removed by the inherited editor's `finishAndRemoveTask`.
  A source-warning strip distinguishes *manual changes to Markdown* from
  *knowledge-journal decisions*.
- Confirmations for **revision, split, merge and retraction** now use a
  scrollable Material 3 review with individually labeled **before/after**
  blocks, selected text, change highlighting for one-to-one comparisons,
  diagnostics, warnings and explicit confirmation. No preview itself writes
  the source or the journal. The existing fingerprint, stale-head and
  concurrency validation still runs at commit time.

This is a UX integration layer, **not** yet a fully independent DPKS editor,
an audit-ready source-span diff or a replacement for the decision list dialogs.

## Third slice — first full-screen Decisions destination (initial feedback positive)

- Four bottom destinations: **Library / Decisions / Processes / Settings**.
  The editor, background jobs, and the local-first journal policy are unchanged.
- **Decisions** is accessible on every launch, even with an empty journal or
  no analysis index. It has eight searchable, bounded, count-labeled lists:
  pending interpretations, active records, fact records, hypothesis records,
  rejection events, deferral events, obsolete records and journal history.
- An interpretation counts as pending only if its source fragment has not
  already produced a saved record/deferral, and its formalized nodes have not
  all been explicitly rejected. Other readings of a partly rejected fragment
  can still be pending. Because old rejection events do not persist a full
  source snapshot, the **rejected/deferred tabs show journal events** rather
  than pretending they are full accepted knowledge records.
- Tapping an item opens a **full-height, scrollable, read-only detail screen**:
  source excerpt (labeled snapshot), machine interpretation or human record,
  formalized statement, available provenance, local diagnostics, available
  conflicts and related journal events. Missing evidence is visibly missing,
  not silently counted as zero. The original source is re-read and the journal
  head revalidated before any later commit.
- Existing guarded actions remain available via **Review interpretation** or
  **Revise / Split / Merge / Retract**. Batch approval remains explicitly
  accessible for 2–8 proposals; no journal append is made by browsing.
- Python core tests use only disposable synthetic Markdown and assert the
  journal/source invariant; resource tests assert four destinations and detail
  sections. The owner confirmed the ordinary decision review works after
  Preview #14, but comprehensive physical Android validation, large-list
  performance and TalkBack remain open. See `docs/STAGE22_DECISIONS_QA.md`.

## Fourth slice — note editor intelligence and context (pending CI and QA)

- The dedicated DPKS editor shell adds an explicit **Edit / Preview**
  control, retaining Markor's renderer, formatting support and proven
  file-save path. The control reflects existing Markor mode changes.
- **Analyze** runs the local Python parser on the editor buffer without
  source or journal writes, showing bounded fragment previews, semantic
  candidate counts and coverage status; these are machine interpretations,
  *not* accepted knowledge.
- **Review selection** maps Android UTF-16 selection offsets to Python
  Unicode code points, matches exactly one source fragment and opens the
  existing guarded decision preview. Selections spanning multiple fragments
  are refused rather than guessed.
- **Save-before-review guard:** a selected fragment can only be passed to
  decision review when the current editor text exactly equals the saved
  Markdown file. The receiving library rechecks the SHA-256 of the saved
  source before generating its normal preview, followed by existing
  source/journal freshness checks at commit. The UI never silently saves,
  changes the note or accepts an interpretation.
- **Links** displays only *possible lexical backlinks* from the last
  complete, vault-matched private index scan, opened read-only. Missing or
  stale scans do not claim an absence of links; navigation rechecks that the
  target stays inside the selected vault. This is not semantic backlink proof.
- Analysis and backlink queries run off the UI thread with stale-result
  suppression. No cloud request or telemetry is added. Synthetic Python
  and resource tests assert selection mapping, boundaries and UI guardrails.
  See `docs/STAGE22_EDITOR_QA.md` for the mandatory device checklist.

**Still incomplete:** this third slice is not a complete independent Markdown
editor or inline evidence-linked diff. Indexed snippets are not a guarantee
that original notes are still current; history displays journal events but
does not yet model every historical source span. Deep audit findings must be
opened through Settings. The old per-operation confirmation dialogs and
Markor Markdown editing engine are retained during the migration.

There are **no changes to authority, knowledge journal semantics, Markdown
sources, LLM permissions, APIs or the public data shipped with the APK**.

## One-device manual acceptance checklist (not automated PASS)

On an Android 15 device with test notes (avoid private journal mutations):

1. Open **DP Knowledge System**. Confirm the four bottom destinations,
   readable labels, color contrast in light/dark mode and wallpaper-driven
   color on Android 12+.
2. In **Library**, verify 760-note search (or another disposable collection),
   scroll, open note, long-press actions, search keyboard behavior, no toolbar
   or system-navigation overlap, and blank-query empty state.
3. Move to **Processes**, start the existing synthetic **Run device QA + copy**,
   switch to Library while it runs and back. The process badge must appear,
   the job must continue, status must update, and clipboard must contain the
   redacted report. Never run real provider requests for this check.
4. Repeat the tab switch during synthetic 20k stress only if storage and
   battery allow. Verify cancellation uses its original action and the UI
   remains responsive. Note cold/warm navigation and memory if possible.
5. In **Settings**, verify all review/audit/diagnostics/export/import controls
   are reachable. **Cancel** destructive or restorative dialogs. Do not
   import backup data into a real journal as a UI smoke test.
6. Rotate the device or change system theme, check selected tab restoration,
   text scaling, keyboard dismissal, contrast and readable scrolling on a
   small screen. Check accessibility labels with TalkBack if available.
7. Re-run the original synthetic Stage 15/16/17 reports; previous SQLite
   WARN and unfinished external/device checks must not be recast as PASS.
8. Cold-launch the **DP Knowledge System** icon, verify that **Library** opens
   immediately and no Markor-first activity or second launcher icon appears.
   Open an artificial `.md` note, switch Markdown edit/preview, save a manual
   edit in the disposable file, navigate back to the **same** DPKS library and
   confirm that Android Recents still contains DPKS. Test system Back and the
   editor's toolbar back; preserve and back up private notes beforehand.
9. In a **separate disposable knowledge journal**, review the Stage 17 revise,
   split, merge and retract *preview* dialogs. Check all before/after cards,
   long text, conflict warnings, Russian labels, font scaling, rotation and
   TalkBack. Cancel without committing for the UX smoke test. Test actual
   journal changes only with a verified backup and deliberately synthetic data.

## Remaining UI/UX work

- A full independent DPKS editor and intelligent workspace replacing the
  remaining Markor note-editing engine/menu chrome, while retaining reliable
  import/export and edit/preview workflows. The fourth slice adds visible
  edit/preview and guarded review/link entry points, but not an inline
  source-span diff, persistent sidebar or separate renderer.
- Accessible large-list navigation beyond the current 250 visible results,
  filtering/chips, meaningful progress history and pause/resume controls.
- Inline source-span-aware diff, evidence, selective approval and reversible
  proposal preview; the Decisions screen covers navigation and provenance,
  but explicit confirmation still uses the established dialogs.
- Screen-reader audit, reduced motion, large-font layouts, adaptive tablets,
  gesture navigation/edge-to-edge insets, instrumented UI tests and measured
  scrolling/rendering under 20k notes.
- Complete portable app backup, secure secret management and controlled sync.

No UI work changes the owner-confirmed rule: original Markdown is never
rewritten by autonomous analysis, and human confirmation is required for
authoritative knowledge changes outside a separately approved strict policy.
